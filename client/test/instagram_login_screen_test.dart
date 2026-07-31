import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
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
