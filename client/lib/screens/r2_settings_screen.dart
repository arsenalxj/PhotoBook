import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';

class R2SettingsScreen extends ConsumerWidget {
  const R2SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final controller = ref.watch(appControllerProvider);
    final settings = controller.r2Settings;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cloudflare R2'),
        actions: [
          IconButton(
            key: const ValueKey('add-r2-connection'),
            tooltip: '添加 R2 连接',
            onPressed: () => _showConnectionEditor(context),
            icon: const Icon(LucideIcons.plus),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: settings.connections.isEmpty
          ? _EmptyR2State(onAdd: () => _showConnectionEditor(context))
          : ListView(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 32),
              children: [
                _R2Summary(targetCount: settings.targets.length),
                if (controller.backupStatus.lastError case final error?) ...[
                  const SizedBox(height: 12),
                  _InlineError(message: '最近备份失败：$error'),
                ],
                const SizedBox(height: 20),
                const _SectionLabel('R2 连接'),
                const SizedBox(height: 8),
                for (
                  var index = 0;
                  index < settings.connections.length;
                  index++
                ) ...[
                  _ConnectionPanel(
                    connection: settings.connections[index],
                    targets: settings.targets
                        .where(
                          (target) =>
                              target.connectionId ==
                              settings.connections[index].connectionId,
                        )
                        .toList(growable: false),
                    onAddTarget: () =>
                        _showTargetEditor(context, settings.connections[index]),
                    onUpdateCredentials: () => _showConnectionEditor(
                      context,
                      connection: settings.connections[index],
                    ),
                    onEditTarget: (target) => _showTargetEditor(
                      context,
                      settings.connections[index],
                      target: target,
                    ),
                    onDeleteTarget: (target) =>
                        _deleteTarget(context, ref, target),
                    onDeleteConnection: () => _deleteConnection(
                      context,
                      ref,
                      settings.connections[index],
                    ),
                  ),
                  if (index != settings.connections.length - 1)
                    const SizedBox(height: 12),
                ],
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: () => _showConnectionEditor(context),
                  icon: const Icon(LucideIcons.plus),
                  label: const Text('添加其他 bucket'),
                ),
              ],
            ),
    );
  }

  Future<void> _showConnectionEditor(
    BuildContext context, {
    R2ConnectionSummary? connection,
  }) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      builder: (_) => _R2ConnectionEditorSheet(connection: connection),
    );
    if (saved != true || !context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(connection == null ? 'R2 连接已保存' : 'R2 凭证已更新')),
    );
  }

  Future<void> _showTargetEditor(
    BuildContext context,
    R2ConnectionSummary connection, {
    R2BackupTargetSummary? target,
  }) async {
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      enableDrag: false,
      builder: (_) =>
          _R2TargetEditorSheet(connection: connection, target: target),
    );
    if (saved != true || !context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(target == null ? '备份位置已添加' : '备份位置已更新')),
    );
  }

  Future<void> _deleteTarget(
    BuildContext context,
    WidgetRef ref,
    R2BackupTargetSummary target,
  ) async {
    final confirmed = await _confirmDelete(
      context,
      title: '删除“${target.name}”?',
      message: '该位置未完成的备份会停止，R2 中已经上传的内容不会删除。',
      confirmLabel: '删除位置',
    );
    if (!confirmed || !context.mounted) return;
    try {
      await ref.read(appControllerProvider).deleteR2Target(target.targetId);
    } on Object catch (error) {
      if (!context.mounted) return;
      _showError(context, error, '备份位置删除失败');
    }
  }

  Future<void> _deleteConnection(
    BuildContext context,
    WidgetRef ref,
    R2ConnectionSummary connection,
  ) async {
    final confirmed = await _confirmDelete(
      context,
      title: '删除 bucket“${connection.bucket}”?',
      message: '该连接下的位置和未完成备份会从本机移除，R2 中已经上传的内容不会删除。',
      confirmLabel: '删除连接',
    );
    if (!confirmed || !context.mounted) return;
    try {
      await ref
          .read(appControllerProvider)
          .deleteR2Connection(connection.connectionId);
    } on Object catch (error) {
      if (!context.mounted) return;
      _showError(context, error, 'R2 连接删除失败');
    }
  }
}

class _R2Summary extends StatelessWidget {
  const _R2Summary({required this.targetCount});

