import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../controllers/providers.dart';
import '../controllers/update_controller.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(appControllerProvider);
    final savedConfig = controller.r2Config;
    final hasSyncError = controller.syncStatus.lastError != null;
    final updateController = ref.watch(updateControllerProvider);
    final updateState = updateController.state;
    final status = savedConfig == null
        ? '未配置'
        : hasSyncError
        ? '同步失败'
        : '${savedConfig.bucket} / ${savedConfig.prefix}';

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 40),
        children: [
          const Padding(
            padding: EdgeInsets.only(left: 4),
            child: Text(
              '云同步',
              style: TextStyle(color: AppTheme.muted, fontSize: 14),
            ),
          ),
          const SizedBox(height: 8),
          Card(
            clipBehavior: Clip.antiAlias,
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 4,
              ),
              leading: Icon(
                savedConfig == null
                    ? Icons.cloud_outlined
                    : hasSyncError
                    ? Icons.cloud_off_outlined
                    : Icons.cloud_done_outlined,
                color: hasSyncError
                    ? Theme.of(context).colorScheme.error
                    : savedConfig == null
                    ? AppTheme.muted
                    : const Color(0xFF217A66),
              ),
              title: const Text('Cloudflare R2'),
              subtitle: Text(
                status,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: hasSyncError
                      ? Theme.of(context).colorScheme.error
                      : AppTheme.muted,
                ),
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => const R2SettingsScreen(),
                ),
              ),
            ),
          ),
          const SizedBox(height: 24),
          const Padding(
            padding: EdgeInsets.only(left: 4),
            child: Text(
              '关于',
              style: TextStyle(color: AppTheme.muted, fontSize: 14),
            ),
          ),
          const SizedBox(height: 8),
          Card(
            clipBehavior: Clip.antiAlias,
            child: ListTile(
              contentPadding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 4,
              ),
              leading: const Icon(Icons.system_update_alt),
              title: const Text('检查更新'),
              subtitle: Text(_updateSubtitle(updateState)),
              trailing: updateState.status == UpdateStatus.checking
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.chevron_right),
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
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.message ?? 'R2 配置验证失败')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _clear() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清除 R2 配置？'),
        content: const Text('本地帖子不会删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
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
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(appControllerProvider);
    final savedConfig = controller.r2Config;
    final syncError = controller.syncStatus.lastError;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cloudflare R2'),
        actions: [
          if (savedConfig != null)
            IconButton(
              tooltip: '清除 R2 配置',
              onPressed: _saving ? null : _clear,
              icon: const Icon(Icons.delete_outline),
            ),
        ],
      ),
      body: Form(
        key: _formKey,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 40),
          children: [
            Row(
              children: [
                Icon(
                  savedConfig == null
                      ? Icons.cloud_off_outlined
                      : Icons.cloud_done_outlined,
                  color: savedConfig == null
                      ? AppTheme.muted
                      : const Color(0xFF217A66),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    savedConfig == null
                        ? 'R2 未配置'
                        : '${savedConfig.bucket} / ${savedConfig.prefix}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                if (savedConfig != null)
                  Text(
                    savedConfig.accessKeyIdHint,
                    style: const TextStyle(color: AppTheme.muted),
                  ),
              ],
            ),
            if (savedConfig != null && syncError != null) ...[
              const SizedBox(height: 14),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(
                    Icons.cloud_off_outlined,
                    size: 20,
                    color: Color(0xFFB5473C),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      '最近同步失败：$syncError',
                      style: const TextStyle(color: Color(0xFFB5473C)),
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 22),
            TextFormField(
              controller: _endpointController,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'S3 Endpoint',
                prefixIcon: Icon(Icons.link),
              ),
              validator: _required,
            ),
            const SizedBox(height: 14),
            TextFormField(
              controller: _bucketController,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'Bucket',
                prefixIcon: Icon(Icons.inventory_2_outlined),
              ),
              validator: _required,
            ),
            const SizedBox(height: 14),
            TextFormField(
              controller: _prefixController,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'Prefix',
                prefixIcon: Icon(Icons.folder_outlined),
              ),
              validator: _required,
            ),
            const SizedBox(height: 14),
            TextFormField(
              controller: _accessKeyController,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'Access Key ID',
                prefixIcon: Icon(Icons.key_outlined),
              ),
              validator: _required,
            ),
            const SizedBox(height: 14),
            TextFormField(
              controller: _secretController,
              autocorrect: false,
              enableSuggestions: false,
              obscureText: !_secretVisible,
              decoration: InputDecoration(
                labelText: 'Secret Access Key',
                prefixIcon: const Icon(Icons.password_outlined),
                suffixIcon: IconButton(
                  tooltip: _secretVisible ? '隐藏' : '显示',
                  onPressed: () =>
                      setState(() => _secretVisible = !_secretVisible),
                  icon: Icon(
                    _secretVisible
                        ? Icons.visibility_off_outlined
                        : Icons.visibility_outlined,
                  ),
                ),
              ),
              validator: _required,
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _saving ? null : _save,
              icon: _saving
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.cloud_done_outlined),
              label: const Text('验证并保存'),
            ),
          ],
        ),
      ),
    );
  }

  String? _required(String? value) =>
      value == null || value.trim().isEmpty ? '不能为空' : null;
}
