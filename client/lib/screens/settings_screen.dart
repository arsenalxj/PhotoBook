import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../controllers/providers.dart';
import '../controllers/update_controller.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';
import 'instagram_login_screen.dart';
import 'r2_settings_screen.dart';

export 'r2_settings_screen.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(appControllerProvider);
    final instagramSession = controller.instagramSession;
    final savedTargetCount = controller.r2Settings.targets.length;
    final hasBackupError = controller.backupStatus.lastError != null;
    final updateController = ref.watch(updateControllerProvider);
    final updateState = updateController.state;

    final instagramColor = switch (instagramSession?.status) {
      InstagramSessionStatus.ready => AppTheme.success,
      InstagramSessionStatus.needsRefresh => AppTheme.danger,
      null => AppTheme.foreground,
    };
    final instagramStatus = switch (instagramSession?.status) {
      InstagramSessionStatus.ready => '@${instagramSession!.username}',
      InstagramSessionStatus.needsRefresh =>
        '@${instagramSession!.username} · 登录已失效',
      null => '未登录',
    };
    final r2Color = hasBackupError ? AppTheme.danger : AppTheme.foreground;
    final r2Status = savedTargetCount == 0
        ? '未配置'
        : hasBackupError
        ? '备份失败'
        : '$savedTargetCount 个备份位置';

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _SettingsSection(
            title: 'Instagram',
            child: _SettingsRow(
              icon: LucideIcons.camera,
              iconColor: instagramColor,
              title: 'Instagram 账号',
              subtitle: instagramStatus,
              subtitleColor: instagramColor,
              monoSubtitle:
                  instagramSession?.status == InstagramSessionStatus.ready,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const InstagramSettingsScreen(),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          _SettingsSection(
            title: '云备份',
            child: _SettingsRow(
              icon: hasBackupError ? LucideIcons.cloudOff : LucideIcons.cloud,
              iconColor: r2Color,
              title: 'Cloudflare R2',
              subtitle: r2Status,
              subtitleColor: r2Color,
              monoSubtitle: false,
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const R2SettingsScreen(),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16),
          _SettingsSection(
            title: '关于',
            child: _SettingsRow(
              icon: LucideIcons.rotateCw,
              title: '检查更新',
              subtitle: _updateSubtitle(updateState),
              busy: updateState.status == UpdateStatus.checking,
              onTap: updateState.status == UpdateStatus.checking
                  ? null
                  : updateController.checkManually,
            ),
          ),
        ],
      ),
    );
  }

  String _updateSubtitle(UpdateState state) {
    if (state.status == UpdateStatus.checking) return '正在检查…';
    final installed = state.installedApp;
    if (installed == null) return '查看当前版本和可用更新';
    return '当前版本 ${installed.versionName} (${installed.versionCode})';
  }
}

class _SettingsSection extends StatelessWidget {
  const _SettingsSection({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: AppTheme.surface,
      border: Border.all(color: AppTheme.border),
      borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
      boxShadow: [
        BoxShadow(
          color: AppTheme.foreground.withValues(alpha: 0.08),
          blurRadius: 3,
          offset: const Offset(0, 1),
        ),
      ],
    ),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(0, 12, 0, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              title,
              style: const TextStyle(
                color: AppTheme.muted,
                fontFamily: 'monospace',
                fontSize: 11,
              ),
            ),
          ),
          child,
        ],
      ),
    ),
  );
}

class _SettingsRow extends StatelessWidget {
  const _SettingsRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    this.iconColor = AppTheme.foreground,
    this.subtitleColor = AppTheme.muted,
    this.monoSubtitle = false,
    this.busy = false,
    this.onTap,
  });

  final IconData icon;
  final Color iconColor;
  final String title;
  final String subtitle;
  final Color subtitleColor;
  final bool monoSubtitle;
  final bool busy;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 56),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                color: iconColor.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
              ),
              child: SizedBox.square(
                dimension: 36,
                child: Icon(icon, size: 20, color: iconColor),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 1),
                  Text(
                    subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: subtitleColor,
                      fontFamily: monoSubtitle ? 'monospace' : null,
                      fontSize: monoSubtitle ? 12 : 13,
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
              const Icon(
                LucideIcons.chevronRight,
                color: AppTheme.muted,
                size: 20,
              ),
          ],
        ),
      ),
    ),
  );
}
