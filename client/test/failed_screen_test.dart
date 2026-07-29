import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/models/archive_job.dart';
import 'package:photobook/screens/failed_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';

void main() {
  testWidgets('登录失败任务在没有可用 Session 时显示登录动作', (tester) async {
    final controller = _FailedController();
    await tester.pumpWidget(_app(controller));
    await tester.pumpAndSettle();

    expect(find.byTooltip('登录 Instagram'), findsOneWidget);
    expect(find.byIcon(Icons.login), findsOneWidget);
  });

  testWidgets('已有可用 Session 时登录失败任务显示重试动作', (tester) async {
    final controller = _FailedController()
      ..instagramSession = const InstagramSessionSummary(
        status: InstagramSessionStatus.ready,
        username: 'archive_user',
        validatedAt: 1750000000000,
      );
    await tester.pumpWidget(_app(controller));
    await tester.pumpAndSettle();

    expect(find.byTooltip('重新下载'), findsOneWidget);
    expect(find.byIcon(Icons.refresh), findsOneWidget);
  });
}

Widget _app(AppController controller) => ProviderScope(
  overrides: [appControllerProvider.overrideWith((ref) => controller)],
  child: MaterialApp(theme: AppTheme.light, home: const FailedScreen()),
);

class _FailedController extends AppController {
  _FailedController() {
    phase = AppPhase.ready;
  }

  @override
  Future<List<ArchiveJob>> loadAllFailures() async => const [
    ArchiveJob(
      id: 'job-1',
      sourcePostId: 'Post123',
      errorCode: 'LOGIN_REQUIRED',
      errorMessage: 'Instagram 要求登录',
    ),
  ];
}
