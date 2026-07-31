import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_staggered_grid_view/flutter_staggered_grid_view.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../models/post.dart';
import '../widgets/post_action_sheets.dart';
import '../widgets/post_card.dart';
import 'detail_screen.dart';
import 'settings_screen.dart';
import 'task_list_screen.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  String? _activePostId;
  _AuthorFilter? _authorFilter;

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(appControllerProvider);
    final visiblePosts = _authorFilter == null
        ? controller.posts
        : controller.posts
              .where(
                (post) =>
                    post.sourcePlatform == _authorFilter!.sourcePlatform &&
                    post.authorUsername.toLowerCase() ==
                        _authorFilter!.username.toLowerCase(),
              )
              .toList(growable: false);

    return PopScope(
      canPop: _activePostId == null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop && _activePostId != null) {
          setState(() => _activePostId = null);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: _buildTitle(context),
          actions: [
            if (controller.isSyncing)
              const SizedBox.square(
                dimension: 48,
                child: Center(
                  child: SizedBox.square(
                    dimension: 19,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              ),
            IconButton(
              tooltip: '粘贴链接',
              onPressed: controller.isImportingClipboard
                  ? null
                  : () => controller.importClipboard(),
              icon: const Icon(LucideIcons.clipboard),
            ),
            Badge.count(
              count: controller.taskCount,
              isLabelVisible: controller.taskCount > 0,
              backgroundColor: controller.hasRealFailures
                  ? AppTheme.danger
                  : AppTheme.accent,
              textColor: AppTheme.accentOn,
              offset: const Offset(-5, 5),
              child: IconButton(
                tooltip: '任务列表',
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(
                    builder: (_) => const TaskListScreen(),
                  ),
                ),
                icon: const Icon(LucideIcons.alignLeft),
              ),
            ),
            IconButton(
              tooltip: '设置',
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const SettingsScreen()),
              ),
              icon: const Icon(LucideIcons.settings),
            ),
            const SizedBox(width: 4),
          ],
        ),
        body: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTap: _activePostId == null
              ? null
              : () => setState(() => _activePostId = null),
          child: RefreshIndicator(
            onRefresh: () => controller.synchronize(showErrors: true),
            child: controller.posts.isEmpty
                ? const _EmptyLibrary(
                    message: '还没有保存的帖子',
                    detail: '在 Instagram 或小红书里把帖子「分享」到 PhotoBook，即可归档到本机。',
                    icon: LucideIcons.bookmark,
                  )
                : visiblePosts.isEmpty
                ? const _EmptyLibrary(
                    message: '没有该博主的帖子',
                    icon: LucideIcons.search,
                  )
                : NotificationListener<ScrollStartNotification>(
                    onNotification: (_) {
                      if (_activePostId != null) {
                        setState(() => _activePostId = null);
                      }
                      return false;
                    },
                    child: _PostGrid(
                      posts: visiblePosts,
                      activePostId: _activePostId,
                      onTap: _openPost,
                      onLongPress: (post) {
                        setState(() => _activePostId = post.id);
                      },
                      onFilterAuthor: _filterAuthor,
                      onDelete: _deletePost,
                    ),
                  ),
          ),
        ),
      ),
    );
  }

  Widget _buildTitle(BuildContext context) {
    final filter = _authorFilter;
    if (filter == null) return const Text('PhotoBook');
    return ConstrainedBox(
      constraints: BoxConstraints(
        maxWidth: (MediaQuery.sizeOf(context).width - 188).clamp(120, 260),
      ),
      child: InputChip(
        key: const ValueKey('author-filter-chip'),
        label: Text(filter.label, maxLines: 1, overflow: TextOverflow.ellipsis),
        deleteIcon: const Icon(LucideIcons.x, size: 18),
        onDeleted: _clearFilter,
        onPressed: _clearFilter,
        backgroundColor: AppTheme.surface,
        deleteIconColor: AppTheme.muted,
        side: const BorderSide(color: AppTheme.border),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
        ),
        labelStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
        visualDensity: VisualDensity.compact,
      ),
    );
  }

  void _clearFilter() => setState(() => _authorFilter = null);

  void _filterAuthor(ArchivedPost post) {
    setState(() {
      _activePostId = null;
      _authorFilter = _AuthorFilter(
        sourcePlatform: post.sourcePlatform,
        username: post.authorUsername,
        label: post.sourcePlatform == PostSourcePlatform.instagram
            ? '@${post.authorUsername}'
            : post.authorUsername,
      );
    });
  }

  void _openPost(ArchivedPost post) {
    if (_activePostId != null) {
      setState(() => _activePostId = null);
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => DetailScreen(postId: post.id)),
    );
  }

  Future<void> _deletePost(ArchivedPost post) async {
    setState(() => _activePostId = null);
    await showDeletePostSheet(
      context: context,
      post: post,
      onDelete: () => ref.read(appControllerProvider).deletePost(post.id),
    );
  }
}

class _PostGrid extends StatelessWidget {
  const _PostGrid({
    required this.posts,
    required this.activePostId,
    required this.onTap,
    required this.onLongPress,
    required this.onFilterAuthor,
    required this.onDelete,
  });

  final List<ArchivedPost> posts;
  final String? activePostId;
  final ValueChanged<ArchivedPost> onTap;
  final ValueChanged<ArchivedPost> onLongPress;
  final ValueChanged<ArchivedPost> onFilterAuthor;
  final ValueChanged<ArchivedPost> onDelete;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 760 ? 3 : 2;
        return MasonryGridView.count(
          physics: const AlwaysScrollableScrollPhysics(),
          crossAxisCount: columns,
          mainAxisSpacing: 8,
          crossAxisSpacing: 8,
          padding: const EdgeInsets.fromLTRB(8, 8, 8, 96),
          itemCount: posts.length,
          itemBuilder: (context, index) {
            final post = posts[index];
            return RepaintBoundary(
              child: PostCard(
                key: ValueKey(post.id),
                post: post,
                showActions: activePostId == post.id,
                onTap: () => onTap(post),
                onLongPress: () => onLongPress(post),
                onFilterAuthor: () => onFilterAuthor(post),
                onDelete: () => onDelete(post),
              ),
            );
          },
        );
      },
    );
  }
}

class _EmptyLibrary extends StatelessWidget {
  const _EmptyLibrary({required this.message, required this.icon, this.detail});

  final String message;
  final String? detail;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(32),
      children: [
        const SizedBox(height: 164),
        Center(
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: AppTheme.surface,
              border: Border.all(color: AppTheme.border),
              borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
            ),
            child: SizedBox.square(
              dimension: 56,
              child: Icon(icon, size: 26, color: AppTheme.foreground),
            ),
          ),
        ),
        const SizedBox(height: 12),
        Text(
          message,
          textAlign: TextAlign.center,
          style: const TextStyle(color: AppTheme.muted, fontSize: 14),
        ),
        if (detail != null) ...[
          const SizedBox(height: 12),
          Text(
            detail!,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppTheme.muted, fontSize: 13),
          ),
        ],
      ],
    );
  }
}

class _AuthorFilter {
  const _AuthorFilter({
    required this.sourcePlatform,
    required this.username,
    required this.label,
  });

  final PostSourcePlatform sourcePlatform;
  final String username;
  final String label;
}
