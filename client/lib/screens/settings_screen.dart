import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../controllers/providers.dart';
import '../controllers/update_controller.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';
import 'instagram_login_screen.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(appControllerProvider);
    final instagramSession = controller.instagramSession;
    final savedConfig = controller.r2Config;
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
    final r2Status = savedConfig == null
        ? '未配置'
        : hasBackupError
        ? '备份失败'
        : '${savedConfig.bucket} / ${savedConfig.prefix}';

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
              monoSubtitle: savedConfig != null && !hasBackupError,
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

class R2SettingsScreen extends ConsumerStatefulWidget {
  const R2SettingsScreen({super.key});

  @override
  ConsumerState<R2SettingsScreen> createState() => _R2SettingsScreenState();
}

class _R2SettingsScreenState extends ConsumerState<R2SettingsScreen> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _endpointController;
  late final TextEditingController _bucketController;
  late final TextEditingController _prefixController;
  final _accessKeyController = TextEditingController();
  final _secretController = TextEditingController();
  bool _saving = false;
  bool _secretVisible = false;
  String? _saveError;

  @override
  void initState() {
    super.initState();
    final config = ref.read(appControllerProvider).r2Config;
    _endpointController = TextEditingController(text: config?.endpoint ?? '');
    _bucketController = TextEditingController(text: config?.bucket ?? '');
    _prefixController = TextEditingController(
      text: config?.prefix ?? 'photobook',
    );
  }

  @override
  void dispose() {
    _endpointController.dispose();
    _bucketController.dispose();
    _prefixController.dispose();
    _accessKeyController.dispose();
    _secretController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _saveError = null);
    if (!_formKey.currentState!.validate() || _saving) return;
    setState(() => _saving = true);
    try {
      await ref
          .read(appControllerProvider)
          .saveR2Config(
            R2ConfigInput(
              endpoint: _endpointController.text,
              bucket: _bucketController.text,
              prefix: _prefixController.text,
              accessKeyId: _accessKeyController.text,
              secretAccessKey: _secretController.text,
            ),
          );
      if (!mounted) return;
      _accessKeyController.clear();
      _secretController.clear();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('R2 配置已保存')));
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _saveError = error.message ?? 'R2 配置验证失败');
    } on Object {
      if (!mounted) return;
      setState(() => _saveError = 'R2 配置保存失败，请重试');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _clear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清除 R2 配置?'),
        content: const Text('本地帖子和已上传的 R2 内容不会删除，未完成的备份将停止。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.danger,
              foregroundColor: AppTheme.accentOn,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await ref.read(appControllerProvider).clearR2Config();
    if (!mounted) return;
    _endpointController.clear();
    _bucketController.clear();
    _prefixController.text = 'photobook';
    _accessKeyController.clear();
    _secretController.clear();
    setState(() => _saveError = null);
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(appControllerProvider);
    final savedConfig = controller.r2Config;
    final backupError = controller.backupStatus.lastError;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cloudflare R2'),
        actions: [
          if (savedConfig != null)
            IconButton(
              tooltip: '清除 R2 配置',
              onPressed: _saving ? null : _clear,
              icon: const Icon(LucideIcons.trash),
            ),
          const SizedBox(width: 4),
        ],
      ),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
          children: [
            _R2StatusCard(config: savedConfig),
            if (savedConfig != null && backupError != null) ...[
              const SizedBox(height: 16),
              _InlineError(message: '最近备份失败：$backupError'),
            ],
            const SizedBox(height: 16),
            _field(
              label: 'S3 Endpoint',
              controller: _endpointController,
              hint: 'https://xxxx.r2.cloudflarestorage.com',
              keyboardType: TextInputType.url,
            ),
            _field(label: 'Bucket', controller: _bucketController),
            _field(label: 'Prefix', controller: _prefixController),
            _field(label: 'Access Key ID', controller: _accessKeyController),
            _field(
              label: 'Secret Access Key',
              controller: _secretController,
              obscureText: !_secretVisible,
              suffixIcon: IconButton(
                tooltip: _secretVisible ? '隐藏' : '显示',
                onPressed: () =>
                    setState(() => _secretVisible = !_secretVisible),
                icon: Icon(
                  _secretVisible ? LucideIcons.eyeOff : LucideIcons.eye,
                  color: AppTheme.muted,
                  size: 20,
                ),
              ),
            ),
            if (_saveError != null) ...[
              _InlineError(message: _saveError!),
              const SizedBox(height: 16),
            ],
            FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
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
                        Text('正在验证…'),
                      ],
                    )
                  : const Text('验证并保存'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field({
    required String label,
    required TextEditingController controller,
    String? hint,
    TextInputType? keyboardType,
    bool obscureText = false,
    Widget? suffixIcon,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text.rich(
          TextSpan(
            text: label,
            children: const [
              TextSpan(
                text: '  必填',
                style: TextStyle(
                  color: AppTheme.muted,
                  fontWeight: FontWeight.w400,
                ),
              ),
            ],
          ),
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          keyboardType: keyboardType,
          autocorrect: false,
          enableSuggestions: false,
          obscureText: obscureText,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 14),
          decoration: InputDecoration(hintText: hint, suffixIcon: suffixIcon),
          validator: _required,
        ),
      ],
    ),
  );

  String? _required(String? value) =>
      value == null || value.trim().isEmpty ? '不能为空' : null;
}

class _R2StatusCard extends StatelessWidget {
  const _R2StatusCard({required this.config});

  final R2ConfigSummary? config;

  @override
  Widget build(BuildContext context) {
    final configured = config != null;
    final color = configured ? AppTheme.success : AppTheme.muted;
    return DecoratedBox(
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
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            DecoratedBox(
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
              ),
              child: SizedBox.square(
                dimension: 40,
                child: Icon(
                  configured ? LucideIcons.check : LucideIcons.cloudOff,
                  color: color,
                  size: 22,
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    configured
                        ? '${config!.bucket} / ${config!.prefix}'
                        : 'R2 未配置',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: configured ? AppTheme.foreground : AppTheme.muted,
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    configured
                        ? 'AccessKey ${config!.accessKeyIdHint}'
                        : '填写下方表单以启用单向云备份',
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
          ],
        ),
      ),
    );
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: AppTheme.surface,
      border: Border.all(color: AppTheme.danger),
      borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
    ),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(LucideIcons.circleAlert, color: AppTheme.danger, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(fontSize: 13, height: 1.45),
            ),
          ),
        ],
      ),
    ),
  );
}
