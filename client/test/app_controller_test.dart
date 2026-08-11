import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/core/database/app_database.dart';
import 'package:photobook/models/archive_job.dart';
import 'package:photobook/models/post.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';

void main() {
  test('连续任务刷新只查询任务表并合并为当前查询和一次尾随查询', () async {
    final database = _DelayedDatabase();
    final controller = AppController(database: database);

    final first = controller.refreshTasks();
    final second = controller.refreshTasks();
    final third = controller.refreshTasks();
    expect(database.taskReadCount, 1);
    expect(database.postReadCount, 0);

    database.releaseFirstTaskRead();
    await Future.wait([first, second, third]);

    expect(database.taskReadCount, 2);
    expect(database.postReadCount, 0);
    expect(controller.tasks.single.id, 'latest');
    controller.dispose();
  });

  test('初始化先订阅任务事件，初始读取期间的变化会触发尾随读取', () async {
    final database = _DelayedDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );

    final initialization = controller.initialize();
    await database.firstTaskReadStarted.future;
    runtime.emitJobChanged();
    database.releaseFirstTaskRead();
    await initialization;

    expect(database.taskReadCount, 2);
    expect(controller.tasks.single.id, 'latest');
    controller.dispose();
    await runtime.close();
  });

  test('进入前台时使用自动模式检查剪贴板', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    await controller.initialize();

    await controller.setForeground(true);
    await controller.setForeground(true);

    expect(runtime.clipboardAutomaticCalls, [true]);
    expect(controller.message, isNull);

    await controller.setForeground(false);
    await controller.setForeground(true);

    expect(runtime.clipboardAutomaticCalls, [true, true]);
    controller.dispose();
    await runtime.close();
  });

  test('初始化取得 R2 配置后使用当前资料库身份重新读取帖子', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge()
      ..r2Config = const R2ConfigSummary(
        endpoint: 'https://example.r2.cloudflarestorage.com',
        bucket: 'photobook-test',
        prefix: 'photobook',
        accessKeyIdHint: 'abc…xyz',
        backupTargetId: 'target-a',
      );
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );

    await controller.initialize();

    expect(database.postBackupTargetIds, [null, 'target-a']);
    controller.dispose();
    await runtime.close();
  });

  test('R2 配置已保存但列表刷新失败时保留成功结果并提示稍后重试', () async {
    final database = _FailingReloadDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    const input = R2ConfigInput(
      endpoint: 'https://example.r2.cloudflarestorage.com',
      bucket: 'photobook-test',
      prefix: 'photobook',
      accessKeyId: 'access-key',
      secretAccessKey: 'secret-key',
    );

    await expectLater(controller.saveR2Config(input), completes);

    expect(runtime.savedR2Config, input);
    expect(controller.r2Config?.backupTargetId, 'target-a');
    expect(controller.message, 'R2 配置已保存，但本地状态刷新失败，稍后会自动重试');
    controller.dispose();
    await runtime.close();
  });

  test('保存到系统相册成功后不触发全量数据刷新', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    const media = PostMedia(
      id: 'media-1',
      mediaType: PostMediaType.image,
      width: 1080,
      height: 1350,
    );

    final firstName = await controller.saveMedia(media);
    final secondName = await controller.saveMedia(media);

    expect(firstName, 'PhotoBook_media-1.jpg');
    expect(secondName, 'PhotoBook_media-1.jpg');
    expect(runtime.savedMediaIds, ['media-1', 'media-1']);
    expect(database.postReadCount, 0);

    await controller.refreshAfterMediaSave();
    expect(database.postReadCount, 1);
    controller.dispose();
    await runtime.close();
  });

  test('删除已提交但刷新失败时保留成功结果并更新内存状态', () async {
    final database = _FailingReloadDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    )..posts = [_postWithMedia()];

    final result = await controller.deleteMediaSelection('post-1', [
      controller.posts.single.media.first,
    ]);

    expect(result.postDeleted, isFalse);
    expect(runtime.deletedMediaIds, ['media-0']);
    expect(controller.posts.single.media.map((item) => item.id), ['media-1']);
    expect(controller.posts.single.coverMediaId, 'media-1');
    expect(controller.posts.single.isBackedUp, isTrue);
    expect(controller.message, '删除已完成，但列表刷新失败，稍后会自动重试');
    controller.dispose();
    await runtime.close();
  });

  test('整帖删除已提交但刷新失败时立即移除内存帖子', () async {
    final database = _FailingReloadDatabase();
    final runtime = _FakeRuntimeBridge()..deleteSelectionPostDeleted = true;
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    )..posts = [_postWithMedia()];

    final result = await controller.deleteMediaSelection(
      'post-1',
      controller.posts.single.media,
    );

    expect(result.postDeleted, isTrue);
    expect(controller.posts, isEmpty);
    controller.dispose();
    await runtime.close();
  });

  test('媒体保存成功后的统一刷新失败不推翻保存结果', () async {
    final database = _FailingReloadDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    const media = PostMedia(
      id: 'media-1',
      mediaType: PostMediaType.image,
      width: 1080,
      height: 1350,
    );

    final displayName = await controller.saveMedia(media);
    await controller.refreshAfterMediaSave();

    expect(displayName, 'PhotoBook_media-1.jpg');
    expect(controller.message, '媒体已保存，但本地状态刷新失败');
    controller.dispose();
    await runtime.close();
  });
}

