import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../services/archive_runtime_bridge.dart';

class InstagramSettingsScreen extends ConsumerStatefulWidget {
  const InstagramSettingsScreen({super.key});

  @override
  ConsumerState<InstagramSettingsScreen> createState() =>
      _InstagramSettingsScreenState();
}

class _InstagramSettingsScreenState
    extends ConsumerState<InstagramSettingsScreen> {
  static const _maxCookieHeaderLength = 32 * 1024;

  final _cookieController = TextEditingController();
  bool _cookieVisible = false;
  bool _importingCookie = false;
  bool _hasCookie = false;

  @override
  void initState() {
    super.initState();
    _cookieController.addListener(_handleCookieChanged);
  }

  void _handleCookieChanged() {
    final hasCookie = _cookieController.text.trim().isNotEmpty;
    if (hasCookie != _hasCookie && mounted) {
      setState(() => _hasCookie = hasCookie);
    }
  }

  @override
  void dispose() {
    _cookieController
      ..removeListener(_handleCookieChanged)
      ..clear()
      ..dispose();
    super.dispose();
  }

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
        title: const Text('清除 Instagram 登录?'),
        content: const Text('已归档的帖子不会删除。'),
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

  Future<void> _copyCookies(BuildContext context, WidgetRef ref) async {
    try {
      await ref.read(appControllerProvider).copyInstagramCookies();
      if (!context.mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Cookie 已复制，60 秒后自动清除')));
    } on PlatformException catch (error) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.message ?? 'Cookie 复制失败')));
    }
  }

  Future<void> _importCookies() async {
    if (_importingCookie) return;
    final cookieHeader = _cookieController.text.trim();
    if (cookieHeader.isEmpty) return;

    FocusScope.of(context).unfocus();
    _cookieController.clear();
    setState(() {
      _cookieVisible = false;
      _importingCookie = true;
    });
    try {
      final session = await ref
          .read(appControllerProvider)
          .importInstagramCookies(cookieHeader);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('已登录 @${session.username}')));
    } on PlatformException catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(error.message ?? 'Instagram Cookie 验证失败')),
      );
    } on Object {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Instagram Cookie 验证失败')));
    } finally {
      if (mounted) setState(() => _importingCookie = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(appControllerProvider).instagramSession;
    final status = switch (session?.status) {
      InstagramSessionStatus.ready => (
        icon: LucideIcons.check,
        title: '已登录',
        subtitle: '@${session!.username}',
        color: AppTheme.success,
      ),
      InstagramSessionStatus.needsRefresh => (
        icon: LucideIcons.clock,
        title: '@${session!.username}',
        subtitle: '登录已失效',
        color: AppTheme.danger,
      ),
      null => (
        icon: LucideIcons.user,
        title: '未登录',
        subtitle: '尚未登录 Instagram',
        color: AppTheme.muted,
      ),
    };

    return Scaffold(
      appBar: AppBar(
        leading: _instagramBackButton(context),
        title: const Text('Instagram'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          DecoratedBox(
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
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: status.color.withValues(alpha: 0.10),
                          borderRadius: BorderRadius.circular(
                            AppTheme.radiusMedium,
                          ),
                        ),
                        child: SizedBox.square(
                          dimension: 44,
                          child: Icon(
                            status.icon,
                            size: 22,
                            color: status.color,
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              status.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                fontSize: 15,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            Text(
                              status.subtitle,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: status.color == AppTheme.danger
                                    ? AppTheme.danger
                                    : AppTheme.muted,
                                fontFamily: 'monospace',
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  const Text(
                    '登录后可保存需要登录才能查看的帖子。',
                    style: TextStyle(
                      color: AppTheme.muted,
                      fontSize: 13,
                      height: 1.6,
                    ),
                  ),
                  const SizedBox(height: 14),
                  SizedBox(
                    height: 48,
                    child: FilledButton(
                      onPressed: _importingCookie
                          ? null
                          : () => _login(context, ref),
                      child: Text(session == null ? '登录 Instagram' : '重新登录'),
                    ),
                  ),
                  if (session?.status == InstagramSessionStatus.ready) ...[
                    const SizedBox(height: 14),
                    SizedBox(
                      height: 48,
                      child: FilledButton(
                        onPressed: _importingCookie
                            ? null
                            : () => _copyCookies(context, ref),
                        child: const Text('复制Cookie'),
                      ),
                    ),
                  ],
                  if (session != null) ...[
                    const SizedBox(height: 14),
                    SizedBox(
                      height: 48,
                      child: OutlinedButton(
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppTheme.danger,
                        ),
                        onPressed: _importingCookie
                            ? null
                            : () => _clear(context, ref),
                        child: const Text('清除登录'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          DecoratedBox(
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
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'Cookie 登录',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    key: const ValueKey('instagram-cookie-input'),
                    controller: _cookieController,
                    enabled: !_importingCookie,
                    obscureText: !_cookieVisible,
                    autocorrect: false,
                    enableSuggestions: false,
                    enableIMEPersonalizedLearning: false,
                    autofillHints: const [],
                    smartDashesType: SmartDashesType.disabled,
                    smartQuotesType: SmartQuotesType.disabled,
                    keyboardType: TextInputType.visiblePassword,
                    textInputAction: TextInputAction.done,
                    inputFormatters: [
                      LengthLimitingTextInputFormatter(_maxCookieHeaderLength),
                    ],
                    style: const TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 13,
                    ),
                    decoration: InputDecoration(
                      hintText: '粘贴完整 Cookie',
                      suffixIcon: IconButton(
                        tooltip: _cookieVisible ? '隐藏 Cookie' : '显示 Cookie',
                        onPressed: _importingCookie
                            ? null
                            : () => setState(
                                () => _cookieVisible = !_cookieVisible,
                              ),
                        icon: Icon(
                          _cookieVisible ? LucideIcons.eyeOff : LucideIcons.eye,
                          color: AppTheme.muted,
                          size: 20,
                        ),
                      ),
                    ),
                    onSubmitted: (_) {
                      if (_hasCookie && !_importingCookie) {
                        unawaited(_importCookies());
                      }
                    },
                  ),
                  const SizedBox(height: 14),
                  SizedBox(
                    height: 48,
                    child: OutlinedButton(
                      onPressed: _hasCookie && !_importingCookie
                          ? _importCookies
                          : null,
                      child: _importingCookie
                          ? const Row(
                              mainAxisSize: MainAxisSize.min,
                              mainAxisAlignment: MainAxisAlignment.center,
                              children: [
                                SizedBox.square(
                                  dimension: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                    color: AppTheme.foreground,
                                  ),
                                ),
                                SizedBox(width: 8),
                                Text('正在验证…'),
                              ],
                            )
                          : const Text('验证并登录'),
                    ),
                  ),
                ],
              ),
            ),
          ),
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
      ..setBackgroundColor(AppTheme.surface)
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
          leading: _instagramBackButton(context),
          title: const Text('登录 Instagram'),
          actions: [
            if (_error != null)
              IconButton(
                tooltip: retryTooltip,
                onPressed: busy ? null : _retry,
                icon: const Icon(LucideIcons.rotateCw),
              ),
          ],
        ),
        body: Stack(
          children: [
            Positioned.fill(
              child: _ready
                  ? WebViewWidget(controller: _webViewController)
                  : const ColoredBox(color: AppTheme.surface),
            ),
            if (_progress < 100)
              Align(
                alignment: Alignment.topCenter,
                child: LinearProgressIndicator(value: _progress / 100),
              ),
            if (busy)
              Positioned.fill(
                child: ColoredBox(
                  color: AppTheme.surface.withValues(alpha: 0.78),
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
                    color: AppTheme.danger,
                    borderRadius: BorderRadius.circular(AppTheme.radiusSmall),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
                      child: Row(
                        children: [
                          const Icon(
                            LucideIcons.circleAlert,
                            color: AppTheme.accentOn,
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Text(
                              error,
                              style: const TextStyle(
                                color: AppTheme.accentOn,
                                fontSize: 13,
                                height: 1.45,
                              ),
                            ),
                          ),
                          SizedBox.square(
                            dimension: 44,
                            child: IconButton(
                              tooltip: retryTooltip,
                              onPressed: busy ? null : _retry,
                              icon: const Icon(
                                LucideIcons.rotateCw,
                                color: AppTheme.accentOn,
                              ),
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

Widget _instagramBackButton(BuildContext context) => IconButton(
  tooltip: MaterialLocalizations.of(context).backButtonTooltip,
  onPressed: () => Navigator.maybePop(context),
  icon: const Icon(LucideIcons.chevronLeft),
);
