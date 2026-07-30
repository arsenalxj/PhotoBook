import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../controllers/app_controller.dart';
import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../models/archive_job.dart';
import '../services/archive_runtime_bridge.dart';
import 'instagram_login_screen.dart';

class TaskListScreen extends ConsumerStatefulWidget {
  const TaskListScreen({super.key});

  @override
  ConsumerState<TaskListScreen> createState() => _TaskListScreenState();
}

class _TaskListScreenState extends ConsumerState<TaskListScreen> {
  final Set<String> _busyJobIds = {};

  Future<void> _run(
    ArchiveJob job,
    String action,
    Future<void> Function() operation,
  ) async {
    if (!_busyJobIds.add(job.id)) return;
    setState(() {});
    try {
      await operation();
    } on Object catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('$action失败：$error')));
    } finally {
      if (mounted) setState(() => _busyJobIds.remove(job.id));
    }
  }

  Future<void> _loginAndRetry(ArchiveJob job) async {
    final session = await Navigator.of(context).push<InstagramSessionSummary>(
      MaterialPageRoute(builder: (_) => const InstagramLoginScreen()),
    );
    if (session == null || !mounted) return;
    await _run(job, '重试', () => ref.read(appControllerProvider).retryJob(job));
  }

  Future<void> _confirmDelete(AppController controller, ArchiveJob job) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除任务记录？'),
        content: const Text('只删除这条任务记录，不会删除已归档的帖子或媒体。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _run(job, '删除', () => controller.deleteJob(job));
  }

  @override
  Widget build(BuildContext context) {
    final controller = ref.watch(appControllerProvider);
    final active = controller.tasks.where((job) => job.isActive).toList();
    final failed = controller.tasks.where((job) => job.isFailure).toList();
    final cancelled = controller.tasks.where((job) => job.isCancelled).toList();

    return Scaffold(
      appBar: AppBar(title: const Text('任务列表')),
      body: RefreshIndicator(
        onRefresh: controller.refreshTasks,
        child: controller.tasks.isEmpty
            ? ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: const [
                  SizedBox(height: 200),
                  Icon(Icons.task_alt, size: 44, color: Color(0xFF217A66)),
                  SizedBox(height: 14),
                  Text(
                    '没有进行中、失败或已取消的任务',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppTheme.muted),
                  ),
                ],
              )
            : ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.only(bottom: 32),
                children: [
                  if (active.isNotEmpty) ...[
                    const _SectionHeader(title: '进行中'),
                    ...active.map(
                      (job) => _TaskRow(
                        job: job,
                        busy: _busyJobIds.contains(job.id),
                        onCancel: job.canCancel
                            ? () => _run(
                                job,
                                '取消',
                                () => controller.cancelJob(job),
                              )
                            : null,
                      ),
                    ),
                  ],
                  if (failed.isNotEmpty) ...[
                    const _SectionHeader(title: '失败'),
                    ...failed.map(
                      (job) => _TaskRow(
                        job: job,
                        busy: _busyJobIds.contains(job.id),
                        needsLogin: _needsLogin(controller, job),
                        onRetry: _retryAction(controller, job),
                        onDelete: () => _confirmDelete(controller, job),
                      ),
                    ),
                  ],
                  if (cancelled.isNotEmpty) ...[
                    const _SectionHeader(title: '已取消'),
                    ...cancelled.map(
                      (job) => _TaskRow(
                        job: job,
                        busy: _busyJobIds.contains(job.id),
                        onRetry: () =>
                            _run(job, '重试', () => controller.retryJob(job)),
                        onDelete: () => _confirmDelete(controller, job),
                      ),
                    ),
                  ],
                ],
              ),
      ),
    );
  }

  bool _needsLogin(AppController controller, ArchiveJob job) =>
      job.errorCode == 'LOGIN_REQUIRED' &&
      controller.instagramSession?.status != InstagramSessionStatus.ready;

  VoidCallback _retryAction(AppController controller, ArchiveJob job) {
    final needsLogin = _needsLogin(controller, job);
    return needsLogin
        ? () => _loginAndRetry(job)
        : () => _run(job, '重试', () => controller.retryJob(job));
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 18, 16, 8),
    child: Text(
      title,
      style: Theme.of(context).textTheme.titleSmall?.copyWith(
        color: AppTheme.muted,
        fontWeight: FontWeight.w700,
      ),
    ),
  );
}

class _TaskRow extends StatelessWidget {
  const _TaskRow({
    required this.job,
    required this.busy,
    this.needsLogin = false,
    this.onCancel,
    this.onRetry,
    this.onDelete,
  });

  final ArchiveJob job;
  final bool busy;
  final bool needsLogin;
  final VoidCallback? onCancel;
  final VoidCallback? onRetry;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(bottom: BorderSide(color: AppTheme.divider)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Icon(_icon, color: _iconColor(context), size: 22),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    job.isActive ? job.stageLabel : job.failureTitle,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  if (!job.isActive) ...[
                    const SizedBox(height: 5),
                    Text(
                      job.failureDetail,
                      style: const TextStyle(
                        color: AppTheme.muted,
                        height: 1.35,
                      ),
                    ),
                  ],
                  const SizedBox(height: 7),
                  Text(
                    '帖子：${job.sourcePostId}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: AppTheme.muted, fontSize: 12),
                  ),
                ],
              ),
            ),
            if (busy)
              const SizedBox.square(
                dimension: 44,
                child: Padding(
                  padding: EdgeInsets.all(12),
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              )
            else ...[
              if (onCancel != null)
                IconButton(
                  key: ValueKey('cancel-job-${job.id}'),
                  tooltip: '取消任务',
                  onPressed: onCancel,
                  icon: const Icon(Icons.cancel_outlined),
                ),
              if (onRetry != null)
                IconButton(
                  key: ValueKey('retry-job-${job.id}'),
                  tooltip: needsLogin ? '登录 Instagram' : '重试任务',
                  onPressed: onRetry,
                  icon: Icon(needsLogin ? Icons.login : Icons.refresh),
                ),
              if (onDelete != null)
                IconButton(
                  key: ValueKey('delete-job-${job.id}'),
                  tooltip: '删除任务',
                  onPressed: onDelete,
                  icon: const Icon(Icons.delete_outline),
                ),
            ],
          ],
        ),
      ),
    );
  }

  IconData get _icon => switch (job.status) {
    ArchiveJobStatus.queued => Icons.schedule,
    ArchiveJobStatus.fetching => Icons.manage_search,
    ArchiveJobStatus.downloading => Icons.download_outlined,
    ArchiveJobStatus.committing => Icons.save_outlined,
    ArchiveJobStatus.cancelling => Icons.hourglass_top,
    ArchiveJobStatus.failed when job.isCancelled => Icons.block,
    ArchiveJobStatus.failed => Icons.error_outline,
  };

  Color _iconColor(BuildContext context) => job.isFailure
      ? Theme.of(context).colorScheme.error
      : job.isActive
      ? Theme.of(context).colorScheme.secondary
      : AppTheme.muted;
}
