import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/models/post.dart';
import 'package:photobook/screens/detail_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:photobook/widgets/post_action_sheets.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:video_player_platform_interface/video_player_platform_interface.dart';

void main() {
  late Directory tempDirectory;

  setUp(() {
    tempDirectory = Directory.systemTemp.createTempSync(
      'photobook_detail_test_',
    );
  });

  tearDown(() {
    tempDirectory.deleteSync(recursive: true);
  });

  testWidgets('详情页返回按钮右侧在两个平台都显示作者展示名', (tester) async {
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        ArchivedPost(
          id: 'post-bella',
          sourceUrl: 'https://www.instagram.com/p/test/',
          authorUsername: 'zhouyanxi0909',
          authorDisplayName: 'Bella周老师',
          caption: '',
          publishedAt: 1,
          coverMediaId: 'media-1',
          mediaCount: 1,
          media: const [
            PostMedia(
              id: 'media-1',
              mediaType: PostMediaType.image,
              width: 1080,
              height: 1350,
            ),
          ],
        ),
        ArchivedPost(
          id: 'post-xhs',
          sourcePlatform: PostSourcePlatform.xiaohongshu,
          sourceUrl: 'https://www.xiaohongshu.com/explore/test',
          authorUsername: 'xhs-user-id',
          authorDisplayName: '小红书作者',
          caption: '',
          publishedAt: 1,
          coverMediaId: 'media-2',
          mediaCount: 1,
          media: const [
            PostMedia(
              id: 'media-2',
              mediaType: PostMediaType.image,
              width: 1080,
              height: 1350,
            ),
          ],
        ),
      ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-bella'),
        ),
      ),
    );

    final appBar = find.byType(AppBar);
    expect(
      find.descendant(of: appBar, matching: find.text('Bella周老师')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: appBar, matching: find.text('@zhouyanxi0909')),
      findsNothing,
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-xhs'),
        ),
      ),
    );

    expect(
      find.descendant(of: appBar, matching: find.text('小红书作者')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: appBar, matching: find.text('xhs-user-id')),
      findsNothing,
    );
  });

  testWidgets('本地原图解码期间保留缩略图占位', (tester) async {
    final imageBytes = base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
      '+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    );
    final thumbnail = File('${tempDirectory.path}/thumbnail.png')
      ..writeAsBytesSync(imageBytes);
    final original = File('${tempDirectory.path}/original.png')
      ..writeAsBytesSync(imageBytes);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        ArchivedPost(
          id: 'post-1',
          sourceUrl: 'https://www.instagram.com/p/test/',
          authorUsername: 'tester',
          authorDisplayName: 'Tester',
          caption: '',
          publishedAt: 1,
          coverMediaId: 'media-1',
          mediaCount: 1,
          media: [
            PostMedia(
              id: 'media-1',
              mediaType: PostMediaType.image,
              width: 1080,
              height: 1350,
              localThumbnailPath: thumbnail.path,
              localOriginalPath: original.path,
            ),
          ],
        ),
      ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-1'),
        ),
      ),
    );

    final images = tester.widgetList<Image>(find.byType(Image));
    expect(images, hasLength(2));
    expect(images.every((image) => image.fit == BoxFit.contain), isTrue);
  });

  testWidgets('单个横向媒体按屏幕宽度等比确定高度', (tester) async {
    tester.view.physicalSize = const Size(600, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [(width: 600, height: 300)]),
      ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-1'),
        ),
      ),
    );

    expect(
      tester.getSize(find.byKey(const ValueKey('media-viewer'))),
      const Size(600, 300),
    );
  });

  testWidgets('混合尺寸媒体取最大显示高度且不超过屏幕三分之二', (tester) async {
    tester.view.physicalSize = const Size(600, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final imageBytes = base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
      '+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    );
    final first = File('${tempDirectory.path}/first.png')
      ..writeAsBytesSync(imageBytes);
    final second = File('${tempDirectory.path}/second.png')
      ..writeAsBytesSync(imageBytes);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(
          const [(width: 600, height: 300), (width: 300, height: 600)],
          localOriginalPaths: [first.path, second.path],
        ),
      ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-1'),
        ),
      ),
    );

    expect(
      tester.getSize(find.byKey(const ValueKey('media-viewer'))),
      const Size(600, 600),
    );
    expect(find.text('1/2'), findsOneWidget);
    expect(
      tester
          .widgetList<Image>(find.byType(Image))
          .every((image) => image.fit == BoxFit.contain),
      isTrue,
    );

    await tester.drag(find.byType(PageView), const Offset(-500, 0));
    await tester.pumpAndSettle();

    expect(find.text('2/2'), findsOneWidget);
    expect(
      tester.getSize(find.byKey(const ValueKey('media-viewer'))),
      const Size(600, 600),
    );
    expect(
      tester
          .widgetList<Image>(find.byType(Image))
          .every((image) => image.fit == BoxFit.contain),
      isTrue,
    );
  });

  testWidgets('分享默认选中当前媒体，图片可多选且视频保持单选', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(
          const [
            (width: 1080, height: 1350),
            (width: 1080, height: 1350),
            (width: 1080, height: 1920),
          ],
          mediaTypes: const [
            PostMediaType.image,
            PostMediaType.image,
            PostMediaType.video,
          ],
        ),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('share-post-media')));
    await tester.pumpAndSettle();

    expect(find.byIcon(LucideIcons.circleCheck), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('share-media-1')));
    await tester.pump();
    expect(find.text('分享 2 项'), findsOneWidget);
    expect(find.byIcon(LucideIcons.circleCheck), findsNWidgets(2));

    await tester.tap(find.byKey(const ValueKey('share-media-2')));
    await tester.pump();
    expect(find.text('分享 1 项'), findsOneWidget);
    expect(find.byIcon(LucideIcons.circleCheck), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('share-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.sharedMediaIds, ['media-2']);
  });

  testWidgets('降级 Live Photo 分享直接使用静态图', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [_livePost(hasMotion: false)];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('share-post-media')));
    await tester.pumpAndSettle();
    expect(find.text('GIF'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('share-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.sharedMediaIds, ['media-live-still']);
    expect(controller.sharedExportMode, MediaExportMode.staticImage);
    expect(find.text('GIF'), findsNothing);
  });

  testWidgets('完整 Live Photo 分享提供静态图 GIF 视频三种选项', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [_livePost(hasMotion: true)];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('share-post-media')));
    await tester.pumpAndSettle();

    expect(find.text('静态图'), findsOneWidget);
    expect(find.text('GIF'), findsOneWidget);
    expect(find.text('视频'), findsOneWidget);
    await tester.tap(find.text('GIF'));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('share-selected-media')));
    await tester.pumpAndSettle();
    expect(controller.sharedExportMode, MediaExportMode.gif);
  });

  testWidgets('完整 Live Photo 按住播放动态且松手恢复静态图', (tester) async {
    final originalPlatform = VideoPlayerPlatform.instance;
    final videoPlatform = _FakeVideoPlayerPlatform();
    VideoPlayerPlatform.instance = videoPlatform;
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await videoPlatform.close();
      VideoPlayerPlatform.instance = originalPlatform;
    });
    final still = _writeImage(tempDirectory, 'live-still.png');
    final motion = File('${tempDirectory.path}/live-motion.mp4')
      ..writeAsBytesSync([]);
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _livePost(
          hasMotion: true,
          stillPath: still.path,
          motionPath: motion.path,
        ),
      ];
    await _pumpDetail(tester, controller);

    final gesture = await tester.startGesture(
      tester.getCenter(find.byKey(const ValueKey('live-media-live-still'))),
    );
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump();

    expect(
      find.byKey(const ValueKey('live-motion-media-live-motion')),
      findsOneWidget,
    );
    await gesture.up();
    await tester.pump();
    expect(
      find.byKey(const ValueKey('live-motion-media-live-motion')),
      findsNothing,
    );
    expect(find.byKey(const ValueKey('live-media-live-still')), findsOneWidget);
  });

  testWidgets('删除面板默认全选，部分删除后定位相邻项并更新分享默认项', (tester) async {
    final originals = [
      for (var index = 0; index < 3; index += 1)
        _writeImage(tempDirectory, 'delete-$index.png').path,
    ];
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [
          (width: 1080, height: 1350),
          (width: 1080, height: 1350),
          (width: 1080, height: 1350),
        ], localOriginalPaths: originals),
      ];
    await _pumpDetail(tester, controller);

    await tester.drag(find.byType(PageView), const Offset(-500, 0));
    await tester.pumpAndSettle();
    expect(find.text('2/3'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('delete-post-media')));
    await tester.pumpAndSettle();
    expect(find.text('3/3'), findsOneWidget);
    expect(find.text('删除帖子'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('delete-media-0')));
    await tester.tap(find.byKey(const ValueKey('delete-media-2')));
    await tester.pump();
    expect(find.text('1/3'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('delete-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.deletedMediaIds, ['media-1']);
    expect(find.text('2/2'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('share-post-media')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('share-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.sharedMediaIds, ['media-2']);
  });

  testWidgets('保存面板默认全选并允许取消选择', (tester) async {
    final originals = [
      _writeImage(tempDirectory, 'save-0.png').path,
      _writeImage(tempDirectory, 'save-1.png').path,
    ];
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [
          (width: 1080, height: 1350),
          (width: 1080, height: 1350),
        ], localOriginalPaths: originals),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('save-post-media')));
    await tester.pumpAndSettle();
    expect(find.text('2/2'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('save-media-1')));
    await tester.pump();
    expect(
      find.descendant(of: find.byType(BottomSheet), matching: find.text('1/2')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('save-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.savedMediaIds, ['media-0']);
    expect(controller.mediaSaveRefreshCount, 1);
    expect(find.text('已保存 1 项到系统相册'), findsOneWidget);
  });

  testWidgets('删除面板保持全选时直接删除整帖', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [
          (width: 1080, height: 1350),
          (width: 1080, height: 1350),
        ]),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('delete-post-media')));
    await tester.pumpAndSettle();
    expect(find.text('2/2'), findsOneWidget);
    expect(find.text('删除帖子'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('delete-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.deletedMediaIds, ['media-0', 'media-1']);
    expect(find.text('帖子不存在'), findsOneWidget);
  });

  testWidgets('Live Photo 保存支持 GIF 且进度持续显示到保存完成', (tester) async {
    final pendingSave = Completer<void>();
    addTearDown(() {
      if (!pendingSave.isCompleted) pendingSave.complete();
    });
    final still = _writeImage(tempDirectory, 'save-live-still.png');
    final motion = File('${tempDirectory.path}/save-live-motion.mp4')
      ..writeAsBytesSync([]);
    final controller = _FakeAppController()
      ..saveCompleter = pendingSave
      ..phase = AppPhase.ready
      ..posts = [
        _livePost(
          hasMotion: true,
          stillPath: still.path,
          motionPath: motion.path,
        ),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('save-post-media')));
    await tester.pumpAndSettle();
    expect(find.text('静态图'), findsOneWidget);
    expect(find.text('GIF'), findsOneWidget);
    expect(find.text('视频'), findsOneWidget);
    await tester.tap(find.text('GIF'));
    await tester.tap(find.byKey(const ValueKey('save-selected-media')));
    await tester.pump();

    expect(find.text('正在保存 0/1'), findsOneWidget);
    await tester.pump(const Duration(seconds: 31));
    expect(find.text('正在保存 0/1'), findsOneWidget);

    pendingSave.complete();
    await tester.pumpAndSettle();
    expect(controller.savedExportModes, [MediaExportMode.gif]);
  });

  testWidgets('保存部分失败时只保留失败项并允许重试', (tester) async {
    final controller = _FakeAppController()
      ..saveFailures.add('media-1')
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [
          (width: 1080, height: 1350),
          (width: 1080, height: 1350),
        ]),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('save-post-media')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('save-selected-media')));
    await tester.pumpAndSettle();

    expect(find.textContaining('1 项失败'), findsOneWidget);
    expect(find.text('保存 1 项'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('save-selected-media')));
    await tester.pumpAndSettle();

    expect(controller.savedMediaIds, ['media-0', 'media-1', 'media-1']);
    expect(controller.mediaSaveRefreshCount, 1);
    expect(find.text('已保存 2 项到系统相册'), findsOneWidget);
  });

  testWidgets('删除执行期间返回、遮罩和下拉都不能关闭选择面板', (tester) async {
    final pendingDelete = Completer<void>();
    addTearDown(() {
      if (!pendingDelete.isCompleted) pendingDelete.complete();
    });
    final controller = _FakeAppController()
      ..deleteSelectionCompleter = pendingDelete
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [(width: 1080, height: 1350)]),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('delete-post-media')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('delete-selected-media')));
    await tester.pump();
    expect(find.text('正在删除'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tapAt(const Offset(8, 8));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.drag(find.byType(BottomSheet), const Offset(0, 400));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('正在删除'), findsOneWidget);
    pendingDelete.complete();
    await tester.pumpAndSettle();
    expect(controller.deletedMediaIds, ['media-0']);
  });

  testWidgets('分享准备期间返回、遮罩和下拉都不能关闭抽屉', (tester) async {
    final pendingShare = Completer<void>();
    addTearDown(() {
      if (!pendingShare.isCompleted) pendingShare.complete();
    });
    final controller = _FakeAppController()
      ..shareCompleter = pendingShare
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(const [(width: 1080, height: 1350)]),
      ];
    await _pumpDetail(tester, controller);

    await tester.tap(find.byKey(const ValueKey('share-post-media')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('share-selected-media')));
    await tester.pump();
    expect(find.text('正在准备媒体'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tapAt(const Offset(8, 8));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.drag(find.byType(BottomSheet), const Offset(0, 400));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('正在准备媒体'), findsOneWidget);
    expect(
      tester
          .widget<IconButton>(find.byKey(const ValueKey('close-share-sheet')))
          .onPressed,
      isNull,
    );
    pendingShare.complete();
    await tester.pumpAndSettle();
    expect(controller.sharedMediaIds, ['media-0']);
  });

  testWidgets('分享抽屉在矮屏和大字体下可滚动到分享按钮', (tester) async {
    tester.view.physicalSize = const Size(360, 480);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final post = _postWithMedia(
      List.generate(12, (_) => (width: 1080, height: 1350)),
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.light,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(1.8)),
          child: child!,
        ),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              key: const ValueKey('open-share-sheet'),
              onPressed: () => showShareMediaSheet(
                context: context,
                post: post,
                initialMediaId: post.media.first.id,
                onShare: (_, _) async {},
              ),
              child: const Text('打开分享'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.byKey(const ValueKey('open-share-sheet')));
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    await tester.drag(
      find.byType(SingleChildScrollView),
      const Offset(0, -500),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('share-selected-media')).hitTestable(),
      findsOneWidget,
    );
  });

  testWidgets('多视频轮播只播放当前页', (tester) async {
    final originalPlatform = VideoPlayerPlatform.instance;
    final videoPlatform = _FakeVideoPlayerPlatform();
    VideoPlayerPlatform.instance = videoPlatform;
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await videoPlatform.close();
      VideoPlayerPlatform.instance = originalPlatform;
    });
    final first = File('${tempDirectory.path}/first.mp4')..writeAsBytesSync([]);
    final second = File('${tempDirectory.path}/second.mp4')
      ..writeAsBytesSync([]);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(
          const [(width: 1080, height: 1350), (width: 1080, height: 1350)],
          mediaType: PostMediaType.video,
          localOriginalPaths: [first.path, second.path],
        ),
      ];

    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-1'),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 10));

    expect(videoPlatform.activePlayers, hasLength(1));
    final firstPlayer = videoPlatform.activePlayers.single;
    final pageView = find.byType(PageView);
    await tester.drag(pageView, const Offset(-500, 0));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(milliseconds: 10));

    expect(find.text('2/2'), findsOneWidget);
    expect(videoPlatform.createdPlayerCount, 2);
    expect(videoPlatform.activePlayers, hasLength(1));
    expect(videoPlatform.activePlayers.single, isNot(firstPlayer));
    expect(videoPlatform.maxActivePlayerCount, 1);

    await tester.longPress(find.byKey(ValueKey('video-${second.path}')));
    await tester.pumpAndSettle();
    expect(find.text('保存到系统相册'), findsNothing);
    expect(find.byKey(const ValueKey('save-post-media')), findsOneWidget);
    expect(find.byKey(const ValueKey('delete-post-media')), findsOneWidget);
  });

  testWidgets('视频初始化期间长按不再打开媒体操作', (tester) async {
    final originalPlatform = VideoPlayerPlatform.instance;
    final videoPlatform = _FakeVideoPlayerPlatform(emitInitialized: false);
    VideoPlayerPlatform.instance = videoPlatform;
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await videoPlatform.close();
      VideoPlayerPlatform.instance = originalPlatform;
    });
    final video = File('${tempDirectory.path}/loading.mp4')
      ..writeAsBytesSync([]);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(
          const [(width: 1080, height: 1350)],
          mediaType: PostMediaType.video,
          localOriginalPaths: [video.path],
        ),
      ];
    await _pumpDetail(tester, controller);
    await tester.pump();

    await tester.longPress(find.byKey(ValueKey('video-${video.path}')));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('保存到系统相册'), findsNothing);
  });

  testWidgets('视频初始化失败后长按不再打开媒体操作', (tester) async {
    final originalPlatform = VideoPlayerPlatform.instance;
    final videoPlatform = _FakeVideoPlayerPlatform(creationFails: true);
    VideoPlayerPlatform.instance = videoPlatform;
    addTearDown(() async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await videoPlatform.close();
      VideoPlayerPlatform.instance = originalPlatform;
    });
    final video = File('${tempDirectory.path}/failed.mp4')
      ..writeAsBytesSync([]);
    final controller = AppController()
      ..phase = AppPhase.ready
      ..posts = [
        _postWithMedia(
          const [(width: 1080, height: 1350)],
          mediaType: PostMediaType.video,
          localOriginalPaths: [video.path],
        ),
      ];
    await _pumpDetail(tester, controller);
    await tester.pump();
    await tester.pump();

    await tester.longPress(find.byKey(ValueKey('video-${video.path}')));
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('保存到系统相册'), findsNothing);
  });
}

