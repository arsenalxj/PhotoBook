import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../controllers/providers.dart';
import '../controllers/update_controller.dart';
import '../core/theme/app_theme.dart';

class UpdateDialog extends ConsumerWidget {
  const UpdateDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(
      updateControllerProvider.select((controller) => controller.state),
    );
    final controller = ref.read(updateControllerProvider);
    final canClose = switch (state.status) {
      UpdateStatus.downloading || UpdateStatus.installing => false,
      _ => true,
    };

    return PopScope(
      canPop: canClose,
      child: Dialog(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 320, maxHeight: 420),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '应用更新',
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 12),
                Flexible(
                  child: SingleChildScrollView(
                    child: _content(context, controller, state),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _content(
    BuildContext context,
    UpdateController controller,
    UpdateState state,
  ) {
    void close() {
      controller.reset();
      Navigator.of(context).pop();
    }

    final manifest = state.manifest;
    return switch (state.status) {
      UpdateStatus.available => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${manifest?.versionName ?? ''} (${manifest?.versionCode ?? ''})',
            style: const TextStyle(
              fontFamily: 'monospace',
              fontSize: 14,
              fontWeight: FontWeight.w700,
            ),
          ),
          if (manifest?.releaseNotes.trim().isNotEmpty == true) ...[
            const SizedBox(height: 8),
            ...manifest!.releaseNotes
                .trim()
                .split('\n')
                .where((line) => line.trim().isNotEmpty)
                .map(
                  (line) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text(
                      '• ${line.trim().replaceFirst(RegExp(r'^[-*•]\s*'), '')}',
                      style: const TextStyle(fontSize: 14, height: 1.5),
                    ),
                  ),
                ),
          ],
          const SizedBox(height: 16),
          _DialogActions(
            secondaryLabel: '稍后',
            onSecondary: close,
            primaryLabel: '下载更新',
            onPrimary: controller.download,
          ),
        ],
      ),
      UpdateStatus.downloading => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '正在安全下载 APK…',
            style: TextStyle(color: AppTheme.muted, fontSize: 14),
          ),
          const SizedBox(height: 12),
          ClipRRect(
            borderRadius: BorderRadius.circular(999),
            child: LinearProgressIndicator(
              minHeight: 8,
              value: state.downloadProgress,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: Text(
                  '${manifest?.versionName ?? ''} (${manifest?.versionCode ?? ''})',
                  style: const TextStyle(
                    color: AppTheme.muted,
                    fontFamily: 'monospace',
                    fontSize: 13,
                  ),
                ),
              ),
              Text(
                state.downloadProgress == null
                    ? '正在下载'
                    : '已下载 ${(state.downloadProgress! * 100).round()}%',
                style: const TextStyle(
                  color: AppTheme.muted,
                  fontFamily: 'monospace',
                  fontSize: 13,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              onPressed: () async {
                await controller.cancelDownload();
                if (context.mounted) Navigator.of(context).pop();
              },
              child: const Text('取消下载'),
            ),
          ),
        ],
      ),
      UpdateStatus.readyToInstall => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'APK 已通过大小和 SHA-256 校验。安装前还会核对包名、版本和签名证书。',
            style: TextStyle(color: AppTheme.muted, fontSize: 14, height: 1.5),
          ),
          const SizedBox(height: 16),
          _DialogActions(
            secondaryLabel: '稍后',
            onSecondary: close,
            primaryLabel: '安装',
            onPrimary: controller.install,
          ),
        ],
      ),
      UpdateStatus.awaitingPermission => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '请在系统页面允许 PhotoBook 安装未知应用，授权完成后返回本页面继续。',
            style: TextStyle(color: AppTheme.muted, fontSize: 14, height: 1.5),
          ),
          const SizedBox(height: 16),
          _DialogActions(
            secondaryLabel: '稍后',
            onSecondary: close,
            primaryLabel: '继续安装',
            onPrimary: controller.install,
          ),
        ],
      ),
      UpdateStatus.installing => const Padding(
        padding: EdgeInsets.symmetric(vertical: 12),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox.square(
                dimension: 26,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
              SizedBox(height: 12),
              Text(
                '请在 Android 系统安装页面确认更新。',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 14),
              ),
            ],
          ),
        ),
      ),
      UpdateStatus.failed => Column(
        children: [
          DecoratedBox(
            decoration: BoxDecoration(
              color: AppTheme.danger.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
            ),
            child: const SizedBox.square(
              dimension: 48,
              child: Icon(LucideIcons.x, color: AppTheme.danger, size: 24),
            ),
          ),
          const SizedBox(height: 12),
          const Text(
            '更新失败',
            style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          Text(
            state.errorMessage ?? '更新操作失败，请稍后重试',
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: AppTheme.muted,
              fontSize: 14,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 16),
          _DialogActions(
            secondaryLabel: '关闭',
            onSecondary: close,
            primaryLabel: state.manifest == null ? null : '重试',
            onPrimary: state.manifest == null
                ? null
                : state.localPath == null
                ? controller.download
                : controller.install,
          ),
        ],
      ),
      _ => const SizedBox.shrink(),
    };
  }
}

class _DialogActions extends StatelessWidget {
  const _DialogActions({
    required this.secondaryLabel,
    required this.onSecondary,
    this.primaryLabel,
    this.onPrimary,
  });

  final String secondaryLabel;
  final VoidCallback onSecondary;
  final String? primaryLabel;
  final VoidCallback? onPrimary;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: OutlinedButton(
          onPressed: onSecondary,
          child: Text(secondaryLabel),
        ),
      ),
      if (primaryLabel != null) ...[
        const SizedBox(width: 8),
        Expanded(
          child: FilledButton(onPressed: onPrimary, child: Text(primaryLabel!)),
        ),
      ],
    ],
  );
}
