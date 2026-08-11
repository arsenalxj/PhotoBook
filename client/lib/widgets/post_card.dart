import 'dart:io';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../core/theme/app_theme.dart';
import '../models/post.dart';

class PostCard extends StatelessWidget {
  const PostCard({
    required this.post,
    required this.onTap,
    required this.onLongPress,
    required this.showActions,
    required this.onFilterAuthor,
    required this.onDelete,
    super.key,
  });

  final ArchivedPost post;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  final bool showActions;
  final VoidCallback onFilterAuthor;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final cover = post.coverMedia;
    final displayName = post.authorDisplayName.trim();
    final authorName = displayName.isNotEmpty
        ? displayName
        : post.sourcePlatform == PostSourcePlatform.instagram
        ? '@${post.authorUsername}'
        : post.authorUsername;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
        boxShadow: [
          BoxShadow(
            color: AppTheme.foreground.withValues(alpha: 0.08),
            blurRadius: 3,
            offset: const Offset(0, 1),
          ),
        ],
      ),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            InkWell(
              onTap: onTap,
              onLongPress: onLongPress,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  AspectRatio(
                    key: ValueKey('post-cover-${post.id}'),
                    aspectRatio: cover.aspectRatio.clamp(0.58, 1.65),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        _LocalImage(path: cover.localThumbnailPath),
                        if (cover.mediaType == PostMediaType.video)
                          Center(
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                color: AppTheme.accent.withValues(alpha: 0.78),
                                shape: BoxShape.circle,
                              ),
                              child: const SizedBox.square(
                                dimension: 40,
                                child: Icon(
                                  LucideIcons.play,
                                  size: 24,
                                  color: AppTheme.accentOn,
                                ),
                              ),
                            ),
                          ),
                        if (post.mediaCount > 1 || post.isBackedUp)
                          Positioned(
                            right: 8,
                            top: 8,
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                if (post.mediaCount > 1)
                                  _MediaCountBadge(post: post),
                                if (post.mediaCount > 1 && post.isBackedUp)
                                  const SizedBox(width: 6),
                                if (post.isBackedUp)
                                  _PostBackupBadge(postId: post.id),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (post.caption.isNotEmpty) ...[
                          Text(
                            post.caption,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 13, height: 1.45),
                          ),
                          const SizedBox(height: 8),
                        ],
                        Row(
                          children: [
                            _Avatar(
                              path: post.localAvatarPath,
                              fallback: _avatarFallback(post),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                authorName,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: AppTheme.muted,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                            const SizedBox(width: 8),
                            DecoratedBox(
                              decoration: BoxDecoration(
                                border: Border.all(color: AppTheme.border),
                                borderRadius: BorderRadius.circular(
                                  AppTheme.radiusSmall,
                                ),
                              ),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 7,
                                  vertical: 1,
                                ),
                                child: Text(
                                  post.sourcePlatform ==
                                          PostSourcePlatform.instagram
                                      ? 'IG'
                                      : '小红书',
                                  style: const TextStyle(
                                    color: AppTheme.muted,
                                    fontFamily: 'monospace',
                                    fontSize: 10,
                                    height: 1.5,
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            if (showActions)
              Positioned.fill(
                child: _PostActionsOverlay(
                  onFilterAuthor: onFilterAuthor,
                  onDelete: onDelete,
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _avatarFallback(ArchivedPost post) {
    final displayName = post.authorDisplayName.trim();
    final source = displayName.isEmpty
        ? post.authorUsername.trim()
        : displayName;
    return source.isEmpty ? '?' : source.characters.first;
  }
}

class _MediaCountBadge extends StatelessWidget {
  const _MediaCountBadge({required this.post});

  final ArchivedPost post;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    key: ValueKey('post-media-count-${post.id}'),
    decoration: BoxDecoration(
      color: AppTheme.accent.withValues(alpha: 0.78),
      borderRadius: BorderRadius.circular(999),
    ),
    child: SizedBox(
      height: 26,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(LucideIcons.copy, size: 11, color: AppTheme.accentOn),
            const SizedBox(width: 4),
            Text(
              '${post.mediaCount}',
              style: const TextStyle(
                color: AppTheme.accentOn,
                fontFamily: 'monospace',
                fontSize: 11,
                height: 1.2,
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _PostBackupBadge extends StatelessWidget {
  const _PostBackupBadge({required this.postId});

  final String postId;

  @override
  Widget build(BuildContext context) => Tooltip(
    message: '已备份到 R2',
    child: DecoratedBox(
      key: ValueKey('post-backup-success-$postId'),
      decoration: BoxDecoration(
        color: AppTheme.accent.withValues(alpha: 0.78),
        borderRadius: BorderRadius.circular(999),
      ),
      child: const SizedBox.square(
        dimension: 26,
        child: Icon(LucideIcons.cloudCheck, size: 15, color: AppTheme.accentOn),
      ),
    ),
  );
}

class _PostActionsOverlay extends StatelessWidget {
  const _PostActionsOverlay({
    required this.onFilterAuthor,
    required this.onDelete,
  });

  final VoidCallback onFilterAuthor;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) => Material(
    key: const ValueKey('post-actions-overlay'),
    color: AppTheme.accent.withValues(alpha: 0.78),
    child: Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 128,
            child: OutlinedButton(
              key: const ValueKey('filter-author-action'),
              style: OutlinedButton.styleFrom(
                backgroundColor: AppTheme.surface,
                foregroundColor: AppTheme.foreground,
              ),
              onPressed: onFilterAuthor,
              child: const Text('只看TA'),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: 128,
            child: FilledButton(
              key: const ValueKey('delete-post-action'),
              style: FilledButton.styleFrom(
                backgroundColor: AppTheme.danger,
                foregroundColor: AppTheme.accentOn,
              ),
              onPressed: onDelete,
              child: const Text('删除'),
            ),
          ),
        ],
      ),
    ),
  );
}

class _Avatar extends StatelessWidget {
  const _Avatar({required this.path, required this.fallback});

  final String? path;
  final String fallback;

  @override
  Widget build(BuildContext context) {
    final file = path == null ? null : File(path!);
    final hasImage = file != null && file.existsSync();
    return CircleAvatar(
      radius: 12,
      backgroundColor: AppTheme.accent,
      foregroundColor: AppTheme.accentOn,
      foregroundImage: hasImage ? FileImage(file) : null,
      child: hasImage
          ? null
          : Text(
              fallback,
              style: const TextStyle(
                color: AppTheme.accentOn,
                fontSize: 11,
                fontWeight: FontWeight.w700,
              ),
            ),
    );
  }
}

class _LocalImage extends StatelessWidget {
  const _LocalImage({required this.path});

  final String? path;

  @override
  Widget build(BuildContext context) {
    final file = path == null ? null : File(path!);
    if (file == null || !file.existsSync()) {
      return const ColoredBox(
        color: AppTheme.surface,
        child: Center(
          child: Icon(LucideIcons.image, color: AppTheme.muted, size: 28),
        ),
      );
    }
    return Image.file(
      file,
      fit: BoxFit.cover,
      gaplessPlayback: true,
      cacheWidth: 600,
      errorBuilder: (_, _, _) => const ColoredBox(
        color: AppTheme.surface,
        child: Center(child: Icon(LucideIcons.imageOff, color: AppTheme.muted)),
      ),
    );
  }
}