Future<void> _pumpDetail(WidgetTester tester, AppController controller) =>
    tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const DetailScreen(postId: 'post-1'),
        ),
      ),
    );

File _writeImage(Directory directory, String name) {
  final imageBytes = base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
    '+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  );
  return File('${directory.path}/$name')..writeAsBytesSync(imageBytes);
}

ArchivedPost _postWithMedia(
  List<({int width, int height})> sizes, {
  PostMediaType mediaType = PostMediaType.image,
  List<PostMediaType>? mediaTypes,
  List<String?>? localOriginalPaths,
}) {
  final media = [
    for (var index = 0; index < sizes.length; index += 1)
      PostMedia(
        id: 'media-$index',
        mediaType: mediaTypes?[index] ?? mediaType,
        width: sizes[index].width,
        height: sizes[index].height,
        localOriginalPath: localOriginalPaths?[index],
      ),
  ];
  return ArchivedPost(
    id: 'post-1',
    sourceUrl: 'https://www.instagram.com/p/test/',
    authorUsername: 'tester',
    authorDisplayName: 'Tester',
    caption: '',
    publishedAt: 1,
    coverMediaId: media.first.id,
    mediaCount: media.length,
    media: media,
  );
}

ArchivedPost _livePost({
  required bool hasMotion,
  String? stillPath,
  String? motionPath,
}) {
  final motion = PostMedia(
    id: 'media-live-motion',
    mediaType: PostMediaType.video,
    mediaRole: PostMediaRole.liveMotion,
    logicalIndex: 0,
    sortIndex: 1,
    mimeType: 'video/mp4',
    width: 1080,
    height: 1440,
    localOriginalPath: motionPath,
  );
  return ArchivedPost(
    id: 'post-1',
    sourcePlatform: PostSourcePlatform.xiaohongshu,
    sourceUrl: 'https://www.xiaohongshu.com/explore/live',
    authorUsername: 'author',
    authorDisplayName: '作者',
    caption: '',
    publishedAt: 1,
    coverMediaId: 'media-live-still',
    mediaCount: 1,
    media: [
      PostMedia(
        id: 'media-live-still',
        mediaType: PostMediaType.image,
        mediaRole: PostMediaRole.liveStill,
        logicalIndex: 0,
        mimeType: 'image/jpeg',
        width: 1080,
        height: 1440,
        localOriginalPath: stillPath,
        liveMotion: hasMotion ? motion : null,
      ),
    ],
  );
}

