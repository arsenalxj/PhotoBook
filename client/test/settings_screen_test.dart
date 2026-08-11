import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/screens/settings_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

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
    expect(find.text('Instagram 账号'), findsOneWidget);
    expect(find.text('未登录'), findsOneWidget);
    expect(find.text('检查更新'), findsOneWidget);
    expect(find.text('S3 Endpoint'), findsNothing);

    await tester.tap(find.text('Cloudflare R2'));
    await tester.pumpAndSettle();

    expect(find.text('Cloudflare R2'), findsOneWidget);
    expect(find.textContaining('S3 Endpoint'), findsOneWidget);
    await tester.drag(find.byType(ListView), const Offset(0, -600));
    await tester.pumpAndSettle();
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
    expect(find.byIcon(LucideIcons.camera), findsOneWidget);
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
    expect(find.byIcon(LucideIcons.camera), findsOneWidget);
  });

  testWidgets('R2 保存发生非平台异常时显示可重试错误并退出加载态', (tester) async {
    final controller = _FailingR2Controller()..phase = AppPhase.ready;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appControllerProvider.overrideWith((ref) => controller)],
        child: MaterialApp(
          theme: AppTheme.light,
          home: const R2SettingsScreen(),
        ),
      ),
    );

    final fields = find.byType(TextFormField);
    await tester.enterText(
      fields.at(0),
      'https://example.r2.cloudflarestorage.com',
    );
    await tester.enterText(fields.at(1), 'photobook-test');
    await tester.enterText(fields.at(2), 'photobook');
    await tester.enterText(fields.at(3), 'access-key');
    await tester.enterText(fields.at(4), 'secret-key');
    await tester.drag(find.byType(ListView), const Offset(0, -600));
    await tester.pumpAndSettle();
    final saveButton = find.text('验证并保存');
    await tester.tap(saveButton);
    await tester.pump();

    expect(find.text('R2 配置保存失败，请重试'), findsOneWidget);
    expect(find.text('正在验证…'), findsNothing);
    expect(controller.saveAttempts, 1);
  });
}

class _FailingR2Controller extends AppController {
  int saveAttempts = 0;

  @override
  Future<void> saveR2Config(R2ConfigInput config) async {
    saveAttempts += 1;
    throw StateError('测试保存失败');
  }
}
