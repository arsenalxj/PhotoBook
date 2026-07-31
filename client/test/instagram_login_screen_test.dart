import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/screens/instagram_login_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:webview_flutter_platform_interface/webview_flutter_platform_interface.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late FakeWebViewPlatform webViewPlatform;

  setUp(() {
    webViewPlatform = FakeWebViewPlatform();
    WebViewPlatform.instance = webViewPlatform;
  });

  testWidgets('已登录时复制Cookie位于重新登录和清除登录之间', (tester) async {
    const methodChannel = MethodChannel('photobook-test/copy-instagram-cookie');
    const eventChannel = EventChannel(
      'photobook-test/copy-instagram-cookie-events',
    );
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          calls.add(call);
          return null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(methodChannel, null),
    );
    final controller =
        AppController(
            runtimeBridge: ArchiveRuntimeBridge(
              methodChannel: methodChannel,
              eventChannel: eventChannel,
            ),
            isAndroid: true,
          )
          ..phase = AppPhase.ready
          ..instagramSession = const InstagramSessionSummary(
            status: InstagramSessionStatus.ready,
            username: 'archive_user',
            validatedAt: 1750000000000,
          );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const InstagramSettingsScreen(),
        ),
      ),
    );

    final relogin = find.text('重新登录');
    final copy = find.text('复制Cookie');
    final clear = find.text('清除登录');
    expect(relogin, findsOneWidget);
    expect(copy, findsOneWidget);
    expect(clear, findsOneWidget);
    expect(tester.getCenter(relogin).dy, lessThan(tester.getCenter(copy).dy));
    expect(tester.getCenter(copy).dy, lessThan(tester.getCenter(clear).dy));
    expect(
      find.ancestor(of: relogin, matching: find.byType(FilledButton)),
      findsOneWidget,
    );
    expect(
      find.ancestor(of: copy, matching: find.byType(FilledButton)),
      findsOneWidget,
    );

    await tester.tap(copy);
    await tester.pump();

    expect(calls.map((call) => call.method), ['copyInstagramCookies']);
    expect(find.text('Cookie 已复制，60 秒后自动清除'), findsOneWidget);
  });

  testWidgets('登录失效时不显示复制Cookie', (tester) async {
    final controller = AppController()
      ..phase = AppPhase.ready
      ..instagramSession = const InstagramSessionSummary(
        status: InstagramSessionStatus.needsRefresh,
        username: 'archive_user',
        validatedAt: 1750000000000,
      );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const InstagramSettingsScreen(),
        ),
      ),
    );

    expect(find.text('重新登录'), findsOneWidget);
    expect(find.text('复制Cookie'), findsNothing);
    expect(find.text('清除登录'), findsOneWidget);
  });

  testWidgets('Cookie 登录区位于状态卡下方并回显用户名', (tester) async {
    final controller = _CookieImportController();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const InstagramSettingsScreen(),
        ),
      ),
    );

    final input = find.byKey(const ValueKey('instagram-cookie-input'));
    final button = find.text('验证并登录');
    expect(input, findsOneWidget);
    expect(button, findsOneWidget);
    expect(tester.widget<TextField>(input).obscureText, isTrue);
    expect(
      tester.getTopLeft(input).dy,
      greaterThan(tester.getBottomLeft(find.text('登录 Instagram')).dy),
    );

    await tester.enterText(
      input,
      'sessionid=session-secret; csrftoken=csrf-value',
    );
    await tester.pump();
    await tester.tap(button);
    await tester.pumpAndSettle();

    expect(controller.cookieHeaders, [
      'sessionid=session-secret; csrftoken=csrf-value',
    ]);
    expect(tester.widget<TextField>(input).controller!.text, isEmpty);
    expect(find.text('@manual_user'), findsOneWidget);
    expect(find.text('已登录 @manual_user'), findsOneWidget);
  });

  testWidgets('Cookie 验证失败后仍立即清空输入', (tester) async {
    final controller = _CookieImportController(
      failure: PlatformException(
        code: 'LOGIN_VALIDATION_FAILED',
        message: 'Instagram Cookie 已失效',
      ),
    );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const InstagramSettingsScreen(),
        ),
      ),
    );

    final input = find.byKey(const ValueKey('instagram-cookie-input'));
    await tester.enterText(
      input,
      'sessionid=must-not-remain; csrftoken=csrf-value',
    );
    await tester.pump();
    await tester.tap(find.text('验证并登录'));
    await tester.pumpAndSettle();

    expect(tester.widget<TextField>(input).controller!.text, isEmpty);
    expect(find.text('Instagram Cookie 已失效'), findsOneWidget);
    expect(find.textContaining('must-not-remain'), findsNothing);
  });

  testWidgets('登录页准备失败后可重新加载', (tester) async {
    webViewPlatform.failNextLoad = true;
    var beginCount = 0;

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: InstagramLoginScreen(
            beginLogin: () async => beginCount += 1,
            captureLogin: _unexpectedCapture,
            cancelLogin: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Instagram 登录页打开失败'), findsOneWidget);
    expect(find.byTooltip('重新加载'), findsNWidgets(2));

    await tester.tap(find.byTooltip('重新加载').first);
    await tester.pumpAndSettle();

    expect(beginCount, 2);
    expect(webViewPlatform.controller.loadCount, 2);
    expect(find.text('Instagram 登录页打开失败'), findsNothing);
  });

  testWidgets('主框架加载失败后的刷新会重新加载网页', (tester) async {
    var beginCount = 0;

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: InstagramLoginScreen(
            beginLogin: () async => beginCount += 1,
            captureLogin: _unexpectedCapture,
            cancelLogin: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    webViewPlatform.navigationDelegate.onWebResourceError?.call(
      const WebResourceError(
        errorCode: -2,
        description: 'host lookup failed',
        isForMainFrame: true,
      ),
    );
    await tester.pump();

    expect(find.text('Instagram 页面加载失败，请检查系统网络或 VPN'), findsOneWidget);
    await tester.tap(find.byTooltip('重新加载').first);
    await tester.pumpAndSettle();

    expect(beginCount, 2);
    expect(webViewPlatform.controller.loadCount, 2);
  });

  testWidgets('取消登录后忽略迟到的验证结果', (tester) async {
    final capture = Completer<InstagramSessionSummary>();
    var cancelCount = 0;

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Builder(
            builder: (context) => TextButton(
              onPressed: () {
                Navigator.of(context).push<void>(
                  MaterialPageRoute(
                    builder: (_) => InstagramLoginScreen(
                      beginLogin: () async {},
                      captureLogin: () => capture.future,
                      cancelLogin: () async => cancelCount += 1,
                    ),
                  ),
                );
              },
              child: const Text('打开登录页'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('打开登录页'));
    await tester.pumpAndSettle();

    webViewPlatform.navigationDelegate.onPageFinished?.call(
      'https://www.instagram.com/',
    );
    await tester.pump();
    expect(find.text('正在验证登录状态'), findsOneWidget);

    await tester.tap(find.byIcon(LucideIcons.chevronLeft));
    await tester.pumpAndSettle();

    expect(cancelCount, 1);
    expect(find.text('打开登录页'), findsOneWidget);

    capture.complete(
      const InstagramSessionSummary(
        status: InstagramSessionStatus.ready,
        username: 'late_user',
        validatedAt: 1750000000000,
      ),
    );
    await tester.pump();

    expect(find.text('打开登录页'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

Future<InstagramSessionSummary> _unexpectedCapture() =>
    throw StateError('不应触发 Session 验证');

class _CookieImportController extends AppController {
  _CookieImportController({this.failure}) : super(isAndroid: false) {
    phase = AppPhase.ready;
  }

  final PlatformException? failure;
  final List<String> cookieHeaders = [];

  @override
  Future<InstagramSessionSummary> importInstagramCookies(
    String cookieHeader,
  ) async {
    cookieHeaders.add(cookieHeader);
    final importFailure = failure;
    if (importFailure != null) throw importFailure;
    const session = InstagramSessionSummary(
      status: InstagramSessionStatus.ready,
      username: 'manual_user',
      validatedAt: 1750000000000,
    );
    instagramSession = session;
    notifyListeners();
    return session;
  }
}

class FakeWebViewPlatform extends WebViewPlatform {
  late final FakeWebViewController controller;
  late final FakeNavigationDelegate navigationDelegate;
  bool failNextLoad = false;

  @override
  PlatformWebViewController createPlatformWebViewController(
    PlatformWebViewControllerCreationParams params,
  ) {
    controller = FakeWebViewController(params, this);
    return controller;
  }

  @override
  PlatformWebViewWidget createPlatformWebViewWidget(
    PlatformWebViewWidgetCreationParams params,
  ) => FakeWebViewWidget(params);

  @override
  PlatformNavigationDelegate createPlatformNavigationDelegate(
    PlatformNavigationDelegateCreationParams params,
  ) {
    navigationDelegate = FakeNavigationDelegate(params);
    return navigationDelegate;
  }
}

class FakeWebViewController extends PlatformWebViewController {
  FakeWebViewController(super.params, this.owner) : super.implementation();

  final FakeWebViewPlatform owner;
  int loadCount = 0;

  @override
  Future<void> setJavaScriptMode(JavaScriptMode javaScriptMode) async {}

  @override
  Future<void> setBackgroundColor(Color color) async {}

  @override
  Future<void> setPlatformNavigationDelegate(
    PlatformNavigationDelegate handler,
  ) async {}

  @override
  Future<void> clearCache() async {}

  @override
  Future<void> clearLocalStorage() async {}

  @override
  Future<void> loadRequest(LoadRequestParams params) async {
    loadCount += 1;
    if (owner.failNextLoad) {
      owner.failNextLoad = false;
      throw StateError('load failed');
    }
  }
}

class FakeWebViewWidget extends PlatformWebViewWidget {
  FakeWebViewWidget(super.params) : super.implementation();

  @override
  Widget build(BuildContext context) => const SizedBox.expand();
}

class FakeNavigationDelegate extends PlatformNavigationDelegate {
  FakeNavigationDelegate(super.params) : super.implementation();

  NavigationRequestCallback? onNavigationRequest;
  PageEventCallback? onPageStarted;
  PageEventCallback? onPageFinished;
  ProgressCallback? onProgress;
  WebResourceErrorCallback? onWebResourceError;

  @override
  Future<void> setOnNavigationRequest(
    NavigationRequestCallback onNavigationRequest,
  ) async {
    this.onNavigationRequest = onNavigationRequest;
  }

  @override
  Future<void> setOnPageStarted(PageEventCallback onPageStarted) async {
    this.onPageStarted = onPageStarted;
  }

  @override
  Future<void> setOnPageFinished(PageEventCallback onPageFinished) async {
    this.onPageFinished = onPageFinished;
  }

  @override
  Future<void> setOnProgress(ProgressCallback onProgress) async {
    this.onProgress = onProgress;
  }

  @override
  Future<void> setOnWebResourceError(
    WebResourceErrorCallback onWebResourceError,
  ) async {
    this.onWebResourceError = onWebResourceError;
  }
}
