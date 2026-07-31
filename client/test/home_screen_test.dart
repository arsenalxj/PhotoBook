import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/models/post.dart';
import 'package:photobook/models/archive_job.dart';
import 'package:photobook/screens/home_screen.dart';
import 'package:photobook/screens/task_list_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

void main() {
  testWidgets('首页没有 Tab，任务列表从右上角入口进入并显示总数', (tester) async {
    final controller = AppController()
      ..phase = AppPhase.ready
      ..tasks = const [
        ArchiveJob(
          id: 'active',
          sourcePostId: 'ACTIVE',
          status: ArchiveJobStatus.fetching,
        ),
        ArchiveJob(
          id: 'failed',
          sourcePostId: 'FAILED',
          status: ArchiveJobStatus.failed,
          errorCode: 'NETWORK_ERROR',
        ),
      ];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(theme: AppTheme.light, home: const HomeScreen()),
      ),
    );

    expect(find.byType(TabBar), findsNothing);
    expect(find.byIcon(LucideIcons.alignLeft), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(
      tester.widget<Badge>(find.byType(Badge)).backgroundColor,
      AppTheme.light.colorScheme.error,
    );
    expect(find.byIcon(LucideIcons.settings), findsOneWidget);
    expect(find.byTooltip('粘贴链接'), findsOneWidget);
    expect(find.text('PhotoBook'), findsOneWidget);

    await tester.tap(find.byTooltip('任务列表'));
    await tester.pumpAndSettle();

    expect(find.byType(TaskListScreen), findsOneWidget);
    expect(find.text('任务'), findsOneWidget);
  });

  testWidgets('首页粘贴按钮触发剪贴板导入', (tester) async {
    final controller = _FakeAppController()..phase = AppPhase.ready;
    await _pumpHome(tester, controller);

    await tester.tap(find.byTooltip('粘贴链接'));
    await tester.pump();

    expect(controller.clipboardImportCount, 1);
  });

  testWidgets('只有进行中任务时角标使用非错误色', (tester) async {
    final controller = AppController()
      ..phase = AppPhase.ready
      ..tasks = const [
        ArchiveJob(
          id: 'active',
          sourcePostId: 'ACTIVE',
          status: ArchiveJobStatus.queued,
        ),
      ];
    await _pumpHome(tester, controller);

    expect(
      tester.widget<Badge>(find.byType(Badge)).backgroundColor,
      AppTheme.light.colorScheme.secondary,
    );
  });

  testWidgets('长按时只有一张卡片显示操作，作者筛选可从标题胶囊取消', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _post(id: 'post-a', username: 'alice', displayName: 'Alice'),
        _post(id: 'post-b', username: 'bob', displayName: 'Bob'),
      ];
    await _pumpHome(tester, controller);

    await tester.longPress(find.byKey(const ValueKey('post-a')));
    await tester.pump();

    expect(find.byKey(const ValueKey('post-actions-overlay')), findsOneWidget);
    expect(find.text('只看TA'), findsOneWidget);
    expect(find.text('删除'), findsOneWidget);

    await tester.longPress(find.byKey(const ValueKey('post-b')));
    await tester.pump();

    expect(find.byKey(const ValueKey('post-actions-overlay')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('filter-author-action')));
    await tester.pump();

    final chip = find.byKey(const ValueKey('author-filter-chip'));
    expect(chip, findsOneWidget);
    expect(
      find.descendant(of: chip, matching: find.text('@bob')),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('post-a')), findsNothing);
    expect(find.byKey(const ValueKey('post-b')), findsOneWidget);

    await tester.tap(
      find.descendant(of: chip, matching: find.byIcon(LucideIcons.x)),
    );
    await tester.pump();

    expect(find.text('PhotoBook'), findsOneWidget);
    expect(find.byKey(const ValueKey('post-a')), findsOneWidget);
    expect(find.byKey(const ValueKey('post-b')), findsOneWidget);
  });

  testWidgets('首页删除先显示确认抽屉，确认后才删除帖子', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [_post(id: 'post-a', username: 'alice', displayName: 'Alice')];
    await _pumpHome(tester, controller);

    await tester.longPress(find.byKey(const ValueKey('post-a')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('delete-post-action')));
    await tester.pumpAndSettle();

    expect(find.text('删除这条帖子?'), findsOneWidget);
    expect(controller.deletedPostIds, isEmpty);

    await tester.tap(find.byKey(const ValueKey('confirm-delete-post')));
    await tester.pumpAndSettle();

    expect(controller.deletedPostIds, ['post-a']);
    expect(find.text('还没有保存的帖子'), findsOneWidget);
  });

  testWidgets('同名作者按来源平台隔离筛选', (tester) async {
    final controller = _FakeAppController()
      ..phase = AppPhase.ready
      ..posts = [
        _post(id: 'ig-alice', username: 'alice', displayName: 'Alice'),
        _post(
          id: 'xhs-alice',
          username: 'alice',
          displayName: '小红书 Alice',
          sourcePlatform: PostSourcePlatform.xiaohongshu,
        ),
      ];
    await _pumpHome(tester, controller);

    await tester.longPress(find.byKey(const ValueKey('xhs-alice')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('filter-author-action')));
    await tester.pump();

    expect(find.byKey(const ValueKey('xhs-alice')), findsOneWidget);
    expect(find.byKey(const ValueKey('ig-alice')), findsNothing);
  });
}

Future<void> _pumpHome(WidgetTester tester, AppController controller) =>
    tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(theme: AppTheme.light, home: const HomeScreen()),
      ),
    );

ArchivedPost _post({
  required String id,
  required String username,
  required String displayName,
  PostSourcePlatform sourcePlatform = PostSourcePlatform.instagram,
}) => ArchivedPost(
  id: id,
  sourcePlatform: sourcePlatform,
  sourceUrl: sourcePlatform == PostSourcePlatform.instagram
      ? 'https://www.instagram.com/p/$id/'
      : 'https://www.xiaohongshu.com/explore/$id',
  authorUsername: username,
  authorDisplayName: displayName,
  caption: id,
  publishedAt: 1,
  coverMediaId: '$id-media',
  mediaCount: 1,
  media: [
    PostMedia(
      id: '$id-media',
      mediaType: PostMediaType.image,
      width: 1080,
      height: 1350,
    ),
  ],
);

class _FakeAppController extends AppController {
  final List<String> deletedPostIds = [];
  int clipboardImportCount = 0;

  @override
  Future<void> importClipboard({bool automatic = false}) async {
    clipboardImportCount += 1;
  }

  @override
  Future<void> deletePost(String postId) async {
    deletedPostIds.add(postId);
    posts = posts.where((post) => post.id != postId).toList(growable: false);
    notifyListeners();
  }
}