class _FakeAppController extends AppController {
  List<String> sharedMediaIds = [];
  MediaExportMode? sharedExportMode;
  final List<String> savedMediaIds = [];
  final List<MediaExportMode> savedExportModes = [];
  final List<String> deletedMediaIds = [];
  final List<String> deletedPostIds = [];
  final Set<String> saveFailures = {};
  int mediaSaveRefreshCount = 0;
  Completer<void>? shareCompleter;
  Completer<void>? saveCompleter;
  Completer<void>? deleteSelectionCompleter;

  @override
  Future<File> ensureOriginal(PostMedia media) async {
    final path = media.localOriginalPath;
    if (path == null) throw StateError('测试媒体没有原文件');
    return File(path);
  }

  @override
  Future<void> shareMedia(
    List<PostMedia> media, {
    MediaExportMode exportMode = MediaExportMode.original,
  }) async {
    sharedMediaIds = media.map((item) => item.id).toList(growable: false);
    sharedExportMode = exportMode;
    await shareCompleter?.future;
  }

  @override
  Future<String> saveMedia(
    PostMedia media, {
    MediaExportMode exportMode = MediaExportMode.original,
  }) async {
    savedMediaIds.add(media.id);
    savedExportModes.add(exportMode);
    await saveCompleter?.future;
    if (saveFailures.remove(media.id)) throw StateError('测试保存失败');
    return 'PhotoBook_${media.id}.jpg';
  }