  final int targetCount;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: AppTheme.surface,
      border: Border.all(color: AppTheme.border),
      borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
    ),
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          const SizedBox.square(
            dimension: 40,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: AppTheme.foreground,
                borderRadius: BorderRadius.all(
                  Radius.circular(AppTheme.radiusMedium),
                ),
              ),
              child: Icon(
                LucideIcons.cloud,
                color: AppTheme.background,
                size: 21,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '$targetCount 个备份位置',
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                const Text(
                  '备份只在详情页手动发起',
                  style: TextStyle(color: AppTheme.muted, fontSize: 12),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

class _EmptyR2State extends StatelessWidget {
  const _EmptyR2State({required this.onAdd});

  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) => Center(
    child: SingleChildScrollView(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(LucideIcons.cloudUpload, size: 42),
          const SizedBox(height: 16),
          const Text(
            '还没有备份位置',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          const Text(
            '先添加一个 R2 bucket，随后可以复用凭证添加多个 prefix。',
            textAlign: TextAlign.center,
            style: TextStyle(color: AppTheme.muted, height: 1.45),
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            onPressed: onAdd,
            icon: const Icon(LucideIcons.plus),
            label: const Text('添加 R2 连接'),
          ),
        ],
      ),
    ),
  );
}

class _ConnectionPanel extends StatelessWidget {
  const _ConnectionPanel({
    required this.connection,
    required this.targets,
    required this.onAddTarget,
    required this.onUpdateCredentials,
    required this.onEditTarget,
    required this.onDeleteTarget,
    required this.onDeleteConnection,
  });

  final R2ConnectionSummary connection;
  final List<R2BackupTargetSummary> targets;
  final VoidCallback onAddTarget;
  final VoidCallback onUpdateCredentials;
  final ValueChanged<R2BackupTargetSummary> onEditTarget;
  final ValueChanged<R2BackupTargetSummary> onDeleteTarget;
  final VoidCallback onDeleteConnection;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: AppTheme.surface,
      border: Border.all(color: AppTheme.border),
      borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
    ),
    child: Padding(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(LucideIcons.database, size: 20),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  connection.bucket,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              Text(
                '${targets.length} 个位置',
                style: const TextStyle(color: AppTheme.muted, fontSize: 12),
              ),
            ],
          ),
          const SizedBox(height: 5),
          Text(
            connection.endpoint,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppTheme.muted,
              fontFamily: 'monospace',
              fontSize: 11,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            'AccessKey ${connection.accessKeyIdHint}',
            style: const TextStyle(
              color: AppTheme.muted,
              fontFamily: 'monospace',
              fontSize: 11,
            ),
          ),
          const SizedBox(height: 12),
          for (final target in targets)
            _TargetRow(
              target: target,
              onEdit: () => onEditTarget(target),
              onDelete: () => onDeleteTarget(target),
            ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 4,
            runSpacing: 4,
            children: [
              TextButton.icon(
                onPressed: onAddTarget,
                icon: const Icon(LucideIcons.plus, size: 17),
                label: const Text('添加位置'),
              ),
              TextButton(
                onPressed: onUpdateCredentials,
                child: const Text('更新凭证'),
              ),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: AppTheme.danger),
                onPressed: onDeleteConnection,
                child: const Text('删除连接'),
              ),
            ],
          ),
        ],
      ),
    ),
  );
}

enum _TargetAction { edit, delete }

class _TargetRow extends StatelessWidget {
  const _TargetRow({
    required this.target,
    required this.onEdit,
    required this.onDelete,
  });

  final R2BackupTargetSummary target;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minHeight: 54),
    decoration: const BoxDecoration(
      border: Border(top: BorderSide(color: AppTheme.border)),
    ),
    child: Row(
      children: [
        const Icon(LucideIcons.folder, size: 18, color: AppTheme.muted),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                target.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              Text(
                target.prefix,
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
        PopupMenuButton<_TargetAction>(
          tooltip: '位置操作',
          onSelected: (action) {
            if (action == _TargetAction.edit) {
              onEdit();
            } else {
              onDelete();
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: _TargetAction.edit, child: Text('编辑位置')),
            PopupMenuItem(value: _TargetAction.delete, child: Text('删除位置')),
          ],
          icon: const Icon(LucideIcons.ellipsis, size: 20),
        ),
      ],
    ),
  );
}

class _R2ConnectionEditorSheet extends ConsumerStatefulWidget {
  const _R2ConnectionEditorSheet({this.connection});

  final R2ConnectionSummary? connection;

  @override
  ConsumerState<_R2ConnectionEditorSheet> createState() =>
      _R2ConnectionEditorSheetState();
}

