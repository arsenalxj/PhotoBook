import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../core/theme/app_theme.dart';
import '../models/post.dart';

class MediaDeleteSelectionOutcome {
  const MediaDeleteSelectionOutcome({
    required this.deletedMediaIds,
    required this.postDeleted,
  });

  final Set<String> deletedMediaIds;
  final bool postDeleted;
}

Future<bool> showDeletePostSheet({
  required BuildContext context,
  required ArchivedPost post,
  required Future<void> Function() onDelete,
}) async {
  final deleted = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    builder: (sheetContext) {
      var isDeleting = false;
      String? errorMessage;
      return StatefulBuilder(
        builder: (context, setState) => PopScope(
          canPop: !isDeleting,
          child: SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const _SheetHandle(),
                  const SizedBox(height: 8),
                  const Text(
                    '删除这条帖子?',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    post.sourcePlatform == PostSourcePlatform.instagram
                        ? '${post.authorDisplayName}  ·  @${post.authorUsername}'
                        : '${post.authorDisplayName}  ·  小红书',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppTheme.muted,
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    '帖子只会从本机移除，R2 中已经备份的内容和系统相册副本不受影响。',
                    style: TextStyle(height: 1.45),
                  ),
                  if (errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      errorMessage!,
                      style: const TextStyle(color: AppTheme.danger),
                    ),
                  ],
                  const SizedBox(height: 20),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: isDeleting
                              ? null
                              : () => Navigator.of(sheetContext).pop(false),
                          child: const Text('取消'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: FilledButton(
                          key: const ValueKey('confirm-delete-post'),
                          style: FilledButton.styleFrom(
                            backgroundColor: AppTheme.danger,
                            foregroundColor: AppTheme.accentOn,
                            minimumSize: const Size.fromHeight(48),
                          ),
                          onPressed: isDeleting
                              ? null
                              : () async {
                                  setState(() {
                                    isDeleting = true;
                                    errorMessage = null;
                                  });
                                  try {
                                    await onDelete();
                                    if (sheetContext.mounted) {
                                      Navigator.of(sheetContext).pop(true);
                                    }
                                  } on Object catch (error) {
                                    if (!context.mounted) return;
                                    setState(() {
                                      isDeleting = false;
                                      errorMessage = _messageFor(error);
                                    });
                                  }
                                },
                          child: isDeleting
                              ? const Row(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    SizedBox.square(
                                      dimension: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: AppTheme.accentOn,
                                      ),
                                    ),
                                    SizedBox(width: 8),
                                    Text('正在删除'),
                                  ],
                                )
                              : const Text('删除帖子'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
  return deleted ?? false;
}

Future<int?> showSaveMediaSelectionSheet({
  required BuildContext context,
  required ArchivedPost post,
  required Future<void> Function(PostMedia media, MediaExportMode exportMode)
  onSave,
}) => showModalBottomSheet<int>(
  context: context,
  isScrollControlled: true,
  isDismissible: false,
  enableDrag: false,
  builder: (sheetContext) {
    final selectedIds = post.media.map((item) => item.id).toSet();
    final savedIds = <String>{};
    var liveExportMode = MediaExportMode.staticImage;
    var isSaving = false;
    var progressCurrent = 0;
    var progressTotal = 0;
    String? errorMessage;
    return StatefulBuilder(
      builder: (context, setState) {
        final selectableIds = post.media
            .where((item) => !savedIds.contains(item.id))
            .map((item) => item.id)
            .toSet();
        final allSelected =
            selectableIds.isNotEmpty && selectedIds.containsAll(selectableIds);
        final selectedMedia = post.media
            .where((item) => selectedIds.contains(item.id))
            .toList(growable: false);
        final hasSelectedLivePhoto = selectedMedia.any(
          (item) => item.isLivePhoto && item.hasLiveMotion,
        );

        void toggleAll() {
          if (isSaving || selectableIds.isEmpty) return;
          setState(() {
            errorMessage = null;
            if (allSelected) {
              selectedIds.clear();
            } else {
              selectedIds.addAll(selectableIds);
            }
          });
        }

        return PopScope(
          canPop: !isSaving,
          child: SafeArea(
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                16,
                10,
                16,
                16 + MediaQuery.viewInsetsOf(context).bottom,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const _SheetHandle(),
                  const SizedBox(height: 8),
                  _SelectionSheetHeader(
                    title: '保存到系统相册',
                    selectedCount: selectedIds.length,
                    totalCount: post.media.length,
                    allSelected: allSelected,
                    enabled: !isSaving && selectableIds.isNotEmpty,
                    selectAllKey: const ValueKey('save-select-all'),
                    closeKey: const ValueKey('close-save-sheet'),
                    onToggleAll: toggleAll,
                    onClose: () => Navigator.of(
                      sheetContext,
                    ).pop(savedIds.isEmpty ? null : savedIds.length),
                  ),
                  const SizedBox(height: 12),
                  _MediaSelectionGrid(
                    keyPrefix: 'save',
                    post: post,
                    selectedIds: selectedIds,
                    completedIds: savedIds,
                    enabled: !isSaving,
                    onToggle: (media) {
                      if (savedIds.contains(media.id)) return;
                      setState(() {
                        errorMessage = null;
                        if (!selectedIds.add(media.id)) {
                          selectedIds.remove(media.id);
                        }
                      });
                    },
                  ),
                  if (hasSelectedLivePhoto) ...[
                    const SizedBox(height: 16),
                    const Text(
                      'Live Photo 保存方式',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 8),
                    _LiveExportModeSelector(
                      selected: liveExportMode,
                      enabled: !isSaving,
                      onChanged: (mode) =>
                          setState(() => liveExportMode = mode),
                    ),
                  ],
                  if (errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      errorMessage!,
                      style: const TextStyle(color: AppTheme.danger),
                    ),
                  ],
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    key: const ValueKey('save-selected-media'),
                    onPressed: selectedMedia.isEmpty || isSaving
                        ? null
                        : () async {
                            setState(() {
                              isSaving = true;
                              progressCurrent = 0;
                              progressTotal = selectedMedia.length;
                              errorMessage = null;
                            });
                            final failures = <String, Object>{};
                            for (final media in selectedMedia) {
                              final exportMode = media.isLivePhoto
                                  ? media.hasLiveMotion
                                        ? liveExportMode
                                        : MediaExportMode.staticImage
                                  : MediaExportMode.original;
                              try {
                                await onSave(media, exportMode);
                                savedIds.add(media.id);
                              } on Object catch (error) {
                                failures[media.id] = error;
                              }
                              if (!context.mounted) return;
                              setState(() => progressCurrent += 1);
                            }
                            if (!context.mounted) return;
                            if (failures.isEmpty) {
                              Navigator.of(sheetContext).pop(savedIds.length);
                              return;
                            }
                            setState(() {
                              isSaving = false;
                              selectedIds
                                ..clear()
                                ..addAll(failures.keys);
                              errorMessage =
                                  '已保存 ${savedIds.length} 项，${failures.length} 项失败。'
                                  '失败项已保留，可重试。\n'
                                  '${_messageFor(failures.values.first)}';
                            });
                          },
                    icon: isSaving
                        ? const SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppTheme.accentOn,
                            ),
                          )
                        : const Icon(LucideIcons.download),
                    label: Text(
                      isSaving
                          ? '正在保存 $progressCurrent/$progressTotal'
                          : '保存 ${selectedIds.length} 项',
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  },
);

Future<MediaDeleteSelectionOutcome?> showDeleteMediaSelectionSheet({
  required BuildContext context,
  required ArchivedPost post,
  required Future<bool> Function(List<PostMedia> media) onDelete,
}) => showModalBottomSheet<MediaDeleteSelectionOutcome>(
  context: context,
  isScrollControlled: true,
  isDismissible: false,
  enableDrag: false,
  builder: (sheetContext) {
    final selectedIds = post.media.map((item) => item.id).toSet();
    var isDeleting = false;
    String? errorMessage;
    return StatefulBuilder(
      builder: (context, setState) {
        final allSelected = selectedIds.length == post.media.length;
        final selectedMedia = post.media
            .where((item) => selectedIds.contains(item.id))
            .toList(growable: false);

        return PopScope(
          canPop: !isDeleting,
          child: SafeArea(
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                16,
                10,
                16,
                16 + MediaQuery.viewInsetsOf(context).bottom,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const _SheetHandle(),
                  const SizedBox(height: 8),
                  _SelectionSheetHeader(
                    title: '删除媒体',
                    selectedCount: selectedIds.length,
                    totalCount: post.media.length,
                    allSelected: allSelected,
                    enabled: !isDeleting,
                    selectAllKey: const ValueKey('delete-select-all'),
                    closeKey: const ValueKey('close-delete-sheet'),
                    onToggleAll: () => setState(() {
                      errorMessage = null;
                      if (allSelected) {
                        selectedIds.clear();
                      } else {
                        selectedIds.addAll(post.media.map((item) => item.id));
                      }
                    }),
                    onClose: () => Navigator.of(sheetContext).pop(),
                  ),
                  const SizedBox(height: 12),
                  _MediaSelectionGrid(
                    keyPrefix: 'delete',
                    post: post,
                    selectedIds: selectedIds,
                    enabled: !isDeleting,
                    onToggle: (media) => setState(() {
                      errorMessage = null;
                      if (!selectedIds.add(media.id)) {
                        selectedIds.remove(media.id);
                      }
                    }),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    allSelected && selectedIds.isNotEmpty
                        ? '将只从本机删除整条帖子，R2 备份不会删除。'
                        : '将只从本机删除选中的 ${selectedIds.length} 项媒体，R2 备份不会删除。',
                    style: const TextStyle(color: AppTheme.muted, height: 1.4),
                  ),
                  if (errorMessage != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      errorMessage!,
                      style: const TextStyle(color: AppTheme.danger),
                    ),
                  ],
                  const SizedBox(height: 16),
                  FilledButton.icon(
                    key: const ValueKey('delete-selected-media'),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppTheme.danger,
                      foregroundColor: AppTheme.accentOn,
                    ),
                    onPressed: selectedMedia.isEmpty || isDeleting
                        ? null
                        : () async {
                            setState(() {
                              isDeleting = true;
                              errorMessage = null;
                            });
                            try {
                              final postDeleted = await onDelete(selectedMedia);
                              if (sheetContext.mounted) {
                                Navigator.of(sheetContext).pop(
                                  MediaDeleteSelectionOutcome(
                                    deletedMediaIds: selectedIds.toSet(),
                                    postDeleted: postDeleted,
                                  ),
                                );
                              }
                            } on Object catch (error) {
                              if (!context.mounted) return;
                              setState(() {
                                isDeleting = false;
                                errorMessage = _messageFor(error);
                              });
                            }
                          },
                    icon: isDeleting
                        ? const SizedBox.square(
                            dimension: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: AppTheme.accentOn,
                            ),
                          )
                        : const Icon(LucideIcons.trash),
                    label: Text(
                      isDeleting
                          ? '正在删除'
                          : allSelected && selectedIds.isNotEmpty
                          ? '删除帖子'
                          : '删除 ${selectedIds.length} 项',
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  },
);

Future<void> showShareMediaSheet({
  required BuildContext context,
  required ArchivedPost post,
  required String initialMediaId,
  required Future<void> Function(List<PostMedia>, MediaExportMode exportMode)
  onShare,
}) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    isDismissible: false,
    enableDrag: false,
    builder: (sheetContext) {
      final selectedIds = <String>{initialMediaId};
      var liveExportMode = MediaExportMode.staticImage;
      var isSharing = false;
      String? errorMessage;
      return StatefulBuilder(
        builder: (context, setState) {
          void toggle(PostMedia media) {
            if (isSharing) return;
            setState(() {
              errorMessage = null;
              if (media.mediaType == PostMediaType.video || media.isLivePhoto) {
                selectedIds
                  ..clear()
                  ..add(media.id);
                return;
              }
              final selectedVideo = post.media.any(
                (item) =>
                    selectedIds.contains(item.id) &&
                    (item.mediaType == PostMediaType.video || item.isLivePhoto),
              );
              if (selectedVideo) selectedIds.clear();
              if (!selectedIds.add(media.id)) selectedIds.remove(media.id);
            });
          }

          final selectedMedia = post.media
              .where((item) => selectedIds.contains(item.id))
              .toList(growable: false);
          final selectedLive =
              selectedMedia.length == 1 && selectedMedia.single.isLivePhoto
              ? selectedMedia.single
              : null;

          return PopScope(
            canPop: !isSharing,
            child: SafeArea(
              child: SingleChildScrollView(
                padding: EdgeInsets.fromLTRB(
                  16,
                  10,
                  16,
                  16 + MediaQuery.viewInsetsOf(context).bottom,
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const _SheetHandle(),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        const Expanded(
                          child: Text(
                            '分享媒体',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                        IconButton(
                          key: const ValueKey('close-share-sheet'),
                          tooltip: '关闭',
                          onPressed: isSharing
                              ? null
                              : () => Navigator.of(sheetContext).pop(),
                          icon: const Icon(LucideIcons.x),
                        ),
                      ],
                    ),
                    const Text(
                      '图片可多选，视频每次选择一个',
                      style: TextStyle(color: AppTheme.muted),
                    ),
                    const SizedBox(height: 16),
                    GridView.builder(
                      shrinkWrap: true,
                      physics: const NeverScrollableScrollPhysics(),
                      gridDelegate:
                          const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 3,
                            mainAxisSpacing: 8,
                            crossAxisSpacing: 8,
                          ),
                      itemCount: post.media.length,
                      itemBuilder: (context, index) {
                        final media = post.media[index];
                        final selected = selectedIds.contains(media.id);
                        return _MediaChoice(
                          key: ValueKey('share-${media.id}'),
                          media: media,
                          selected: selected,
                          onTap: () => toggle(media),
                        );
                      },
                    ),
                    if (selectedLive?.hasLiveMotion == true) ...[
                      const SizedBox(height: 16),
                      _LiveExportModeSelector(
                        selected: liveExportMode,
                        enabled: !isSharing,
                        onChanged: (mode) =>
                            setState(() => liveExportMode = mode),
                      ),
                    ],
                    if (errorMessage != null) ...[
                      const SizedBox(height: 12),
                      Text(
                        errorMessage!,
                        style: const TextStyle(color: AppTheme.danger),
                      ),
                    ],
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      key: const ValueKey('share-selected-media'),
                      onPressed: selectedIds.isEmpty || isSharing
                          ? null
                          : () async {
                              setState(() {
                                isSharing = true;
                                errorMessage = null;
                              });
                              try {
                                final exportMode = selectedLive == null
                                    ? MediaExportMode.original
                                    : selectedLive.hasLiveMotion
                                    ? liveExportMode
                                    : MediaExportMode.staticImage;
                                await onShare(selectedMedia, exportMode);
                                if (sheetContext.mounted) {
                                  Navigator.of(sheetContext).pop();
                                }
                              } on Object catch (error) {
                                if (!context.mounted) return;
                                setState(() {
                                  isSharing = false;
                                  errorMessage = _messageFor(error);
                                });
                              }
                            },
                      icon: isSharing
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppTheme.accentOn,
                              ),
                            )
                          : const Icon(LucideIcons.share),
                      label: Text(
                        isSharing ? '正在准备媒体' : '分享 ${selectedIds.length} 项',
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      );
    },
  );
}

class _SelectionSheetHeader extends StatelessWidget {
  const _SelectionSheetHeader({
    required this.title,
    required this.selectedCount,
    required this.totalCount,
    required this.allSelected,
    required this.enabled,
    required this.selectAllKey,
    required this.closeKey,
    required this.onToggleAll,
    required this.onClose,
  });

  final String title;
  final int selectedCount;
  final int totalCount;
  final bool allSelected;
  final bool enabled;
  final Key selectAllKey;
  final Key closeKey;
  final VoidCallback onToggleAll;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Row(
        children: [
          Expanded(
            child: Text(
              title,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
            ),
          ),
          IconButton(
            key: closeKey,
            tooltip: '关闭',
            onPressed: enabled ? onClose : null,
            icon: const Icon(LucideIcons.x),
          ),
        ],
      ),
      Row(
        children: [
          Checkbox(
            key: selectAllKey,
            value: allSelected,
            onChanged: enabled ? (_) => onToggleAll() : null,
          ),
          InkWell(
            onTap: enabled ? onToggleAll : null,
            child: const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text('全选'),
            ),
          ),
          const Spacer(),
          Text(
            '$selectedCount/$totalCount',
            style: const TextStyle(color: AppTheme.muted),
          ),
        ],
      ),
    ],
  );
}

class _MediaSelectionGrid extends StatelessWidget {
  const _MediaSelectionGrid({
    required this.keyPrefix,
    required this.post,
    required this.selectedIds,
    required this.enabled,
    required this.onToggle,
    this.completedIds = const {},
  });

  final String keyPrefix;
  final ArchivedPost post;
  final Set<String> selectedIds;
  final Set<String> completedIds;
  final bool enabled;
  final ValueChanged<PostMedia> onToggle;

  @override
  Widget build(BuildContext context) => GridView.builder(
    shrinkWrap: true,
    physics: const NeverScrollableScrollPhysics(),
    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
      crossAxisCount: 3,
      mainAxisSpacing: 8,
      crossAxisSpacing: 8,
    ),
    itemCount: post.media.length,
    itemBuilder: (context, index) {
      final media = post.media[index];
      return _MediaChoice(
        key: ValueKey('$keyPrefix-${media.id}'),
        media: media,
        selected: selectedIds.contains(media.id),
        completed: completedIds.contains(media.id),
        onTap: enabled ? () => onToggle(media) : null,
      );
    },
  );
}

class _LiveExportModeSelector extends StatelessWidget {
  const _LiveExportModeSelector({
    required this.selected,
    required this.enabled,
    required this.onChanged,
  });

  final MediaExportMode selected;
  final bool enabled;
  final ValueChanged<MediaExportMode> onChanged;

  @override
  Widget build(BuildContext context) => SegmentedButton<MediaExportMode>(
    segments: const [
      ButtonSegment(value: MediaExportMode.staticImage, label: Text('静态图')),
      ButtonSegment(value: MediaExportMode.gif, label: Text('GIF')),
      ButtonSegment(value: MediaExportMode.video, label: Text('视频')),
    ],
    selected: {selected},
    style: ButtonStyle(
      visualDensity: VisualDensity.compact,
      side: const WidgetStatePropertyAll(BorderSide(color: AppTheme.border)),
      backgroundColor: WidgetStateProperty.resolveWith((states) {
        return states.contains(WidgetState.selected)
            ? AppTheme.accent
            : AppTheme.surface;
      }),
      foregroundColor: WidgetStateProperty.resolveWith((states) {
        return states.contains(WidgetState.selected)
            ? AppTheme.accentOn
            : AppTheme.foreground;
      }),
    ),
    onSelectionChanged: enabled
        ? (selection) => onChanged(selection.single)
        : null,
  );
}

class _MediaChoice extends StatelessWidget {
  const _MediaChoice({
    required this.media,
    required this.selected,
    required this.onTap,
    this.completed = false,
    super.key,
  });

  final PostMedia media;
  final bool selected;
  final bool completed;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final thumbnail = media.localThumbnailPath == null
        ? null
        : File(media.localThumbnailPath!);
    return Material(
      color: AppTheme.surface,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppTheme.radiusSmall),
        side: BorderSide(
          color: selected ? AppTheme.accent : AppTheme.border,
          width: selected ? 2 : 1,
        ),
      ),
      child: InkWell(
        onTap: onTap,
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (thumbnail != null && thumbnail.existsSync())
              Image.file(thumbnail, fit: BoxFit.cover)
            else
              const Icon(LucideIcons.image, color: AppTheme.muted),
            if (media.mediaType == PostMediaType.video)
              Center(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: AppTheme.accent.withValues(alpha: 0.78),
                    shape: BoxShape.circle,
                  ),
                  child: const SizedBox.square(
                    dimension: 34,
                    child: Icon(
                      LucideIcons.play,
                      color: AppTheme.accentOn,
                      size: 22,
                    ),
                  ),
                ),
              ),
            if (media.isLivePhoto)
              Positioned(
                left: 6,
                bottom: 6,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: AppTheme.accent.withValues(alpha: 0.78),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          LucideIcons.radio,
                          color: AppTheme.accentOn,
                          size: 12,
                        ),
                        SizedBox(width: 4),
                        Text(
                          'Live',
                          style: TextStyle(
                            color: AppTheme.accentOn,
                            fontSize: 10,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            if (completed)
              const Positioned(
                left: 6,
                top: 6,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: AppTheme.success,
                    borderRadius: BorderRadius.all(Radius.circular(999)),
                  ),
                  child: Padding(
                    padding: EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                    child: Text(
                      '已保存',
                      style: TextStyle(color: AppTheme.accentOn, fontSize: 10),
                    ),
                  ),
                ),
              ),
            Positioned(
              top: 6,
              right: 6,
              child: Icon(
                completed || selected
                    ? LucideIcons.circleCheck
                    : LucideIcons.circle,
                color: completed
                    ? AppTheme.success
                    : selected
                    ? AppTheme.accent
                    : AppTheme.accentOn,
                shadows: [
                  Shadow(
                    blurRadius: 4,
                    color: AppTheme.accent.withValues(alpha: 0.54),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SheetHandle extends StatelessWidget {
  const _SheetHandle();

  @override
  Widget build(BuildContext context) => Center(
    child: Container(
      width: 36,
      height: 4,
      decoration: BoxDecoration(
        color: AppTheme.divider,
        borderRadius: BorderRadius.circular(2),
      ),
    ),
  );
}

String _messageFor(Object error) {
  if (error is PlatformException) return error.message ?? '操作失败，请重试';
  final message = error.toString();
  return message
      .replaceFirst('Bad state: ', '')
      .replaceFirst('Exception: ', '');
}