  @override
  Future<void> refreshAfterMediaSave() async {
    mediaSaveRefreshCount += 1;
  }

  @override
  Future<void> deletePost(String postId) async {
    deletedPostIds.add(postId);
    posts = posts.where((post) => post.id != postId).toList(growable: false);
    notifyListeners();
  }

  @override
  Future<DeleteMediaSelectionResult> deleteMediaSelection(
    String postId,
    List<PostMedia> media,
  ) async {
    deletedMediaIds.addAll(media.map((item) => item.id));
    await deleteSelectionCompleter?.future;
    final post = posts.singleWhere((candidate) => candidate.id == postId);
    final selectedIds = media.map((item) => item.id).toSet();
    if (selectedIds.containsAll(post.media.map((item) => item.id))) {
      posts = posts.where((item) => item.id != postId).toList(growable: false);
      notifyListeners();
      return DeleteMediaSelectionResult(postId: post.id, postDeleted: true);
    }
    final remaining = post.media
        .where((item) => !selectedIds.contains(item.id))
        .toList(growable: false);
    final updated = ArchivedPost(
      id: post.id,
      sourcePlatform: post.sourcePlatform,
      sourceUrl: post.sourceUrl,
      authorUsername: post.authorUsername,
      authorDisplayName: post.authorDisplayName,
      caption: post.caption,
      publishedAt: post.publishedAt,
      coverMediaId: remaining.any((media) => media.id == post.coverMediaId)
          ? post.coverMediaId
          : remaining.first.id,
      mediaCount: remaining.length,
      media: remaining,
      locationName: post.locationName,
      localAvatarPath: post.localAvatarPath,
    );
    posts = [
      for (final candidate in posts)
        if (candidate.id == post.id) updated else candidate,
    ];
    notifyListeners();
    return DeleteMediaSelectionResult(postId: post.id, postDeleted: false);
  }
}

