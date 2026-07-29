import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/screens/settings_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('设置首页通过 Cloudflare R2 入口进入配置页', (tester) async {
    final controller = AppController()..phase = AppPhase.ready;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(theme: AppTheme.light, home: const SettingsScreen()),
      ),
    );

    expect(find.text('Cloudflare R2'), findsOneWidget);
    expect(find.text('Instagram 登录'), findsOneWidget);
    expect(find.text('未登录'), findsOneWidget);
    expect(find.text('检查更新'), findsOneWidget);
    expect(find.text('S3 Endpoint'), findsNothing);

    await tester.tap(find.text('Cloudflare R2'));
    await tester.pumpAndSettle();

    expect(find.text('Cloudflare R2'), findsOneWidget);
    expect(find.text('S3 Endpoint'), findsOneWidget);
    expect(find.text('验证并保存'), findsOneWidget);
  });

  testWidgets('设置首页展示已失效的 Instagram 登录摘要', (tester) async {
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
        child: MaterialApp(theme: AppTheme.light, home: const SettingsScreen()),
      ),
    );

    expect(find.text('@archive_user · 登录已失效'), findsOneWidget);
    expect(find.byIcon(Icons.person_off_outlined), findsOneWidget);
  });

  testWidgets('设置首页展示可用的 Instagram 登录摘要', (tester) async {
    final controller = AppController()
      ..phase = AppPhase.ready
      ..instagramSession = const InstagramSessionSummary(
        status: InstagramSessionStatus.ready,
        username: 'archive_user',
        validatedAt: 1750000000000,
      );
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(theme: AppTheme.light, home: const SettingsScreen()),
      ),
    );

    expect(find.text('@archive_user'), findsOneWidget);
    expect(find.byIcon(Icons.verified_user_outlined), findsOneWidget);
  });
}
