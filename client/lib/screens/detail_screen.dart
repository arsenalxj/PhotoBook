import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:video_player/video_player.dart';

import '../controllers/providers.dart';
import '../core/database/app_database.dart';
import '../core/theme/app_theme.dart';
import '../models/post.dart';
import '../services/archive_runtime_bridge.dart';
import '../widgets/post_action_sheets.dart';
import '../widgets/post_backup_indicator.dart';
import 'r2_settings_screen.dart';

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
        appBar: AppBar(leading: _detailBackButton(context)),
        body: const _MissingPost(),
      );
    }
    final displayName = post.authorDisplayName.trim();
    final authorName = displayName.isNotEmpty
        ? displayName
        : post.sourcePlatform == PostSourcePlatform.instagram
        ? '@${post.authorUsername}'
        : post.authorUsername;
    final currentMedia = _resolveCurrentMedia(post);

    return Scaffold(
      appBar: AppBar(
        leading: _detailBackButton(context),
        title: Text(
          authorName,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
        actions: [
          IconButton(
            key: const ValueKey('share-post-media'),
            tooltip: '分享媒体',
            onPressed: () => _sharePost(post!, currentMedia.id),
            icon: const Icon(LucideIcons.share),
          ),
          IconButton(
            key: const ValueKey('save-post-media'),
            tooltip: '保存媒体',
            onPressed: () => _savePostMedia(post!),
            icon: const Icon(LucideIcons.download),
          ),
          IconButton(
            key: const ValueKey('backup-post'),
            tooltip: '备份帖子',
            onPressed: () => _showBackupTargets(post!),
            icon: const Icon(LucideIcons.cloudUpload),
          ),
          PopupMenuButton<_DetailMenuAction>(
            tooltip: '更多',
            icon: const Icon(LucideIcons.ellipsisVertical),
            onSelected: (action) {
              final sourceUrl = post!.sourceUrl;
              switch (action) {
                case _DetailMenuAction.copySource:
                  unawaited(_copySource(sourceUrl));
                case _DetailMenuAction.openSource:
                  unawaited(_openSource(sourceUrl));
                case _DetailMenuAction.deleteMedia:
                  unawaited(_deletePostMedia(post));
              }
            },
            itemBuilder: (context) => const [
              PopupMenuItem(
                value: _DetailMenuAction.copySource,
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(LucideIcons.copy),
                  title: Text('复制链接'),
                ),
              ),
              PopupMenuItem(
                value: _DetailMenuAction.openSource,
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(LucideIcons.externalLink),
                  title: Text('打开原帖'),
                ),
              ),
              PopupMenuDivider(),
              PopupMenuItem(
                key: ValueKey('delete-post-media'),
                value: _DetailMenuAction.deleteMedia,
                child: ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(LucideIcons.trash, color: AppTheme.danger),
                  title: Text('删除媒体', style: TextStyle(color: AppTheme.danger)),
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
                      icon: LucideIcons.calendarDays,
                      label: _formatDate(post.publishedAt),
                    ),
                    if (post.locationName != null)
                      _MetaItem(
                        icon: LucideIcons.mapPin,
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

  Future<void> _showBackupTargets(ArchivedPost post) =>
      showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (_) => _ManualBackupSheet(post: post),
      );

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

  Future<void> _copySource(String sourceUrl) async {
    try {
      await Clipboard.setData(ClipboardData(text: sourceUrl));
      if (!mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(const SnackBar(content: Text('链接已复制')));
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('链接复制失败，请重试')));
    }
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

enum _DetailMenuAction { copySource, openSource, deleteMedia }

class _ManualBackupSheet extends ConsumerStatefulWidget {
  const _ManualBackupSheet({required this.post});

  final ArchivedPost post;

  @override
  ConsumerState<_ManualBackupSheet> createState() => _ManualBackupSheetState();
}

class _ManualBackupSheetState extends ConsumerState<_ManualBackupSheet> {
  Map<String, BackupTargetStatus> _statuses = const {};
  String? _activeTargetId;
  String? _loadError;
  bool _loading = true;
  int? _seenRevision;

  @override
  void initState() {
    super.initState();
    unawaited(_loadStatuses());
  }

  Future<void> _loadStatuses() async {
    try {
      final statuses = await ref
          .read(appControllerProvider)
          .readBackupTargetStatuses(widget.post.id);
      if (!mounted) return;
      setState(() {
        _statuses = statuses;
        _loadError = null;
        _loading = false;
      });
    } on Object catch (error) {
      if (!mounted) return;
      setState(() {
        _loadError = _messageFor(error);
        _loading = false;
      });
    }
  }

  Future<void> _enqueue(R2BackupTargetSummary target) async {
    if (_activeTargetId != null ||
        _statuses[target.targetId]?.state == BackupTargetState.completed) {
      return;
    }
    setState(() {
      _activeTargetId = target.targetId;
      _loadError = null;
    });
    try {
      final status = await ref
          .read(appControllerProvider)
          .enqueueR2Backup(widget.post.id, target.targetId);
      if (!mounted) return;
      setState(() {
        _statuses = {
          ..._statuses,
          target.targetId: BackupTargetStatus(
            state: status == ManualBackupEnqueueStatus.completed
                ? BackupTargetState.completed
                : BackupTargetState.pending,
          ),
        };
      });
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            status == ManualBackupEnqueueStatus.completed
                ? '该帖子已备份到${target.name}'
                : '已开始备份到${target.name}',
          ),
        ),
      );
    } on Object catch (error) {
      if (!mounted) return;
      setState(() => _loadError = _messageFor(error));
    } finally {
      if (mounted) setState(() => _activeTargetId = null);
    }
  }

  void _manageTargets() {
    final navigator = Navigator.of(context);
    navigator.pop();
    unawaited(
      navigator.push<void>(
        MaterialPageRoute(builder: (_) => const R2SettingsScreen()),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(appControllerProvider);
    final revision = controller.backupRevision;
    if (_seenRevision == null) {
      _seenRevision = revision;
    } else if (_seenRevision != revision) {
      _seenRevision = revision;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_loadStatuses());
      });
    }
    final settings = controller.r2Settings;
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.82,
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const _BackupSheetHandle(),
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      '备份到',
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭',
                    onPressed: _activeTargetId == null
                        ? () => Navigator.of(context).pop()
                        : null,
                    icon: const Icon(LucideIcons.x),
                  ),
                ],
              ),
              const Text(
                '选择一个位置备份当前整帖。任务创建后会在后台继续。',
                style: TextStyle(color: AppTheme.muted, fontSize: 13),
              ),
              const SizedBox(height: 10),
              if (_loading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 28),
                  child: Center(child: CircularProgressIndicator()),
                )
              else if (settings.targets.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 20),
                  child: Column(
                    children: [
                      const Icon(LucideIcons.cloudOff, size: 34),
                      const SizedBox(height: 10),
                      const Text('还没有可用的备份位置'),
                      const SizedBox(height: 14),
                      FilledButton(
                        onPressed: _manageTargets,
                        child: const Text('添加备份位置'),
                      ),
                    ],
                  ),
                )
              else
                Flexible(
                  child: ListView(
                    shrinkWrap: true,
                    children: [
                      for (final connection in settings.connections) ...[
                        if (settings.targets.any(
                          (target) =>
                              target.connectionId == connection.connectionId,
                        )) ...[
                          Padding(
                            padding: const EdgeInsets.fromLTRB(2, 10, 2, 6),
                            child: Text(
                              connection.bucket,
                              style: const TextStyle(
                                color: AppTheme.muted,
                                fontFamily: 'monospace',
                                fontSize: 11,
                              ),
                            ),
                          ),
                          for (final target in settings.targets.where(
                            (target) =>
                                target.connectionId == connection.connectionId,
                          ))
                            _BackupTargetTile(
                              target: target,
                              status: _statuses[target.targetId],
                              busy: _activeTargetId == target.targetId,
                              enabled: _activeTargetId == null,
                              onTap: () => _enqueue(target),
                            ),
                        ],
                      ],
                    ],
                  ),
                ),
              if (_loadError != null) ...[
                const SizedBox(height: 10),
                Text(
                  _loadError!,
                  style: const TextStyle(color: AppTheme.danger, fontSize: 13),
                ),
              ],
              if (settings.targets.isNotEmpty) ...[
                const SizedBox(height: 8),
                TextButton(
                  onPressed: _activeTargetId == null ? _manageTargets : null,
                  child: const Text('管理备份位置'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _BackupTargetTile extends StatelessWidget {
  const _BackupTargetTile({
    required this.target,
    required this.status,
    required this.busy,
    required this.enabled,
    required this.onTap,
  });

  final R2BackupTargetSummary target;
  final BackupTargetStatus? status;
  final bool busy;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final state = status?.state ?? BackupTargetState.notBackedUp;
    final completed = state == BackupTargetState.completed;
    final failed = state == BackupTargetState.failed;
    final stateColor = completed
        ? AppTheme.success
        : failed
        ? AppTheme.danger
        : AppTheme.muted;
    final label = switch (state) {
      BackupTargetState.notBackedUp => '未备份',
      BackupTargetState.pending => '等待或备份中',
      BackupTargetState.completed => '已备份',
      BackupTargetState.failed => '备份失败',
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppTheme.foreground,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          minimumSize: const Size.fromHeight(62),
          alignment: Alignment.centerLeft,
        ),
        onPressed: enabled && !completed ? onTap : null,
        child: Row(
          children: [
            const SizedBox.square(
              dimension: 36,
              child: Icon(LucideIcons.cloud, size: 18),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    target.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  Text(
                    '${target.bucket} / ${target.prefix}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppTheme.muted,
                      fontFamily: 'monospace',
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            if (busy)
              const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            else
              Text(label, style: TextStyle(color: stateColor, fontSize: 12)),
          ],
        ),
      ),
    );
  }
}

class _BackupSheetHandle extends StatelessWidget {
  const _BackupSheetHandle();

  @override
  Widget build(BuildContext context) => Center(
    child: Container(
      width: 36,
      height: 4,
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: AppTheme.border,
        borderRadius: BorderRadius.circular(999),
      ),
    ),
  );
}

class _MissingPost extends StatelessWidget {
  const _MissingPost();

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DecoratedBox(
            decoration: BoxDecoration(
              border: Border.all(color: AppTheme.border),
              borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
            ),
            child: const SizedBox.square(
              dimension: 56,
              child: Icon(
                LucideIcons.zoomOut,
                size: 26,
                color: AppTheme.foreground,
              ),
            ),
          ),
          const SizedBox(height: 12),
          const Text(
            '帖子不存在',
            style: TextStyle(color: AppTheme.muted, fontSize: 14),
          ),
          const SizedBox(height: 12),
          const Text(
            '该帖子可能已从本机删除。',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppTheme.muted, fontSize: 13),
          ),
        ],
      ),
    ),
  );
}

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
    final hasAvatar = avatar != null && avatar.existsSync();
    final avatarSource = post.authorDisplayName.trim().isEmpty
        ? post.authorUsername.trim()
        : post.authorDisplayName.trim();
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        children: [
          CircleAvatar(
            radius: 20,
            backgroundColor: AppTheme.accent,
            foregroundColor: AppTheme.accentOn,
            foregroundImage: hasAvatar ? FileImage(avatar) : null,
            child: hasAvatar
                ? null
                : Text(
                    avatarSource.isEmpty ? '?' : avatarSource.characters.first,
                    style: const TextStyle(
                      color: AppTheme.accentOn,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
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
                  style: const TextStyle(
                    color: AppTheme.muted,
                    fontFamily: 'monospace',
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
          if (post.backupState != PostBackupState.notBackedUp) ...[
            const SizedBox(width: 12),
            PostBackupIndicator(
              state: post.backupState,
              dimension: 28,
              iconSize: 16,
              backgroundColor: AppTheme.foreground,
              foregroundColor: AppTheme.background,
              progressKey: const ValueKey('detail-backup-progress'),
              successKey: const ValueKey('detail-backup-success'),
            ),
          ],
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
      color: AppTheme.accent,
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
                  color: AppTheme.accent.withValues(alpha: 0.62),
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 4,
                  ),
                  child: Text(
                    '${(currentIndex < 0 ? 0 : currentIndex) + 1}/${widget.post.media.length}',
                    style: const TextStyle(
                      color: AppTheme.accentOn,
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
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
                    if (media.hasLiveMotion) ...[
                      Positioned(
                        left: 12,
                        top: 12,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: AppTheme.accent.withValues(alpha: 0.62),
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: const Padding(
                            padding: EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 5,
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  LucideIcons.radio,
                                  color: AppTheme.accentOn,
                                  size: 15,
                                ),
                                SizedBox(width: 6),
                                Text(
                                  'Live Photo',
                                  style: TextStyle(
                                    color: AppTheme.accentOn,
                                    fontFamily: 'monospace',
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                      Positioned(
                        bottom: 14,
                        left: 0,
                        right: 0,
                        child: Center(
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: AppTheme.accent.withValues(alpha: 0.62),
                              borderRadius: BorderRadius.circular(999),
                            ),
                            child: const Padding(
                              padding: EdgeInsets.symmetric(
                                horizontal: 14,
                                vertical: 6,
                              ),
                              child: Text(
                                '长按播放动态部分',
                                style: TextStyle(
                                  color: AppTheme.accentOn,
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
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
          const ColoredBox(
            color: AppTheme.accent,
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox.square(
                    dimension: 28,
                    child: CircularProgressIndicator(
                      strokeWidth: 3,
                      color: AppTheme.accentOn,
                    ),
                  ),
                  SizedBox(height: 14),
                  Text(
                    '正在下载原图…',
                    style: TextStyle(color: AppTheme.accentOn, fontSize: 14),
                  ),
                ],
              ),
            ),
          ),
        if (isCurrent && _errors.containsKey(media.id))
          ColoredBox(
            color: AppTheme.accent,
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: AppTheme.accentOn.withValues(alpha: 0.28),
                      ),
                      borderRadius: BorderRadius.circular(
                        AppTheme.radiusMedium,
                      ),
                    ),
                    child: const SizedBox.square(
                      dimension: 56,
                      child: Icon(
                        LucideIcons.imageOff,
                        color: AppTheme.accentOn,
                        size: 26,
                      ),
                    ),
                  ),
                  const SizedBox(height: 14),
                  const Text(
                    '原图下载失败，请检查网络后重试',
                    style: TextStyle(color: AppTheme.accentOn, fontSize: 14),
                  ),
                  const SizedBox(height: 14),
                  SizedBox.square(
                    dimension: 64,
                    child: OutlinedButton(
                      onPressed: () => _ensureMedia(media.id),
                      style: OutlinedButton.styleFrom(
                        foregroundColor: AppTheme.accentOn,
                        side: const BorderSide(color: AppTheme.accentOn),
                        shape: const CircleBorder(),
                        padding: EdgeInsets.zero,
                      ),
                      child: const Icon(LucideIcons.rotateCw, size: 24),
                    ),
                  ),
                ],
              ),
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
      return Center(
        child: Icon(
          LucideIcons.image,
          color: AppTheme.accentOn.withValues(alpha: 0.55),
          size: 42,
        ),
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
      content = Center(
        child: Icon(
          LucideIcons.imageOff,
          color: AppTheme.accentOn.withValues(alpha: 0.55),
          size: 42,
        ),
      );
    } else if (!_initialized) {
      content = Stack(
        fit: StackFit.expand,
        children: [
          _MediaPlaceholder(thumbnailPath: widget.thumbnailPath),
          const Center(
            child: CircularProgressIndicator(color: AppTheme.accentOn),
          ),
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
            const Icon(
              LucideIcons.circlePlay,
              color: AppTheme.accentOn,
              size: 54,
            ),
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

Widget _detailBackButton(BuildContext context) => IconButton(
  tooltip: MaterialLocalizations.of(context).backButtonTooltip,
  onPressed: () => Navigator.maybePop(context),
  icon: const Icon(LucideIcons.chevronLeft),
);

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
