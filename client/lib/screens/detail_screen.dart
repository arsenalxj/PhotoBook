import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:video_player/video_player.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../models/post.dart';
import '../widgets/post_action_sheets.dart';

class DetailScreen extends ConsumerStatefulWidget {
  const DetailScreen({required this.postId, super.key});

  final String postId;

  @override
  ConsumerState<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends ConsumerState<DetailScreen> {
  String? _currentMediaId;
  int _currentMediaIndex = 0;

  @override
  Widget build(BuildContext context) {
    final posts = ref.watch(
      appControllerProvider.select((controller) => controller.posts),
    );
    ArchivedPost? post;
    for (final candidate in posts) {
      if (candidate.id == widget.postId) {
        post = candidate;
        break;
      }
    }
    if (post == null) {
      return Scaffold(
        appBar: AppBar(),
        body: const Center(child: Text('帖子不存在')),
      );
    }
    final currentMedia = _resolveCurrentMedia(post);

    return Scaffold(
      appBar: AppBar(
        title: Text(
          post.sourcePlatform == PostSourcePlatform.instagram
              ? '@${post.authorUsername}'
              : post.authorDisplayName,
        ),
        actions: [
          IconButton(
            key: const ValueKey('share-post-media'),
            tooltip: '分享媒体',
            onPressed: () => _sharePost(post!, currentMedia.id),
            icon: const Icon(Icons.share_outlined),
          ),
          IconButton(
            key: const ValueKey('save-post-media'),
            tooltip: '保存媒体',
            onPressed: () => _savePostMedia(post!),
            icon: const Icon(Icons.download_outlined),
          ),
          IconButton(
            key: const ValueKey('delete-post-media'),
            tooltip: '删除媒体',
            onPressed: () => _deletePostMedia(post!),
            icon: const Icon(Icons.delete_outline),
          ),
          PopupMenuButton<_DetailMenuAction>(
            tooltip: '更多',
            onSelected: (action) {
              if (action == _DetailMenuAction.openSource) {
                unawaited(_openSource(post!.sourceUrl));
              }
            },
            itemBuilder: (context) => const [
              PopupMenuItem(
                value: _DetailMenuAction.openSource,
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.open_in_new),
                  title: Text('打开原帖'),
                ),
              ),
            ],
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 36),
        children: [
          _AuthorHeader(post: post),
          SizedBox(
            key: const ValueKey('media-viewer'),
            height: _mediaViewerHeight(
              screenSize: MediaQuery.sizeOf(context),
              media: post.media,
            ),
            child: _LazyMediaViewer(
              post: post,
              currentMediaId: currentMedia.id,
              onMediaChanged: (mediaId, index) {
                if (_currentMediaId == mediaId && _currentMediaIndex == index) {
                  return;
                }
                setState(() {
                  _currentMediaId = mediaId;
                  _currentMediaIndex = index;
                });
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 18, 16, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (post.caption.isNotEmpty)
                  SelectableText(
                    post.caption,
                    style: const TextStyle(fontSize: 15, height: 1.55),
                  ),
                if (post.caption.isNotEmpty) const SizedBox(height: 16),
                Wrap(
                  spacing: 14,
                  runSpacing: 8,
                  children: [
                    _MetaItem(
                      icon: Icons.calendar_today_outlined,
                      label: _formatDate(post.publishedAt),
                    ),
                    if (post.locationName != null)
                      _MetaItem(
                        icon: Icons.location_on_outlined,
                        label: post.locationName!,
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  PostMedia _resolveCurrentMedia(ArchivedPost post) {
    var index = post.media.indexWhere((item) => item.id == _currentMediaId);
    if (index < 0) {
      index = _currentMediaIndex.clamp(0, post.media.length - 1);
      _currentMediaId = post.media[index].id;
    }
    _currentMediaIndex = index;
    return post.media[index];
  }

  Future<void> _sharePost(ArchivedPost post, String currentMediaId) =>
      showShareMediaSheet(
        context: context,
        post: post,
        initialMediaId: currentMediaId,
        onShare: (media, exportMode) async {
          await ref
              .read(appControllerProvider)
              .shareMedia(media, exportMode: exportMode);
        },
      );

  Future<void> _savePostMedia(ArchivedPost post) async {
    final savedCount = await showSaveMediaSelectionSheet(
      context: context,
      post: post,
      onSave: (media, exportMode) async {
        await ref
            .read(appControllerProvider)
            .saveMedia(media, exportMode: exportMode);
      },
    );
    if (!mounted || savedCount == null || savedCount == 0) return;
    await ref.read(appControllerProvider).refreshAfterMediaSave();
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('已保存 $savedCount 项到系统相册')));
  }

  Future<void> _deletePostMedia(ArchivedPost post) async {
    final outcome = await showDeleteMediaSelectionSheet(
      context: context,
      post: post,
      onDelete: (media) async {
        final result = await ref
            .read(appControllerProvider)
            .deleteMediaSelection(post.id, media);
        return result.postDeleted;
      },
    );
    if (!mounted || outcome == null) return;
    if (outcome.postDeleted) {
      if (Navigator.of(context).canPop()) Navigator.of(context).pop();
      return;
    }
    final remaining = post.media
        .where((item) => !outcome.deletedMediaIds.contains(item.id))
        .toList(growable: false);
    if (remaining.isNotEmpty) {
      final currentMediaId = _currentMediaId;
      final currentStillExists = remaining.any(
        (item) => item.id == currentMediaId,
      );
      final targetIndex = currentStillExists
          ? remaining.indexWhere((item) => item.id == currentMediaId)
          : _currentMediaIndex.clamp(0, remaining.length - 1);
      setState(() {
        _currentMediaIndex = targetIndex;
        _currentMediaId = remaining[targetIndex].id;
      });
    }
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已删除 ${outcome.deletedMediaIds.length} 项媒体')),
    );
  }

  Future<void> _openSource(String sourceUrl) async {
    try {
      final opened = await launchUrl(
        Uri.parse(sourceUrl),
        mode: LaunchMode.externalApplication,
      );
      if (!opened) throw StateError('无法打开原帖');
    } on Object catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(_messageFor(error))));
    }
  }
}

enum _DetailMenuAction { openSource }

double _mediaViewerHeight({
  required Size screenSize,
  required List<PostMedia> media,
}) {
  final maxHeight = screenSize.height * 2 / 3;
  return media.fold<double>(0, (height, item) {
    final fullWidthHeight = screenSize.width / item.aspectRatio;
    return math.max(height, math.min(fullWidthHeight, maxHeight));
  });
}

class _AuthorHeader extends StatelessWidget {
  const _AuthorHeader({required this.post});

  final ArchivedPost post;

  @override
  Widget build(BuildContext context) {
    final avatar = post.localAvatarPath == null
        ? null
        : File(post.localAvatarPath!);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 14),
      child: Row(
        children: [
          CircleAvatar(
            radius: 22,
            backgroundColor: AppTheme.divider,
            foregroundImage: avatar != null && avatar.existsSync()
                ? FileImage(avatar)
                : null,
            child: avatar == null || !avatar.existsSync()
                ? const Icon(Icons.person_outline, color: AppTheme.muted)
                : null,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  post.authorDisplayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 2),
                Text(
                  post.sourcePlatform == PostSourcePlatform.instagram
                      ? '@${post.authorUsername}'
                      : '小红书 · ${post.authorUsername}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: AppTheme.muted, fontSize: 13),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _LazyMediaViewer extends ConsumerStatefulWidget {
  const _LazyMediaViewer({
    required this.post,
    required this.currentMediaId,
    required this.onMediaChanged,
  });

  final ArchivedPost post;
  final String currentMediaId;
  final void Function(String mediaId, int index) onMediaChanged;

  @override
  ConsumerState<_LazyMediaViewer> createState() => _LazyMediaViewerState();
}

class _LazyMediaViewerState extends ConsumerState<_LazyMediaViewer> {
  final Map<String, String> _originalPaths = {};
  final Map<String, String> _errors = {};
  final Set<String> _downloading = {};
  final Set<String> _livePressed = {};
  late final PageController _pageController;

  @override
  void initState() {
    super.initState();
    final initialIndex = widget.post.media.indexWhere(
      (item) => item.id == widget.currentMediaId,
    );
    _pageController = PageController(
      initialPage: initialIndex < 0 ? 0 : initialIndex,
    );
    _seedOriginalPaths();
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => _ensureMedia(widget.currentMediaId),
    );
  }

  @override
  void didUpdateWidget(_LazyMediaViewer oldWidget) {
    super.didUpdateWidget(oldWidget);
    final activeIds = widget.post.media.map((item) => item.id).toSet();
    final activeMotionIds = widget.post.media
        .map((item) => item.liveMotion?.id)
        .whereType<String>();
    activeIds.addAll(activeMotionIds);
    _originalPaths.removeWhere((mediaId, _) => !activeIds.contains(mediaId));
    _errors.removeWhere((mediaId, _) => !activeIds.contains(mediaId));
    _downloading.removeWhere((mediaId) => !activeIds.contains(mediaId));
    _seedOriginalPaths();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final targetIndex = widget.post.media.indexWhere(
        (item) => item.id == widget.currentMediaId,
      );
      if (targetIndex >= 0 && _pageController.hasClients) {
        final currentPage = _pageController.page?.round();
        if (currentPage != targetIndex) _pageController.jumpToPage(targetIndex);
      }
      unawaited(_ensureMedia(widget.currentMediaId));
    });
  }

  void _seedOriginalPaths() {
    for (final media in widget.post.media) {
      final localPath = media.localOriginalPath;
      if (localPath != null && File(localPath).existsSync()) {
        _originalPaths[media.id] = localPath;
      }
      final motion = media.liveMotion;
      final motionPath = motion?.localOriginalPath;
      if (motion != null &&
          motionPath != null &&
          File(motionPath).existsSync()) {
        _originalPaths[motion.id] = motionPath;
      }
    }
  }

  Future<void> _ensureMedia(String mediaId) async {
    PostMedia? media;
    for (final candidate in widget.post.media) {
      if (candidate.id == mediaId) {
        media = candidate;
        break;
      }
    }
    if (media == null) return;
    if (!mounted ||
        _originalPaths.containsKey(mediaId) ||
        !_downloading.add(mediaId)) {
      return;
    }
    setState(() => _errors.remove(mediaId));
    try {
      final file = await ref.read(appControllerProvider).ensureOriginal(media);
      if (!mounted) return;
      final stillActive = widget.post.media.any((item) => item.id == mediaId);
      if (stillActive) setState(() => _originalPaths[mediaId] = file.path);
    } on Object catch (error) {
      if (!mounted) return;
      setState(() => _errors[mediaId] = error.toString());
    } finally {
      if (mounted) setState(() => _downloading.remove(mediaId));
    }
  }

  Future<void> _ensureLiveMotion(PostMedia media) async {
    final motion = media.liveMotion;
    if (motion == null || _originalPaths.containsKey(motion.id)) return;
    if (!_downloading.add(motion.id)) return;
    try {
      final file = await ref.read(appControllerProvider).ensureOriginal(motion);
      if (!mounted) return;
      setState(() => _originalPaths[motion.id] = file.path);
    } on Object catch (error) {
      if (mounted) setState(() => _errors[motion.id] = error.toString());
    } finally {
      if (mounted) setState(() => _downloading.remove(motion.id));
    }
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final currentIndex = widget.post.media.indexWhere(
      (item) => item.id == widget.currentMediaId,
    );
    return ColoredBox(
      color: const Color(0xFF111111),
      child: Stack(
        children: [
          PageView.builder(
            controller: _pageController,
            itemCount: widget.post.media.length,
            onPageChanged: (index) {
              final mediaId = widget.post.media[index].id;
              widget.onMediaChanged(mediaId, index);
              unawaited(_ensureMedia(mediaId));
              final media = widget.post.media[index];
              if (media.hasLiveMotion) unawaited(_ensureLiveMotion(media));
            },
            itemBuilder: (context, index) {
              final media = widget.post.media[index];
              return _buildMedia(media, media.id == widget.currentMediaId);
            },
          ),
          if (widget.post.media.length > 1)
            Positioned(
              right: 12,
              top: 12,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: const Color(0x99000000),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                  child: Text(
                    '${(currentIndex < 0 ? 0 : currentIndex) + 1}/${widget.post.media.length}',
                    style: const TextStyle(color: Colors.white, fontSize: 12),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildMedia(PostMedia media, bool isCurrent) {
    final originalPath = _originalPaths[media.id];
    if (originalPath != null) {
      final file = File(originalPath);
      if (media.mediaType == PostMediaType.video) {
        return _LocalVideo(
          key: ValueKey(media.id),
          file: file,
          thumbnailPath: media.localThumbnailPath,
          width: media.width,
          height: media.height,
          isCurrent: isCurrent,
        );
      }
      if (media.isLivePhoto) {
        final motionPath = media.liveMotion == null
            ? null
            : _originalPaths[media.liveMotion!.id];
        final playing = _livePressed.contains(media.id) && motionPath != null;
        return GestureDetector(
          key: ValueKey('live-${media.id}'),
          behavior: HitTestBehavior.opaque,
          onLongPressStart: (_) {
            setState(() => _livePressed.add(media.id));
            unawaited(_ensureLiveMotion(media));
          },
          onLongPressEnd: (_) => setState(() => _livePressed.remove(media.id)),
          onLongPressCancel: () =>
              setState(() => _livePressed.remove(media.id)),
          child: playing
              ? _LocalVideo(
                  key: ValueKey('live-motion-${media.liveMotion!.id}'),
                  file: File(motionPath),
                  thumbnailPath: media.localThumbnailPath,
                  width: media.width,
                  height: media.height,
                  isCurrent: isCurrent,
                )
              : Stack(
                  fit: StackFit.expand,
                  children: [
                    _MediaPlaceholder(thumbnailPath: media.localThumbnailPath),
                    Image.file(
                      file,
                      fit: BoxFit.contain,
                      gaplessPlayback: true,
                    ),
                    if (media.hasLiveMotion)
                      const Positioned(
                        left: 12,
                        top: 12,
                        child: Icon(
                          Icons.motion_photos_on,
                          color: Colors.white,
                        ),
                      ),
                  ],
                ),
        );
      }
      return Stack(
        key: ValueKey('media-${media.id}'),
        fit: StackFit.expand,
        children: [
          _MediaPlaceholder(thumbnailPath: media.localThumbnailPath),
          Image.file(
            file,
            fit: BoxFit.contain,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => const SizedBox.shrink(),
          ),
        ],
      );
    }

    return Stack(
      key: ValueKey('media-${media.id}'),
      fit: StackFit.expand,
      children: [
        _MediaPlaceholder(thumbnailPath: media.localThumbnailPath),
        if (isCurrent && _downloading.contains(media.id))
          const Center(child: CircularProgressIndicator(color: Colors.white)),
        if (isCurrent && _errors.containsKey(media.id))
          Center(
            child: IconButton.filled(
              tooltip: '重新下载',
              onPressed: () => _ensureMedia(media.id),
              icon: const Icon(Icons.refresh),
            ),
          ),
      ],
    );
  }
}

class _MediaPlaceholder extends StatelessWidget {
  const _MediaPlaceholder({this.thumbnailPath});

  final String? thumbnailPath;

  @override
  Widget build(BuildContext context) {
    final file = thumbnailPath == null ? null : File(thumbnailPath!);
    if (file == null || !file.existsSync()) {
      return const Center(
        child: Icon(Icons.image_outlined, color: Colors.white54, size: 42),
      );
    }
    return Image.file(file, fit: BoxFit.contain, gaplessPlayback: true);
  }
}

class _LocalVideo extends StatefulWidget {
  const _LocalVideo({
    required this.file,
    required this.thumbnailPath,
    required this.width,
    required this.height,
    required this.isCurrent,
    super.key,
  });

  final File file;
  final String? thumbnailPath;
  final int width;
  final int height;
  final bool isCurrent;

  @override
  State<_LocalVideo> createState() => _LocalVideoState();
}

class _LocalVideoState extends State<_LocalVideo> {
  late final VideoPlayerController _controller;
  bool _initialized = false;
  bool _failed = false;
  bool _isPlaying = false;
  int _playbackRevision = 0;

  @override
  void initState() {
    super.initState();
    _controller = VideoPlayerController.file(
      widget.file,
      viewType: VideoViewType.platformView,
    );
    _controller.addListener(_handleControllerChanged);
    unawaited(_initialize());
  }

  @override
  void didUpdateWidget(_LocalVideo oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.isCurrent != widget.isCurrent && _initialized) {
      _schedulePlaybackSync();
    }
  }

  Future<void> _initialize() async {
    try {
      await _controller.initialize();
      await _controller.setLooping(true);
      if (!mounted) return;
      setState(() => _initialized = true);
      _schedulePlaybackSync();
    } on Object {
      if (mounted) setState(() => _failed = true);
    }
  }

  void _handleControllerChanged() {
    final isPlaying = _controller.value.isPlaying;
    if (!mounted || _isPlaying == isPlaying) return;
    setState(() => _isPlaying = isPlaying);
  }

  void _schedulePlaybackSync() {
    final revision = ++_playbackRevision;
    unawaited(_syncPlayback(revision));
  }

  Future<void> _syncPlayback(int revision) async {
    try {
      if (!mounted || !_initialized) return;
      if (widget.isCurrent && !_controller.value.isPlaying) {
        await _controller.play();
      } else if (!widget.isCurrent && _controller.value.isPlaying) {
        await _controller.pause();
      }
      if (mounted && revision != _playbackRevision) {
        await _syncPlayback(_playbackRevision);
      }
    } on Object {
      if (mounted && revision == _playbackRevision) {
        setState(() => _failed = true);
      }
    }
  }

  @override
  void dispose() {
    _playbackRevision += 1;
    _controller.removeListener(_handleControllerChanged);
    unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    late final Widget content;
    if (_failed) {
      content = const Center(
        child: Icon(
          Icons.broken_image_outlined,
          color: Colors.white54,
          size: 42,
        ),
      );
    } else if (!_initialized) {
      content = Stack(
        fit: StackFit.expand,
        children: [
          _MediaPlaceholder(thumbnailPath: widget.thumbnailPath),
          const Center(child: CircularProgressIndicator(color: Colors.white)),
        ],
      );
    } else {
      content = Stack(
        alignment: Alignment.center,
        children: [
          Center(
            child: AspectRatio(
              aspectRatio: widget.width / widget.height,
              child: VideoPlayer(_controller),
            ),
          ),
          if (!_isPlaying)
            const Icon(Icons.play_circle_fill, color: Colors.white, size: 54),
        ],
      );
    }

    return GestureDetector(
      key: ValueKey('video-${widget.file.path}'),
      behavior: HitTestBehavior.opaque,
      onTap: !_initialized || _failed
          ? null
          : () async {
              if (!widget.isCurrent) return;
              if (_isPlaying) {
                await _controller.pause();
              } else {
                await _controller.play();
              }
            },
      child: content,
    );
  }
}

class _MetaItem extends StatelessWidget {
  const _MetaItem({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16, color: AppTheme.muted),
        const SizedBox(width: 5),
        Text(
          label,
          style: const TextStyle(color: AppTheme.muted, fontSize: 13),
        ),
      ],
    );
  }
}

String _formatDate(int timestampMs) {
  final date = DateTime.fromMillisecondsSinceEpoch(timestampMs).toLocal();
  final month = date.month.toString().padLeft(2, '0');
  final day = date.day.toString().padLeft(2, '0');
  return '${date.year}-$month-$day';
}

String _messageFor(Object error) {
  if (error is PlatformException) return error.message ?? '操作失败，请重试';
  final message = error.toString();
  return message
      .replaceFirst('Bad state: ', '')
      .replaceFirst('Exception: ', '');
}
