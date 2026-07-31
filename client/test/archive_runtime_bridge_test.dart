import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('解析原生运行完成事件和同步错误', () {
    final event = ArchiveRuntimeEvent.fromMap({
      'type': 'runFinished',
      'timestamp': 1750000000000,
      'error': 'R2 读取失败',
    });

    expect(event.type, ArchiveRuntimeEventType.runFinished);
    expect(event.error, 'R2 读取失败');
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
      'r2Config': null,
    });

    expect(
      runtime.instagramSession?.status,
      InstagramSessionStatus.needsRefresh,
    );
    expect(runtime.instagramSession?.username, 'archive_user');
    expect(runtime.instagramSession?.validatedAt, 1750000000000);
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
            'deleteMedia' => <String, Object>{
              'postId': 'post-1',
              'postDeleteRequired': false,
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
    await bridge.clearInstagramSession();
    await bridge.cancelJob('job-active');
    await bridge.retryJob('job-failed');
    await bridge.deleteJob('job-cancelled');
    await bridge.deletePost('post-1');
    final deleted = await bridge.deleteMedia('media-1');
    await bridge.shareMedia(['media-1', 'media-2']);
    final savedName = await bridge.saveMedia('media-1');

    expect(deleted.postId, 'post-1');
    expect(session.username, 'archive_user');
    expect(session.status, InstagramSessionStatus.ready);
    expect(deleted.postDeleteRequired, isFalse);
    expect(savedName, 'PhotoBook_post-1_1.jpg');
    expect(clipboardOutcome, ClipboardImportOutcome.queued);
    expect(calls.map((call) => call.method), [
      'importClipboard',
      'beginInstagramLogin',
      'captureInstagramSession',
      'cancelInstagramLogin',
      'clearInstagramSession',
      'cancelJob',
      'retryJob',
      'deleteJob',
      'deletePost',
      'deleteMedia',
      'shareMedia',
      'saveMedia',
    ]);
    expect(calls[0].arguments, {'automatic': true});
    expect(calls[5].arguments, {'jobId': 'job-active'});
    expect(calls[6].arguments, {'jobId': 'job-failed'});
    expect(calls[7].arguments, {'jobId': 'job-cancelled'});
    expect(calls[8].arguments, {'postId': 'post-1'});
    expect(calls[9].arguments, {'mediaId': 'media-1'});
    expect(calls[10].arguments, {
      'mediaIds': ['media-1', 'media-2'],
      'exportMode': 'original',
    });
    expect(calls[11].arguments, {
      'mediaId': 'media-1',
      'exportMode': 'original',
    });
  });
}
