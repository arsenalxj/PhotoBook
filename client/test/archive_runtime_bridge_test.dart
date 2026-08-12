import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('解析原生运行完成事件和备份错误', () {
    final event = ArchiveRuntimeEvent.fromMap({
      'type': 'runFinished',
      'timestamp': 1750000000000,
      'error': 'R2 备份失败',
    });

    expect(event.type, ArchiveRuntimeEventType.runFinished);
    expect(event.error, 'R2 备份失败');
  });

  test('解析任务失效通知且不依赖任务快照', () {
    final event = ArchiveRuntimeEvent.fromMap({
      'type': 'jobChanged',
      'timestamp': 1750000000000,
    });

    expect(event.type, ArchiveRuntimeEventType.jobChanged);
    expect(event.error, isNull);
  });

  test('解析 Instagram Session 非敏感摘要', () {
    final runtime = ArchiveRuntimeState.fromMap({
      'activeJobCount': 1,
      'failedJobCount': 2,
      'instagramSession': {
        'status': 'needs_refresh',
        'username': 'archive_user',
        'validatedAt': 1750000000000,
      },
      'r2Settings': {'connections': <Object>[], 'targets': <Object>[]},
    });

    expect(
      runtime.instagramSession?.status,
      InstagramSessionStatus.needsRefresh,
    );
    expect(runtime.instagramSession?.username, 'archive_user');
    expect(runtime.instagramSession?.validatedAt, 1750000000000);
  });

  test('解析多个 R2 连接与备份位置摘要', () {
    final runtime = ArchiveRuntimeState.fromMap({
      'activeJobCount': 0,
      'failedJobCount': 0,
      'instagramSession': null,
      'r2Settings': {
        'connections': [
          {
            'connectionId': 'connection-a',
            'endpoint': 'https://example.r2.cloudflarestorage.com',
            'bucket': 'photobook-test',
            'accessKeyIdHint': 'abc…xyz',
            'targetCount': 2,
          },
        ],
        'targets': [
          {
            'targetId': 'target-a',
            'connectionId': 'connection-a',
            'name': '默认备份',
            'endpoint': 'https://example.r2.cloudflarestorage.com',
            'bucket': 'photobook-test',
            'prefix': 'photobook',
          },
        ],
      },
    });

    expect(runtime.r2Settings.connections.single.targetCount, 2);
    expect(runtime.r2Settings.targets.single.targetId, 'target-a');
  });

  test('删除、分享和保存方法使用稳定的原生通道参数', () async {
    const methodChannel = MethodChannel('photobook-test/archive');
    const eventChannel = EventChannel('photobook-test/archive-events');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'importClipboard' => 'queued',
            'captureInstagramSession' => <String, Object>{
              'status': 'ready',
              'username': 'archive_user',
              'validatedAt': 1750000000000,
            },
            'importInstagramCookies' => <String, Object>{
              'status': 'ready',
              'username': 'manual_user',
              'validatedAt': 1750000000001,
            },
            'deleteMediaSelection' => <String, Object>{
              'postId': 'post-1',
              'postDeleted': false,
            },
            'saveMedia' => 'PhotoBook_post-1_1.jpg',
            _ => null,
          };
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null),
    );
    final bridge = ArchiveRuntimeBridge(
      methodChannel: methodChannel,
      eventChannel: eventChannel,
    );

    final clipboardOutcome = await bridge.importClipboard(automatic: true);
    await bridge.beginInstagramLogin();
    final session = await bridge.captureInstagramSession();
    await bridge.cancelInstagramLogin();
    final importedSession = await bridge.importInstagramCookies(
      'sessionid=session-secret; csrftoken=csrf-value',
    );
    await bridge.copyInstagramCookies();
    await bridge.clearInstagramSession();
    await bridge.resumeCaptureJobs();
    await bridge.copyJobSourceUrl('job-active');
    await bridge.cancelJob('job-active');
    await bridge.retryJob('job-failed');
    await bridge.deleteJob('job-cancelled');
    await bridge.deletePost('post-1');
    final deleted = await bridge.deleteMediaSelection('post-1', [
      'media-1',
      'media-2',
    ]);
    await bridge.shareMedia(['media-1', 'media-2']);
    final savedName = await bridge.saveMedia('media-1');

    expect(deleted.postId, 'post-1');
    expect(session.username, 'archive_user');
    expect(importedSession.username, 'manual_user');
    expect(session.status, InstagramSessionStatus.ready);
    expect(deleted.postDeleted, isFalse);
    expect(savedName, 'PhotoBook_post-1_1.jpg');
    expect(clipboardOutcome, ClipboardImportOutcome.queued);
    expect(calls.map((call) => call.method), [
      'importClipboard',
      'beginInstagramLogin',
      'captureInstagramSession',
      'cancelInstagramLogin',
      'importInstagramCookies',
      'copyInstagramCookies',
      'clearInstagramSession',
      'resumeCaptureJobs',
      'copyJobSourceUrl',
      'cancelJob',
      'retryJob',
      'deleteJob',
      'deletePost',
      'deleteMediaSelection',
      'shareMedia',
      'saveMedia',
    ]);
    expect(calls[0].arguments, {'automatic': true});
    expect(calls[4].arguments, {
      'cookieHeader': 'sessionid=session-secret; csrftoken=csrf-value',
    });
    expect(calls[8].arguments, {'jobId': 'job-active'});
    expect(calls[9].arguments, {'jobId': 'job-active'});
    expect(calls[10].arguments, {'jobId': 'job-failed'});
    expect(calls[11].arguments, {'jobId': 'job-cancelled'});
    expect(calls[12].arguments, {'postId': 'post-1'});
    expect(calls[13].arguments, {
      'postId': 'post-1',
      'mediaIds': ['media-1', 'media-2'],
    });
    expect(calls[14].arguments, {
      'mediaIds': ['media-1', 'media-2'],
      'exportMode': 'original',
    });
    expect(calls[15].arguments, {
      'mediaId': 'media-1',
      'exportMode': 'original',
    });
  });

  test('R2 多连接和手动备份方法使用稳定的原生通道参数', () async {
    const methodChannel = MethodChannel('photobook-test/r2');
    const eventChannel = EventChannel('photobook-test/r2-events');
    final calls = <MethodCall>[];
    final settings = <String, Object>{
      'connections': <Object>[],
      'targets': <Object>[],
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          calls.add(call);
          if (call.method == 'enqueueR2Backup') return 'queued';
          return settings;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null),
    );
    final bridge = ArchiveRuntimeBridge(
      methodChannel: methodChannel,
      eventChannel: eventChannel,
    );

    await bridge.saveR2Connection(
      const R2ConnectionInput(
        endpoint: 'https://example.r2.cloudflarestorage.com',
        bucket: 'photobook-test',
        targetName: '默认备份',
        prefix: 'photobook',
        accessKeyId: 'access-key',
        secretAccessKey: 'secret-key',
      ),
    );
    await bridge.saveR2Target(
      const R2TargetInput(
        connectionId: 'connection-a',
        name: '旅行收藏',
        prefix: 'travel',
      ),
    );
    final status = await bridge.enqueueR2Backup('post-1', 'target-a');

    expect(status, ManualBackupEnqueueStatus.queued);
    expect(calls[0].method, 'saveR2Connection');
    expect(calls[0].arguments, {
      'endpoint': 'https://example.r2.cloudflarestorage.com',
      'bucket': 'photobook-test',
      'targetName': '默认备份',
      'prefix': 'photobook',
      'accessKeyId': 'access-key',
      'secretAccessKey': 'secret-key',
    });
    expect(calls[1].method, 'saveR2Target');
    expect(calls[1].arguments, {
      'connectionId': 'connection-a',
      'name': '旅行收藏',
      'prefix': 'travel',
    });
    expect(calls[2].arguments, {'postId': 'post-1', 'targetId': 'target-a'});
  });
}
