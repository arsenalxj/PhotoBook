import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';

class InstagramSettingsScreen extends ConsumerWidget {
  const InstagramSettingsScreen({super.key});

  Future<void> _login(BuildContext context, WidgetRef ref) async {
    final session = await Navigator.of(context).push<InstagramSessionSummary>(
      MaterialPageRoute(builder: (_) => const InstagramLoginScreen()),
    );
    if (session == null || !context.mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('已登录 @${session.username}')));
  }

  Future<void> _clear(BuildContext context, WidgetRef ref) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清除 Instagram 登录？'),
        content: const Text('已归档的帖子不会删除。'),
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
    if (confirmed != true || !context.mounted) return;
    try {
      await ref.read(appControllerProvider).clearInstagramSession();
      if (!context.mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Instagram 登录已清除')));
    } on PlatformException catch (error) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error.message ?? 'Instagram 登录清除失败')),
      );
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = ref.watch(appControllerProvider).instagramSession;
    final ready = session?.status == InstagramSessionStatus.ready;
    final status = switch (session?.status) {
      InstagramSessionStatus.ready => '@${session!.username}',
      InstagramSessionStatus.needsRefresh => '@${session!.username} · 登录已失效',
      null => '未登录',
    };
    final color = switch (session?.status) {
      InstagramSessionStatus.ready => const Color(0xFF217A66),
      InstagramSessionStatus.needsRefresh => Theme.of(
        context,
      ).colorScheme.error,
      null => AppTheme.muted,
    };

    return Scaffold(
      appBar: AppBar(title: const Text('Instagram')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 40),
        children: [
          Row(
            children: [
              Icon(
                ready ? Icons.verified_user_outlined : Icons.person_outline,
                size: 28,
                color: color,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  status,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: color,
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 28),
          SizedBox(
            height: 48,
            child: FilledButton.icon(
              onPressed: () => _login(context, ref),
              icon: Icon(ready ? Icons.sync : Icons.login),
              label: Text(ready ? '重新登录' : '登录 Instagram'),
            ),
          ),
          if (session != null) ...[
            const SizedBox(height: 12),
            SizedBox(
              height: 48,
              child: OutlinedButton.icon(
                onPressed: () => _clear(context, ref),
                icon: const Icon(Icons.logout),
                label: const Text('清除登录'),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class InstagramLoginScreen extends ConsumerStatefulWidget {
  const InstagramLoginScreen({
    super.key,
    @visibleForTesting this.beginLogin,
    @visibleForTesting this.captureLogin,
    @visibleForTesting this.cancelLogin,
  });

  final Future<void> Function()? beginLogin;
  final Future<InstagramSessionSummary> Function()? captureLogin;
  final Future<void> Function()? cancelLogin;

  @override
  ConsumerState<InstagramLoginScreen> createState() =>
      _InstagramLoginScreenState();
}

class _InstagramLoginScreenState extends ConsumerState<InstagramLoginScreen> {
  static final Uri _loginUri = Uri.parse(
    'https://www.instagram.com/accounts/login/',
  );

  late final WebViewController _webViewController;
  bool _ready = false;
  bool _preparing = false;
  bool _needsReload = false;
  bool _validating = false;
  bool _closing = false;
  bool _allowPop = false;
  int _progress = 0;
  int _operationVersion = 0;
  String? _error;

  @override
  void initState() {
    super.initState();
    _webViewController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..setNavigationDelegate(
        NavigationDelegate(
          onProgress: (progress) {
            if (mounted && !_closing) setState(() => _progress = progress);
          },
          onPageStarted: (_) {
            if (!mounted || _closing) return;
            setState(() {
              _error = null;
              _needsReload = false;
            });
          },
          onPageFinished: (_) {
            if (!mounted || _closing) return;
            setState(() => _progress = 100);
            if (!_needsReload) {
              unawaited(_captureSession(silentIncomplete: true));
            }
          },
          onWebResourceError: (error) {
            if (!mounted || _closing || error.isForMainFrame != true) return;
            setState(() {
              _error = 'Instagram 页面加载失败，请检查系统网络或 VPN';
              _needsReload = true;
              _progress = 100;
            });
          },
          onNavigationRequest: _navigationDecision,
        ),
      );
    unawaited(_prepare());
  }

  Future<void> _prepare() async {
    if (_preparing || _validating || _closing) return;
    final operation = ++_operationVersion;
    setState(() {
      _preparing = true;
      _ready = false;
      _needsReload = false;
      _progress = 0;
      _error = null;
    });
    try {
      await _webViewController.clearCache();
      if (!_isCurrent(operation)) return;
      await _webViewController.clearLocalStorage();
      if (!_isCurrent(operation)) return;
      await _beginLogin();
      if (!_isCurrent(operation)) return;
      await _webViewController.loadRequest(_loginUri);
      if (!_isCurrent(operation)) return;
      setState(() {
        _ready = true;
        _preparing = false;
      });
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      setState(() {
        _preparing = false;
        _needsReload = true;
        _error = _message(error, 'Instagram 登录页打开失败');
      });
    }
  }

  Future<void> _beginLogin() {
    final callback = widget.beginLogin;
    return callback != null
        ? callback()
        : ref.read(appControllerProvider).beginInstagramLogin();
  }

  Future<InstagramSessionSummary> _captureLogin() {
    final callback = widget.captureLogin;
    return callback != null
        ? callback()
        : ref.read(appControllerProvider).captureInstagramSession();
  }

  Future<void> _cancelLogin() {
    final callback = widget.cancelLogin;
    return callback != null
        ? callback()
        : ref.read(appControllerProvider).cancelInstagramLogin();
  }

  bool _isCurrent(int operation) =>
      mounted && !_closing && _operationVersion == operation;

  NavigationDecision _navigationDecision(NavigationRequest request) {
    final uri = Uri.tryParse(request.url);
    if (uri == null) return NavigationDecision.prevent;
    if (uri.scheme == 'about' && uri.path == 'blank') {
      return NavigationDecision.navigate;
    }
    if (uri.scheme != 'https' || !_isAllowedHost(uri.host)) {
      return NavigationDecision.prevent;
    }
    return NavigationDecision.navigate;
  }

  bool _isAllowedHost(String host) {
    final normalized = host.toLowerCase();
    return normalized == 'instagram.com' ||
        normalized.endsWith('.instagram.com') ||
        normalized == 'facebook.com' ||
        normalized.endsWith('.facebook.com');
  }

  Future<void> _captureSession({required bool silentIncomplete}) async {
    if (_preparing || _validating || _closing || !_ready || _needsReload) {
      return;
    }
    final operation = ++_operationVersion;
    setState(() {
      _validating = true;
      if (!silentIncomplete) _error = null;
    });
    try {
      final session = await _captureLogin();
      if (!_isCurrent(operation)) return;
      await _webViewController.clearCache();
      if (!_isCurrent(operation)) return;
      await _webViewController.clearLocalStorage();
      if (!_isCurrent(operation)) return;
      setState(() => _allowPop = true);
      if (!mounted) return;
      Navigator.of(context).pop(session);
    } on PlatformException catch (error) {
      if (!_isCurrent(operation)) return;
      if (error.code != 'LOGIN_INCOMPLETE' || !silentIncomplete) {
        setState(() {
          _error = error.message ?? 'Instagram 登录验证失败';
        });
      }
    } on Object catch (error) {
      if (!_isCurrent(operation)) return;
      setState(() => _error = _message(error, 'Instagram 登录验证失败'));
    } finally {
      if (_isCurrent(operation)) setState(() => _validating = false);
    }
  }

  Future<void> _cancelAndClose() async {
    if (_closing) return;
    _operationVersion += 1;
    setState(() {
      _closing = true;
      _error = null;
    });
    try {
      Object? failure;
      StackTrace? failureStack;

      Future<void> runCleanup(Future<void> Function() action) async {
        try {
          await action();
        } on Object catch (error, stack) {
          failure ??= error;
          failureStack ??= stack;
        }
      }

      await runCleanup(_cancelLogin);
      await runCleanup(_webViewController.clearCache);
      await runCleanup(_webViewController.clearLocalStorage);
      if (failure != null) {
        Error.throwWithStackTrace(failure!, failureStack!);
      }
      if (!mounted) return;
      setState(() => _allowPop = true);
      Navigator.of(context).pop();
    } on Object catch (error) {
      if (!mounted) return;
      setState(() {
        _closing = false;
        _ready = false;
        _needsReload = true;
        _error = _message(error, 'Instagram 登录数据清理失败');
      });
    }
  }

  Future<void> _retry() => _needsReload || !_ready
      ? _prepare()
      : _captureSession(silentIncomplete: false);

  String _message(Object error, String fallback) =>
      error is PlatformException ? error.message ?? fallback : fallback;

  @override
  Widget build(BuildContext context) {
    final busy = _preparing || _validating || _closing;
    final busyText = _preparing
        ? '正在打开 Instagram'
        : _validating
        ? '正在验证登录状态'
        : '正在清理登录数据';
    final retryTooltip = _needsReload || !_ready ? '重新加载' : '重新验证';

    return PopScope(
      canPop: _allowPop,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) unawaited(_cancelAndClose());
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('登录 Instagram'),
          actions: [
            if (_error != null)
              IconButton(
                tooltip: retryTooltip,
                onPressed: busy ? null : _retry,
                icon: const Icon(Icons.refresh),
              ),
          ],
        ),
        body: Stack(
          children: [
            Positioned.fill(
              child: _ready
                  ? WebViewWidget(controller: _webViewController)
                  : const ColoredBox(color: Colors.white),
            ),
            if (_progress < 100)
              Align(
                alignment: Alignment.topCenter,
                child: LinearProgressIndicator(value: _progress / 100),
              ),
            if (busy)
              Positioned.fill(
                child: ColoredBox(
                  color: Colors.white.withValues(alpha: 0.88),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const CircularProgressIndicator(),
                        const SizedBox(height: 14),
                        Text(busyText),
                      ],
                    ),
                  ),
                ),
              ),
            if (_error case final error?)
              Align(
                alignment: Alignment.bottomCenter,
                child: SafeArea(
                  minimum: const EdgeInsets.all(12),
                  child: Material(
                    color: Theme.of(context).colorScheme.errorContainer,
                    borderRadius: BorderRadius.circular(6),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
                      child: Row(
                        children: [
                          Icon(
                            Icons.error_outline,
                            color: Theme.of(context).colorScheme.error,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              error,
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                          ),
                          SizedBox.square(
                            dimension: 44,
                            child: IconButton(
                              tooltip: retryTooltip,
                              onPressed: busy ? null : _retry,
                              icon: const Icon(Icons.refresh),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