class _ImmediateDatabase extends AppDatabase {
  int postReadCount = 0;
  final List<String?> postBackupTargetIds = [];

  @override
  Future<void> initialize() async {}

  @override
  Future<List<ArchivedPost>> listPosts({String? backupTargetId}) async {
    postReadCount += 1;
    postBackupTargetIds.add(backupTargetId);
    return const [];
  }

  @override
  Future<List<ArchiveJob>> listVisibleJobs() async => const [];

  @override
  Future<BackupStatus> readBackupStatus() async => const BackupStatus();

  @override
  Future<void> close() async {}
}

class _FailingReloadDatabase extends _ImmediateDatabase {
  @override
  Future<List<ArchivedPost>> listPosts({String? backupTargetId}) async {
    postReadCount += 1;
    postBackupTargetIds.add(backupTargetId);
    throw StateError('测试数据库读取失败');
  }
}

class _DelayedDatabase extends AppDatabase {
  final Completer<void> _firstTaskRead = Completer<void>();
  final Completer<void> firstTaskReadStarted = Completer<void>();
  int postReadCount = 0;
  int taskReadCount = 0;
  final List<String?> postBackupTargetIds = [];

  void releaseFirstTaskRead() => _firstTaskRead.complete();

  @override
  Future<void> initialize() async {}

  @override
  Future<List<ArchivedPost>> listPosts({String? backupTargetId}) async {
    postReadCount += 1;
    postBackupTargetIds.add(backupTargetId);
    return const [];
  }

  @override
  Future<List<ArchiveJob>> listVisibleJobs() async {
    taskReadCount += 1;
    if (taskReadCount == 1) {
      firstTaskReadStarted.complete();
      await _firstTaskRead.future;
    }
    return [
      ArchiveJob(
        id: taskReadCount == 1 ? 'stale' : 'latest',
        sourcePostId: 'ABC123',
        status: ArchiveJobStatus.queued,
      ),
    ];
  }

  @override
  Future<BackupStatus> readBackupStatus() async => const BackupStatus();

  @override
  Future<void> close() async {}
}

class _FakeRuntimeBridge extends ArchiveRuntimeBridge {
  final StreamController<ArchiveRuntimeEvent> _events =
      StreamController<ArchiveRuntimeEvent>.broadcast(sync: true);
  final List<bool> clipboardAutomaticCalls = [];
  final List<String> savedMediaIds = [];
  final List<String> deletedMediaIds = [];
  bool deleteSelectionPostDeleted = false;
  R2ConfigSummary? r2Config;
  R2ConfigInput? savedR2Config;

  @override
  Stream<ArchiveRuntimeEvent> get events => _events.stream;

  @override
  Future<ArchiveRuntimeState> getRuntimeState() async => ArchiveRuntimeState(
    activeJobCount: 0,
    failedJobCount: 0,
    r2Config: r2Config,
  );

  @override
  Future<void> backupNow() async {}

  @override
  Future<R2ConfigSummary> saveR2Config(R2ConfigInput config) async {
    savedR2Config = config;
    return r2Config = const R2ConfigSummary(
      endpoint: 'https://example.r2.cloudflarestorage.com',
      bucket: 'photobook-test',
      prefix: 'photobook',
      accessKeyIdHint: 'ac****ey',
      backupTargetId: 'target-a',
    );
  }

  @override
  Future<String> saveMedia(
    String mediaId, {
    String exportMode = 'original',
  }) async {
    savedMediaIds.add(mediaId);
    return 'PhotoBook_$mediaId.jpg';
  }

  @override
  Future<DeleteMediaSelectionResult> deleteMediaSelection(
    String postId,
    List<String> mediaIds,
  ) async {
    deletedMediaIds.addAll(mediaIds);
    return DeleteMediaSelectionResult(
      postId: postId,
      postDeleted: deleteSelectionPostDeleted,
    );
  }

  @override
  Future<ClipboardImportOutcome> importClipboard({
    required bool automatic,
  }) async {
    clipboardAutomaticCalls.add(automatic);
    return ClipboardImportOutcome.unsupported;
  }

  void emitJobChanged() {
    _events.add(
      const ArchiveRuntimeEvent(
        type: ArchiveRuntimeEventType.jobChanged,
        timestamp: 1,
      ),
    );
  }

  Future<void> close() => _events.close();
}

ArchivedPost _postWithMedia() => const ArchivedPost(
  id: 'post-1',
  sourceUrl: 'https://www.instagram.com/p/Post1/',
  authorUsername: 'archive_user',
  authorDisplayName: 'Archive User',
  caption: '',
  publishedAt: 1,
  coverMediaId: 'media-0',
  mediaCount: 2,
  isBackedUp: true,
  media: [
    PostMedia(
      id: 'media-0',
      mediaType: PostMediaType.image,
      width: 1080,
      height: 1350,
    ),
    PostMedia(
      id: 'media-1',
      mediaType: PostMediaType.image,
      width: 1080,
      height: 1350,
    ),
  ],
);
