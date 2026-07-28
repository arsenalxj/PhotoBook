import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/screens/settings_screen.dart';
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
    expect(find.text('检查更新'), findsOneWidget);
    expect(find.text('S3 Endpoint'), findsNothing);

    await tester.tap(find.text('Cloudflare R2'));
    await tester.pumpAndSettle();

    expect(find.text('Cloudflare R2'), findsOneWidget);
    expect(find.text('S3 Endpoint'), findsOneWidget);
    expect(find.text('验证并保存'), findsOneWidget);
  });
}
