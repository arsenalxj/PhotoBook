import 'dart:io';

import 'package:flutter/material.dart';

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
    return Card(
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
                  aspectRatio: cover.aspectRatio.clamp(0.58, 1.65),
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      _LocalImage(
                        path: cover.localThumbnailPath,
                        fit: BoxFit.cover,
                      ),
                      if (cover.mediaType == PostMediaType.video)
                        const Center(
                          child: Icon(
                            Icons.play_circle_fill,
                            size: 38,
                            color: Colors.white,
                            shadows: [
                              Shadow(blurRadius: 6, color: Colors.black54),
                            ],
                          ),
                        ),
                      if (post.mediaCount > 1)
                        Positioned(
                          right: 6,
                          top: 6,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: const Color(0xB3000000),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 6,
                                vertical: 3,
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  const Icon(
                                    Icons.collections_outlined,
                                    size: 13,
                                    color: Colors.white,
                                  ),
                                  const SizedBox(width: 3),
                                  Text(
                                    '${post.mediaCount}',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 11,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(9, 8, 9, 9),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (post.caption.isNotEmpty) ...[
                        Text(
                          post.caption,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 13,
                            height: 1.35,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        const SizedBox(height: 8),
                      ],
                      Row(
                        children: [
                          _Avatar(path: post.localAvatarPath),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              post.authorUsername,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: AppTheme.muted,
                                fontSize: 11,
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
    );
  }
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
    color: const Color(0xC8000000),
    child: Center(
      child: Wrap(
        alignment: WrapAlignment.center,
        spacing: 10,
        runSpacing: 10,
        children: [
          FilledButton.tonalIcon(
            key: const ValueKey('filter-author-action'),
            onPressed: onFilterAuthor,
            icon: const Icon(Icons.person_search_outlined),
            label: const Text('只看TA'),
          ),
          FilledButton.icon(
            key: const ValueKey('delete-post-action'),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
              foregroundColor: Theme.of(context).colorScheme.onError,
            ),
            onPressed: onDelete,
            icon: const Icon(Icons.delete_outline),
            label: const Text('删除'),
          ),
        ],
      ),
    ),
  );
}

class _Avatar extends StatelessWidget {
  const _Avatar({this.path});

  final String? path;

  @override
  Widget build(BuildContext context) {
    final file = path == null ? null : File(path!);
    return CircleAvatar(
      radius: 10,
      backgroundColor: AppTheme.divider,
      foregroundImage: file != null && file.existsSync()
          ? FileImage(file)
          : null,
      child: file == null || !file.existsSync()
          ? const Icon(Icons.person, size: 12, color: AppTheme.muted)
          : null,
    );
  }
}

class _LocalImage extends StatelessWidget {
  const _LocalImage({required this.path, required this.fit});

  final String? path;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final file = path == null ? null : File(path!);
    if (file == null || !file.existsSync()) {
      return const ColoredBox(
        color: Color(0xFFE9EAEC),
        child: Center(
          child: Icon(Icons.image_outlined, color: AppTheme.muted, size: 28),
        ),
      );
    }
    return Image.file(
      file,
      fit: fit,
      gaplessPlayback: true,
      cacheWidth: 600,
      errorBuilder: (_, _, _) => const ColoredBox(
        color: Color(0xFFE9EAEC),
        child: Center(
          child: Icon(Icons.broken_image_outlined, color: AppTheme.muted),
        ),
      ),
    );
  }
}
