import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'controllers/app_controller.dart';
import 'controllers/providers.dart';
import 'controllers/update_controller.dart';
import 'core/theme/app_theme.dart';
import 'screens/home_screen.dart';
import 'widgets/update_dialog.dart';

class PhotoBookApp extends ConsumerStatefulWidget {
  const PhotoBookApp({super.key});

  @override
  ConsumerState<PhotoBookApp> createState() => _PhotoBookAppState();
}

class _PhotoBookAppState extends ConsumerState<PhotoBookApp> {
  final _messengerKey = GlobalKey<ScaffoldMessengerState>();
  final _navigatorKey = GlobalKey<NavigatorState>();
  AppLifecycleListener? _lifecycleListener;
  bool _showingSavingSnackBar = false;
  bool _updateDialogVisible = false;

  @override
  void initState() {
    super.initState();
    _lifecycleListener = AppLifecycleListener(
      onResume: () =>
          unawaited(ref.read(appControllerProvider).setForeground(true)),
      onPause: () =>
          unawaited(ref.read(appControllerProvider).setForeground(false)),
      onHide: () =>
          unawaited(ref.read(appControllerProvider).setForeground(false)),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _updateSavingSnackBar(ref.read(appControllerProvider).savingCount);
        unawaited(ref.read(appControllerProvider).setForeground(true));
        unawaited(ref.read(updateControllerProvider).checkOnLaunch());
      }
    });
  }

  @override
  void dispose() {
    _lifecycleListener?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<int>(
      appControllerProvider.select((controller) => controller.savingCount),
      (_, count) => _updateSavingSnackBar(count),
    );
    ref.listen<int>(
      appControllerProvider.select((controller) => controller.messageRevision),
      (_, _) => _showTransientMessage(),
    );
    ref.listen<UpdateState>(
      updateControllerProvider.select((controller) => controller.state),
      (_, state) => _handleUpdateState(state),
    );

    return MaterialApp(
      title: 'PhotoBook',
      debugShowCheckedModeBanner: false,
      scaffoldMessengerKey: _messengerKey,
      navigatorKey: _navigatorKey,
      theme: AppTheme.light,
      home: const _AppGate(),
    );
  }

  void _handleUpdateState(UpdateState state) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (state.status == UpdateStatus.available && !_updateDialogVisible) {
        final context = _navigatorKey.currentContext;
        if (context == null) return;
        _updateDialogVisible = true;
        showDialog<void>(
          context: context,
          barrierDismissible: false,
          builder: (_) => const UpdateDialog(),
        ).whenComplete(() {
          _updateDialogVisible = false;
        });
        return;
      }
      if (state.status == UpdateStatus.upToDate && state.isManual) {
        final version = state.installedApp?.versionName;
        _showUpdateMessage(version == null ? '当前已是最新版本' : '当前已是最新版本 $version');
        ref.read(updateControllerProvider).reset();
      } else if (state.status == UpdateStatus.failed &&
          state.isManual &&
          !_updateDialogVisible &&
          state.manifest == null) {
        _showUpdateMessage(state.errorMessage ?? '检查更新失败，请稍后重试');
        ref.read(updateControllerProvider).reset();
      }
    });
  }

  void _showUpdateMessage(String message) {
    final messenger = _messengerKey.currentState;
    if (messenger == null) return;
    messenger.clearSnackBars();
    _showingSavingSnackBar = false;
    messenger
        .showSnackBar(
          SnackBar(
            duration: const Duration(seconds: 3),
            content: Text(message),
          ),
        )
        .closed
        .then((_) {
          if (!mounted) return;
          final savingCount = ref.read(appControllerProvider).savingCount;
          if (savingCount > 0) _showSavingSnackBar(savingCount);
        });
  }

  void _updateSavingSnackBar(int count) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (count <= 0) {
        if (_showingSavingSnackBar) {
          _messengerKey.currentState?.hideCurrentSnackBar();
          _showingSavingSnackBar = false;
        }
        return;
      }
      _showSavingSnackBar(count);
    });
  }

  void _showSavingSnackBar(int count) {
    final messenger = _messengerKey.currentState;
    if (messenger == null) return;
    messenger.clearSnackBars();
    _showingSavingSnackBar = true;
    messenger.showSnackBar(
      SnackBar(
        duration: const Duration(days: 1),
        content: Row(
          children: [
            const SizedBox.square(
              dimension: 18,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: AppTheme.accentOn,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(child: Text('正在保存 $count 条帖子')),
          ],
        ),
      ),
    );
  }

  void _showTransientMessage() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final controller = ref.read(appControllerProvider);
      final message = controller.message;
      if (message == null || message.isEmpty) return;
      final messenger = _messengerKey.currentState;
      if (messenger == null) return;
      messenger.clearSnackBars();
      _showingSavingSnackBar = false;
      messenger
          .showSnackBar(
            SnackBar(
              duration: const Duration(seconds: 3),
              content: Text(message),
            ),
          )
          .closed
          .then((_) {
            if (!mounted) return;
            final savingCount = ref.read(appControllerProvider).savingCount;
            if (savingCount > 0) _showSavingSnackBar(savingCount);
          });
    });
  }
}

class _AppGate extends ConsumerWidget {
  const _AppGate();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final phase = ref.watch(
      appControllerProvider.select((controller) => controller.phase),
    );
    return switch (phase) {
      AppPhase.initializing => const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      ),
      AppPhase.ready => const HomeScreen(),
    };
  }
}
