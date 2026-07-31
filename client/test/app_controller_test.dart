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
}

class _ImmediateDatabase extends AppDatabase {
  @override
  Future<void> initialize() async {}

  @override
  Future<List<ArchivedPost>> listPosts() async => const [];

  @override
  Future<List<ArchiveJob>> listVisibleJobs() async => const [];

  @override
  Future<SyncStatus> readSyncStatus() async => const SyncStatus();

  @override
  Future<void> close() async {}
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
  Future<SyncStatus> readSyncStatus() async => const SyncStatus();

  @override
  Future<void> close() async {}
}

class _FakeRuntimeBridge extends ArchiveRuntimeBridge {
  final StreamController<ArchiveRuntimeEvent> _events =
      StreamController<ArchiveRuntimeEvent>.broadcast(sync: true);
  final List<bool> clipboardAutomaticCalls = [];

  @override
  Stream<ArchiveRuntimeEvent> get events => _events.stream;

  @override
  Future<ArchiveRuntimeState> getRuntimeState() async =>
      const ArchiveRuntimeState(activeJobCount: 0, failedJobCount: 0);

  @override
  Future<void> syncNow() async {}

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