class _R2ConnectionEditorSheetState
    extends ConsumerState<_R2ConnectionEditorSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _endpointController;
  late final TextEditingController _bucketController;
  late final TextEditingController _targetNameController;
  late final TextEditingController _prefixController;
  final _accessKeyController = TextEditingController();
  final _secretController = TextEditingController();
  bool _saving = false;
  bool _secretVisible = false;
  String? _error;

  bool get _isUpdating => widget.connection != null;

  @override
  void initState() {
    super.initState();
    _endpointController = TextEditingController(
      text: widget.connection?.endpoint ?? '',
    );
    _bucketController = TextEditingController(
      text: widget.connection?.bucket ?? '',
    );
    _targetNameController = TextEditingController(text: '默认备份');
    _prefixController = TextEditingController(text: 'photobook');
  }

  @override
  void dispose() {
    _endpointController.dispose();
    _bucketController.dispose();
    _targetNameController.dispose();
    _prefixController.dispose();
    _accessKeyController.dispose();
    _secretController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _error = null);
    if (_saving || !_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      final controller = ref.read(appControllerProvider);
      if (_isUpdating) {
        await controller.updateR2Connection(
          R2CredentialsInput(
            connectionId: widget.connection!.connectionId,
            endpoint: _endpointController.text,
            bucket: _bucketController.text,
            accessKeyId: _accessKeyController.text,
            secretAccessKey: _secretController.text,
          ),
        );
      } else {
        await controller.saveR2Connection(
          R2ConnectionInput(
            endpoint: _endpointController.text,
            bucket: _bucketController.text,
            targetName: _targetNameController.text,
            prefix: _prefixController.text,
            accessKeyId: _accessKeyController.text,
            secretAccessKey: _secretController.text,
          ),
        );
      }
      _accessKeyController.clear();
      _secretController.clear();
      if (mounted) Navigator.of(context).pop(true);
    } on Object catch (error) {
      _accessKeyController.clear();
      _secretController.clear();
      if (mounted) {
        setState(() => _error = _errorMessage(error, 'R2 连接保存失败，请重试'));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_saving,
    child: SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(
          16,
          10,
          16,
          16 + MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const _SheetHandle(),
              _EditorHeader(
                title: _isUpdating ? '更新 R2 凭证' : '添加 R2 连接',
                enabled: !_saving,
              ),
              if (!_isUpdating)
                const Text(
                  '连接验证通过后会同时创建第一个备份位置。',
                  style: TextStyle(color: AppTheme.muted, fontSize: 13),
                ),
              const SizedBox(height: 16),
              _FormField(
                label: 'S3 Endpoint',
                controller: _endpointController,
                hint: 'https://xxxx.r2.cloudflarestorage.com',
                keyboardType: TextInputType.url,
                readOnly: _isUpdating,
              ),
              _FormField(
                label: 'Bucket',
                controller: _bucketController,
                readOnly: _isUpdating,
              ),
              if (!_isUpdating) ...[
                _FormField(label: '位置名称', controller: _targetNameController),
                _FormField(label: 'Prefix', controller: _prefixController),
              ],
              _FormField(
                label: 'Access Key ID',
                controller: _accessKeyController,
              ),
              _FormField(
                label: 'Secret Access Key',
                controller: _secretController,
                obscureText: !_secretVisible,
                suffixIcon: IconButton(
                  tooltip: _secretVisible ? '隐藏' : '显示',
                  onPressed: () =>
                      setState(() => _secretVisible = !_secretVisible),
                  icon: Icon(
                    _secretVisible ? LucideIcons.eyeOff : LucideIcons.eye,
                    size: 20,
                  ),
                ),
              ),
              if (_error != null) ...[
                _InlineError(message: _error!),
                const SizedBox(height: 12),
              ],
              FilledButton(
                key: const ValueKey('save-r2-connection'),
                onPressed: _saving ? null : _save,
                child: _saving
                    ? const _SavingLabel('正在验证…')
                    : Text(_isUpdating ? '验证并更新' : '验证并保存'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _R2TargetEditorSheet extends ConsumerStatefulWidget {
  const _R2TargetEditorSheet({required this.connection, this.target});

  final R2ConnectionSummary connection;
  final R2BackupTargetSummary? target;

  @override
  ConsumerState<_R2TargetEditorSheet> createState() =>
      _R2TargetEditorSheetState();
}

class _R2TargetEditorSheetState extends ConsumerState<_R2TargetEditorSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  late final TextEditingController _prefixController;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.target?.name ?? '');
    _prefixController = TextEditingController(
      text: widget.target?.prefix ?? '',
    );
  }

  @override
  void dispose() {
    _nameController.dispose();
    _prefixController.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _error = null);
    if (_saving || !_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    try {
      await ref
          .read(appControllerProvider)
          .saveR2Target(
            R2TargetInput(
              connectionId: widget.connection.connectionId,
              name: _nameController.text,
              prefix: _prefixController.text,
              previousTargetId: widget.target?.targetId,
            ),
          );
      if (mounted) Navigator.of(context).pop(true);
    } on Object catch (error) {
      if (mounted) {
        setState(() => _error = _errorMessage(error, '备份位置保存失败，请重试'));
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !_saving,
    child: SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(
          16,
          10,
          16,
          16 + MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const _SheetHandle(),
              _EditorHeader(
                title: widget.target == null ? '添加备份位置' : '编辑备份位置',
                enabled: !_saving,
              ),
              Text(
                '复用 ${widget.connection.bucket} 的加密凭证。',
                style: const TextStyle(color: AppTheme.muted, fontSize: 13),
              ),
              const SizedBox(height: 16),
              _FormField(label: '位置名称', controller: _nameController),
              _FormField(
                label: 'Prefix',
                controller: _prefixController,
                readOnly: widget.target != null,
              ),
              if (widget.target != null) ...[
                const Text(
                  '如需更换 Prefix，请新增备份位置。',
                  style: TextStyle(color: AppTheme.muted, fontSize: 12),
                ),
                const SizedBox(height: 14),
              ],
              if (_error != null) ...[
                _InlineError(message: _error!),
                const SizedBox(height: 12),
              ],
              FilledButton(
                key: const ValueKey('save-r2-target'),
                onPressed: _saving ? null : _save,
                child: _saving
                    ? const _SavingLabel('正在保存…')
                    : const Text('保存位置'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _EditorHeader extends StatelessWidget {
  const _EditorHeader({required this.title, required this.enabled});

  final String title;
  final bool enabled;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(
          title,
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
      ),
      IconButton(
        tooltip: '关闭',
        onPressed: enabled ? () => Navigator.of(context).pop(false) : null,
        icon: const Icon(LucideIcons.x),
      ),
    ],
  );
}

class _FormField extends StatelessWidget {
  const _FormField({
    required this.label,
    required this.controller,
    this.hint,
    this.keyboardType,
    this.obscureText = false,
    this.readOnly = false,
    this.suffixIcon,
  });

  final String label;
  final TextEditingController controller;
  final String? hint;
  final TextInputType? keyboardType;
  final bool obscureText;
  final bool readOnly;
  final Widget? suffixIcon;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '$label  必填',
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          keyboardType: keyboardType,
          autocorrect: false,
          enableSuggestions: false,
          obscureText: obscureText,
          readOnly: readOnly,
          style: const TextStyle(fontFamily: 'monospace', fontSize: 14),
          decoration: InputDecoration(hintText: hint, suffixIcon: suffixIcon),
          validator: (value) =>
              value == null || value.trim().isEmpty ? '不能为空' : null,
        ),
      ],
    ),
  );
}

class _SavingLabel extends StatelessWidget {
  const _SavingLabel(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const SizedBox.square(
        dimension: 18,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          color: AppTheme.accentOn,
        ),
      ),
      const SizedBox(width: 8),
      Text(label),
    ],
  );
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Text(
    label,
    style: const TextStyle(
      color: AppTheme.muted,
      fontFamily: 'monospace',
      fontSize: 11,
    ),
  );
}

class _SheetHandle extends StatelessWidget {
  const _SheetHandle();

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
          Expanded(child: Text(message, style: const TextStyle(fontSize: 13))),
        ],
      ),
    ),
  );
}

Future<bool> _confirmDelete(
  BuildContext context, {
  required String title,
  required String message,
  required String confirmLabel,
}) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: AppTheme.danger,
              foregroundColor: AppTheme.accentOn,
            ),
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(confirmLabel),
          ),
        ],
      ),
    ) ??
    false;

String _errorMessage(Object error, String fallback) =>
    error is PlatformException ? error.message ?? fallback : fallback;

void _showError(BuildContext context, Object error, String fallback) {
  ScaffoldMessenger.of(
    context,
  ).showSnackBar(SnackBar(content: Text(_errorMessage(error, fallback))));
}
