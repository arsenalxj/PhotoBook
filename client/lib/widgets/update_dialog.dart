import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../controllers/providers.dart';
import '../controllers/update_controller.dart';

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
      child: AlertDialog(
        title: Text(_title(state.status)),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420, maxHeight: 360),
          child: SingleChildScrollView(child: _content(context, state)),
        ),
        actions: _actions(context, controller, state),
      ),
    );
  }

  String _title(UpdateStatus status) => switch (status) {
    UpdateStatus.available => '发现新版本',
    UpdateStatus.downloading => '正在下载更新',
    UpdateStatus.readyToInstall => '更新已就绪',
    UpdateStatus.awaitingPermission => '需要安装权限',
    UpdateStatus.installing => '正在打开系统安装器',
    UpdateStatus.failed => '更新失败',
    _ => 'PhotoBook 更新',
  };

  Widget _content(BuildContext context, UpdateState state) {
    final manifest = state.manifest;
    return switch (state.status) {
      UpdateStatus.available => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'PhotoBook ${manifest?.versionName ?? ''}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          if (manifest?.releaseNotes.trim().isNotEmpty == true) ...[
            const SizedBox(height: 12),
            Text(manifest!.releaseNotes.trim()),
          ],
        ],
      ),
      UpdateStatus.downloading => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LinearProgressIndicator(value: state.downloadProgress),
          const SizedBox(height: 12),
          Text(
            state.downloadProgress == null
                ? '正在安全下载 APK…'
                : '已下载 ${(state.downloadProgress! * 100).round()}%',
          ),
        ],
      ),
      UpdateStatus.readyToInstall => const Text(
        'APK 已通过大小和 SHA-256 校验。安装前还会核对包名、版本和签名证书。',
      ),
      UpdateStatus.awaitingPermission => const Text(
        '请在系统页面允许 PhotoBook 安装未知应用，然后返回并点击“继续安装”。',
      ),
      UpdateStatus.installing => const Row(
        children: [
          SizedBox.square(
            dimension: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
          SizedBox(width: 12),
          Expanded(child: Text('请在 Android 系统安装页面确认更新。')),
        ],
      ),
      UpdateStatus.failed => Text(state.errorMessage ?? '更新操作失败，请稍后重试'),
      _ => const SizedBox.shrink(),
    };
  }

  List<Widget> _actions(
    BuildContext context,
    UpdateController controller,
    UpdateState state,
  ) {
    void close() {
      controller.reset();
      Navigator.of(context).pop();
    }

    return switch (state.status) {
      UpdateStatus.available => [
        TextButton(onPressed: close, child: const Text('稍后')),
        FilledButton.icon(
          onPressed: controller.download,
          icon: const Icon(Icons.download_outlined),
          label: const Text('下载更新'),
        ),
      ],
      UpdateStatus.downloading => [
        TextButton.icon(
          onPressed: () async {
            await controller.cancelDownload();
            if (context.mounted) Navigator.of(context).pop();
          },
          icon: const Icon(Icons.close),
          label: const Text('取消下载'),
        ),
      ],
      UpdateStatus.readyToInstall => [
        TextButton(onPressed: close, child: const Text('稍后')),
        FilledButton.icon(
          onPressed: controller.install,
          icon: const Icon(Icons.system_update_alt),
          label: const Text('安装'),
        ),
      ],
      UpdateStatus.awaitingPermission => [
        TextButton(onPressed: close, child: const Text('稍后')),
        FilledButton.icon(
          onPressed: controller.install,
          icon: const Icon(Icons.security_outlined),
          label: const Text('继续安装'),
        ),
      ],
      UpdateStatus.installing => const [],
      UpdateStatus.failed => [
        TextButton(onPressed: close, child: const Text('关闭')),
        if (state.manifest != null)
          FilledButton.icon(
            onPressed: state.localPath == null
                ? controller.download
                : controller.install,
            icon: const Icon(Icons.refresh),
            label: const Text('重试'),
          ),
      ],
      _ => const [],
    };
  }
}
