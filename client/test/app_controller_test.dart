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

  test('原生运行期间下拉刷新不会重复启动抓取服务', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    await controller.initialize();
    controller.tasks = const [
      ArchiveJob(
        id: 'active',
        sourcePostId: 'ACTIVE',
        status: ArchiveJobStatus.fetching,
      ),
    ];

    runtime.emitRunStarted();
    await controller.refreshArchive();
    expect(runtime.resumeCaptureCalls, 0);

    runtime.emitRunFinished();
    await controller.refreshArchive();
    expect(runtime.resumeCaptureCalls, 1);

    controller.dispose();
    await runtime.close();
  });

  test('多个原生执行重叠时保留互斥直到全部结束', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    await controller.initialize();
    controller.tasks = const [
      ArchiveJob(
        id: 'active',
        sourcePostId: 'ACTIVE',
        status: ArchiveJobStatus.fetching,
      ),
    ];

    runtime.emitRunStarted();
    runtime.emitRunStarted();
    runtime.emitRunFinished();
    await controller.refreshArchive();
    expect(runtime.resumeCaptureCalls, 0);

    runtime.emitRunFinished();
    await controller.refreshArchive();
    expect(runtime.resumeCaptureCalls, 1);

    controller.dispose();
    await runtime.close();
  });

  test('原生启动请求返回到运行事件送达前不重复启动', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    await controller.initialize();
    controller.tasks = const [
      ArchiveJob(
        id: 'active',
        sourcePostId: 'ACTIVE',
        status: ArchiveJobStatus.fetching,
      ),
    ];

    await controller.refreshArchive();
    await controller.refreshArchive();

    expect(runtime.resumeCaptureCalls, 1);
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
    expect(runtime.resumeBackupCalls, 1);
    expect(controller.message, isNull);

    await controller.setForeground(false);
    await controller.setForeground(true);

    expect(runtime.clipboardAutomaticCalls, [true, true]);
    expect(runtime.resumeBackupCalls, 2);
    controller.dispose();
    await runtime.close();
  });

  test('初始化取得多个 R2 位置后不重复读取帖子或触发自动备份', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge()
      ..r2Settings = const R2SettingsSummary(
        connections: [
          R2ConnectionSummary(
            connectionId: 'connection-a',
            endpoint: 'https://example.r2.cloudflarestorage.com',
            bucket: 'photobook-test',
            accessKeyIdHint: 'abc…xyz',
            targetCount: 1,
          ),
        ],
        targets: [
          R2BackupTargetSummary(
            targetId: 'target-a',
            connectionId: 'connection-a',
            name: '默认备份',
            endpoint: 'https://example.r2.cloudflarestorage.com',
            bucket: 'photobook-test',
            prefix: 'photobook',
          ),
        ],
      );
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );

    await controller.initialize();

    expect(database.postReadCount, 1);
    expect(controller.r2Settings.targets.single.targetId, 'target-a');
    expect(runtime.resumeCaptureCalls, 0);
    controller.dispose();
    await runtime.close();
  });

  test('新增 R2 连接只更新配置且不创建帖子任务', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );
    const input = R2ConnectionInput(
      endpoint: 'https://example.r2.cloudflarestorage.com',
      bucket: 'photobook-test',
      targetName: '默认备份',
      prefix: 'photobook',
      accessKeyId: 'access-key',
      secretAccessKey: 'secret-key',
    );

    await expectLater(controller.saveR2Connection(input), completes);

    expect(runtime.savedR2Connection, input);
    expect(controller.r2Settings.targets.single.targetId, 'target-a');
    expect(database.postReadCount, 0);
    expect(runtime.enqueuedBackups, isEmpty);
    controller.dispose();
    await runtime.close();
  });

  test('详情页手动备份只为选中的位置入队并刷新本地状态', () async {
    final database = _ImmediateDatabase();
    final runtime = _FakeRuntimeBridge();
    final controller = AppController(
      database: database,
      runtimeBridge: runtime,
      isAndroid: true,
    );

    final status = await controller.enqueueR2Backup('post-1', 'target-b');

    expect(status, ManualBackupEnqueueStatus.queued);
    expect(runtime.enqueuedBackups, [('post-1', 'target-b')]);
    expect(database.postReadCount, 1);
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
    expect(controller.posts.single.backupState, PostBackupState.completed);
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

  @override
  Future<void> initialize() async {}

  @override
  Future<List<ArchivedPost>> listPosts() async {
    postReadCount += 1;
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
  Future<List<ArchivedPost>> listPosts() async {
    postReadCount += 1;
    throw StateError('测试数据库读取失败');
  }
}

class _DelayedDatabase extends AppDatabase {
  final Completer<void> _firstTaskRead = Completer<void>();
  final Completer<void> firstTaskReadStarted = Completer<void>();
  int postReadCount = 0;
  int taskReadCount = 0;

  void releaseFirstTaskRead() => _firstTaskRead.complete();

  @override
  Future<void> initialize() async {}

  @override
  Future<List<ArchivedPost>> listPosts() async {
    postReadCount += 1;
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
  final List<(String, String)> enqueuedBackups = [];
  bool deleteSelectionPostDeleted = false;
  int resumeCaptureCalls = 0;
  int resumeBackupCalls = 0;
  R2SettingsSummary r2Settings = const R2SettingsSummary();
  R2ConnectionInput? savedR2Connection;

  @override
  Stream<ArchiveRuntimeEvent> get events => _events.stream;

  @override
  Future<ArchiveRuntimeState> getRuntimeState() async => ArchiveRuntimeState(
    activeJobCount: 0,
    failedJobCount: 0,
    r2Settings: r2Settings,
  );

  @override
  Future<void> resumeCaptureJobs() async {
    resumeCaptureCalls += 1;
  }

  @override
  Future<void> resumeBackupJobs() async {
    resumeBackupCalls += 1;
  }

  @override
  Future<R2SettingsSummary> saveR2Connection(R2ConnectionInput input) async {
    savedR2Connection = input;
    return r2Settings = const R2SettingsSummary(
      connections: [
        R2ConnectionSummary(
          connectionId: 'connection-a',
          endpoint: 'https://example.r2.cloudflarestorage.com',
          bucket: 'photobook-test',
          accessKeyIdHint: 'ac****ey',
          targetCount: 1,
        ),
      ],
      targets: [
        R2BackupTargetSummary(
          targetId: 'target-a',
          connectionId: 'connection-a',
          name: '默认备份',
          endpoint: 'https://example.r2.cloudflarestorage.com',
          bucket: 'photobook-test',
          prefix: 'photobook',
        ),
      ],
    );
  }

  @override
  Future<ManualBackupEnqueueStatus> enqueueR2Backup(
    String postId,
    String targetId,
  ) async {
    enqueuedBackups.add((postId, targetId));
    return ManualBackupEnqueueStatus.queued;
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

  void emitRunStarted() {
    _events.add(
      const ArchiveRuntimeEvent(
        type: ArchiveRuntimeEventType.runStarted,
        timestamp: 1,
      ),
    );
  }

  void emitRunFinished() {
    _events.add(
      const ArchiveRuntimeEvent(
        type: ArchiveRuntimeEventType.runFinished,
        timestamp: 2,
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
  backupState: PostBackupState.completed,
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