class _FakeVideoPlayerPlatform extends VideoPlayerPlatform {
  _FakeVideoPlayerPlatform({
    this.emitInitialized = true,
    this.creationFails = false,
  });

  final bool emitInitialized;
  final bool creationFails;
  final Map<int, StreamController<VideoEvent>> _eventControllers = {};
  final Set<int> activePlayers = {};
  int _nextPlayerId = 0;
  int maxActivePlayerCount = 0;

  int get createdPlayerCount => _nextPlayerId;

  @override
  Future<void> init() async {}

  @override
  Future<int?> createWithOptions(VideoCreationOptions options) async {
    if (creationFails) {
      throw PlatformException(code: 'VIDEO_CREATE_FAILED');
    }
    final playerId = _nextPlayerId++;
    final events = StreamController<VideoEvent>();
    _eventControllers[playerId] = events;
    if (emitInitialized) {
      events.add(
        VideoEvent(
          eventType: VideoEventType.initialized,
          size: const Size(1080, 1350),
          duration: const Duration(seconds: 10),
        ),
      );
    }
    return playerId;
  }

  @override
  Stream<VideoEvent> videoEventsFor(int playerId) =>
      _eventControllers[playerId]!.stream;

  @override
  Future<void> dispose(int playerId) async {
    activePlayers.remove(playerId);
    await _eventControllers.remove(playerId)?.close();
  }

  @override
  Future<void> play(int playerId) async {
    activePlayers.add(playerId);
    maxActivePlayerCount = maxActivePlayerCount < activePlayers.length
        ? activePlayers.length
        : maxActivePlayerCount;
  }

  @override
  Future<void> pause(int playerId) async {
    activePlayers.remove(playerId);
  }

  @override
  Future<void> setLooping(int playerId, bool looping) async {}

  @override
  Future<void> setVolume(int playerId, double volume) async {}

  @override
  Future<void> setPlaybackSpeed(int playerId, double speed) async {}

  @override
  Future<Duration> getPosition(int playerId) async => Duration.zero;

  @override
  Widget buildView(int playerId) => const SizedBox.expand();

  Future<void> close() async {
    for (final events in _eventControllers.values.toList()) {
      if (!events.isClosed) await events.close();
    }
    _eventControllers.clear();
    activePlayers.clear();
  }
}
