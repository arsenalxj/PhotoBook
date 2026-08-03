import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:photobook/controllers/app_controller.dart';
import 'package:photobook/controllers/providers.dart';
import 'package:photobook/core/theme/app_theme.dart';
import 'package:photobook/models/archive_job.dart';
import 'package:photobook/screens/task_list_screen.dart';
import 'package:photobook/services/archive_runtime_bridge.dart';

void main() {
  testWidgets('任务按进行中、失败、已取消分组并显示阶段', (tester) async {
    final controller = _TaskController();
    await tester.pumpWidget(_app(controller));

    expect(find.text('进行中'), findsOneWidget);
    expect(find.text('失败'), findsOneWidget);
    expect(find.text('已取消'), findsNWidgets(2));
    expect(find.text('正在下载媒体 1/3'), findsOneWidget);
    expect(find.byTooltip('取消任务'), findsOneWidget);
    expect(find.text('正在取消'), findsOneWidget);
    expect(find.byKey(const ValueKey('cancel-job-cancelling')), findsNothing);
    expect(find.byKey(const ValueKey('retry-job-cancelling')), findsNothing);
    expect(find.byKey(const ValueKey('delete-job-cancelling')), findsNothing);
    expect(find.byTooltip('重试任务'), findsNWidgets(2));
    expect(find.byTooltip('删除任务'), findsNWidgets(2));
  });

  testWidgets('列表项按钮可取消、重试和删除任务', (tester) async {
    final controller = _TaskController();
    await tester.pumpWidget(_app(controller));

    await tester.tap(find.byKey(const ValueKey('cancel-job-active')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('retry-job-failed')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('delete-job-cancelled')));
    await tester.pumpAndSettle();
    expect(find.text('删除任务记录?'), findsOneWidget);
    expect(controller.deletedJobIds, isEmpty);
    await tester.tap(find.widgetWithText(FilledButton, '删除'));
    await tester.pumpAndSettle();

    expect(controller.cancelledJobIds, ['active']);
    expect(controller.retriedJobIds, ['failed']);
    expect(controller.deletedJobIds, ['cancelled']);
  });

  testWidgets('取消删除确认时保留任务记录', (tester) async {
    final controller = _TaskController();
    await tester.pumpWidget(_app(controller));

    await tester.tap(find.byKey(const ValueKey('delete-job-failed')));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '取消'));
    await tester.pumpAndSettle();

    expect(controller.deletedJobIds, isEmpty);
  });

  testWidgets('长按任务项复制原始链接并给出反馈', (tester) async {
    final controller = _TaskController();
    await tester.pumpWidget(_app(controller));

    await tester.longPress(find.byKey(const ValueKey('task-row-active')));
    await tester.pumpAndSettle();

    expect(controller.copiedJobIds, ['active']);
    expect(find.text('链接已复制'), findsOneWidget);
  });

  testWidgets('长按任务操作按钮不会误复制链接', (tester) async {
    final controller = _TaskController();
    await tester.pumpWidget(_app(controller));

    await tester.longPress(find.byKey(const ValueKey('cancel-job-active')));
    await tester.pumpAndSettle();
    await tester.longPress(find.byKey(const ValueKey('retry-job-failed')));
    await tester.pumpAndSettle();

    expect(controller.copiedJobIds, isEmpty);
  });

  testWidgets('登录失败任务在没有可用 Session 时显示登录动作', (tester) async {
    final controller = _TaskController()
      ..instagramSession = null
      ..tasks = const [
        ArchiveJob(
          id: 'login',
          sourcePostId: 'LOGIN',
          status: ArchiveJobStatus.failed,
          errorCode: 'LOGIN_REQUIRED',
        ),
      ];
    await tester.pumpWidget(_app(controller));

    expect(find.byTooltip('登录 Instagram'), findsOneWidget);
    expect(find.byIcon(LucideIcons.logIn), findsOneWidget);
  });

  testWidgets('等待自动重试时显示最近一次失败原因', (tester) async {
    final controller = _TaskController()
      ..tasks = const [
        ArchiveJob(
          id: 'retrying',
          sourcePostId: 'RETRYING',
          status: ArchiveJobStatus.queued,
          nextAttemptAt: 1750000000000,
          errorCode: 'NETWORK_ERROR',
          errorMessage: '已使用 Instagram 登录状态请求帖子详情，但连接失败，请检查系统网络或 VPN',
        ),
      ];
    await tester.pumpWidget(_app(controller));

    expect(find.text('等待自动重试'), findsOneWidget);
    expect(
      find.text('已使用 Instagram 登录状态请求帖子详情，但连接失败，请检查系统网络或 VPN'),
      findsOneWidget,
    );
  });
}

Widget _app(AppController controller) => ProviderScope(
  overrides: [appControllerProvider.overrideWith((ref) => controller)],
  child: MaterialApp(theme: AppTheme.light, home: const TaskListScreen()),
);

class _TaskController extends AppController {
  _TaskController() {
    phase = AppPhase.ready;
    instagramSession = const InstagramSessionSummary(
      status: InstagramSessionStatus.ready,
      username: 'archive_user',
      validatedAt: 1750000000000,
    );
    tasks = const [
      ArchiveJob(
        id: 'active',
        sourcePostId: 'ACTIVE',
        status: ArchiveJobStatus.downloading,
        progressCurrent: 1,
        progressTotal: 3,
      ),
      ArchiveJob(
        id: 'failed',
        sourcePostId: 'FAILED',
        status: ArchiveJobStatus.failed,
        errorCode: 'NETWORK_ERROR',
      ),
      ArchiveJob(
        id: 'cancelling',
        sourcePostId: 'CANCELLING',
        status: ArchiveJobStatus.cancelling,
      ),
      ArchiveJob(
        id: 'cancelled',
        sourcePostId: 'CANCELLED',
        status: ArchiveJobStatus.failed,
        errorCode: 'CANCELLED',
      ),
    ];
  }

  final List<String> cancelledJobIds = [];
  final List<String> retriedJobIds = [];
  final List<String> deletedJobIds = [];
  final List<String> copiedJobIds = [];

  @override
  Future<void> copyJobSourceUrl(ArchiveJob job) async {
    copiedJobIds.add(job.id);
  }

  @override
  Future<void> cancelJob(ArchiveJob job) async {
    cancelledJobIds.add(job.id);
  }

  @override
  Future<void> retryJob(ArchiveJob job) async {
    retriedJobIds.add(job.id);
  }

  @override
  Future<void> deleteJob(ArchiveJob job) async {
    deletedJobIds.add(job.id);
  }
}
