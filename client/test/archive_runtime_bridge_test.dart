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

  test('删除、分享和保存方法使用稳定的原生通道参数', () async {
    const methodChannel = MethodChannel('photobook-test/archive');
    const eventChannel = EventChannel('photobook-test/archive-events');
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          calls.add(call);
          return switch (call.method) {
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

    await bridge.deletePost('post-1');
    final deleted = await bridge.deleteMedia('media-1');
    await bridge.shareMedia(['media-1', 'media-2']);
    final savedName = await bridge.saveMedia('media-1');

    expect(deleted.postId, 'post-1');
    expect(deleted.postDeleteRequired, isFalse);
    expect(savedName, 'PhotoBook_post-1_1.jpg');
    expect(calls.map((call) => call.method), [
      'deletePost',
      'deleteMedia',
      'shareMedia',
      'saveMedia',
    ]);
    expect(calls[0].arguments, {'postId': 'post-1'});
    expect(calls[1].arguments, {'mediaId': 'media-1'});
    expect(calls[2].arguments, {
      'mediaIds': ['media-1', 'media-2'],
    });
    expect(calls[3].arguments, {'mediaId': 'media-1'});
  });
}
