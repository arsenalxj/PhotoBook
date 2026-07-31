import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

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
        title: const Text('删除任务记录?'),
        content: const Text('只删除这条任务记录，不会删除已归档的帖子或媒体。'),
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
      appBar: AppBar(title: const Text('任务')),
      body: RefreshIndicator(
        onRefresh: controller.refreshTasks,
        child: controller.tasks.isEmpty
            ? const _EmptyTasks()
            : ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
                children: [
                  if (active.isNotEmpty)
                    _TaskGroup(
                      title: '进行中',
                      children: active
                          .map(
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
                          )
                          .toList(growable: false),
                    ),
                  if (active.isNotEmpty && failed.isNotEmpty)
                    const SizedBox(height: 16),
                  if (failed.isNotEmpty)
                    _TaskGroup(
                      title: '失败',
                      children: failed
                          .map(
                            (job) => _TaskRow(
                              job: job,
                              busy: _busyJobIds.contains(job.id),
                              needsLogin: _needsLogin(controller, job),
                              onRetry: _retryAction(controller, job),
                              onDelete: () => _confirmDelete(controller, job),
                            ),
                          )
                          .toList(growable: false),
                    ),
                  if ((active.isNotEmpty || failed.isNotEmpty) &&
                      cancelled.isNotEmpty)
                    const SizedBox(height: 16),
                  if (cancelled.isNotEmpty)
                    _TaskGroup(
                      title: '已取消',
                      children: cancelled
                          .map(
                            (job) => _TaskRow(
                              job: job,
                              busy: _busyJobIds.contains(job.id),
                              onRetry: () => _run(
                                job,
                                '重试',
                                () => controller.retryJob(job),
                              ),
                              onDelete: () => _confirmDelete(controller, job),
                            ),
                          )
                          .toList(growable: false),
                    ),
                ],
              ),
      ),
    );
  }

  bool _needsLogin(AppController controller, ArchiveJob job) =>
      job.sourcePlatform == 'instagram' &&
      job.errorCode == 'LOGIN_REQUIRED' &&
      controller.instagramSession?.status != InstagramSessionStatus.ready;

  VoidCallback _retryAction(AppController controller, ArchiveJob job) {
    final needsLogin = _needsLogin(controller, job);
    return needsLogin
        ? () => _loginAndRetry(job)
        : () => _run(job, '重试', () => controller.retryJob(job));
  }
}

class _TaskGroup extends StatelessWidget {
  const _TaskGroup({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
        child: Text(
          title,
          style: const TextStyle(
            color: AppTheme.muted,
            fontFamily: 'monospace',
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
      Card(
        clipBehavior: Clip.antiAlias,
        child: Column(children: children),
      ),
    ],
  );
}

class _EmptyTasks extends StatelessWidget {
  const _EmptyTasks();

  @override
  Widget build(BuildContext context) => ListView(
    physics: const AlwaysScrollableScrollPhysics(),
    padding: const EdgeInsets.all(32),
    children: [
      const SizedBox(height: 184),
      Center(
        child: DecoratedBox(
          decoration: BoxDecoration(
            border: Border.all(color: AppTheme.border),
            borderRadius: BorderRadius.circular(AppTheme.radiusMedium),
          ),
          child: const SizedBox.square(
            dimension: 56,
            child: Icon(
              LucideIcons.circleCheck,
              size: 26,
              color: AppTheme.foreground,
            ),
          ),
        ),
      ),
      const SizedBox(height: 12),
      const Text(
        '没有进行中、失败或已取消的任务',
        textAlign: TextAlign.center,
        style: TextStyle(color: AppTheme.muted, fontSize: 14),
      ),
    ],
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
        color: AppTheme.surface,
        border: Border(bottom: BorderSide(color: AppTheme.border)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Icon(_icon, color: _iconColor, size: 24),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    job.isActive
                        ? job.stageLabel
                        : job.isCancelled
                        ? '已取消'
                        : job.failureTitle,
                    style: TextStyle(
                      fontSize: 14,
                      height: 1.4,
                      fontWeight: job.isFailure
                          ? FontWeight.w700
                          : FontWeight.w400,
                    ),
                  ),
                  if (job.status == ArchiveJobStatus.downloading &&
                      job.progressTotal > 0) ...[
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        minHeight: 4,
                        value: (job.progressCurrent / job.progressTotal).clamp(
                          0,
                          1,
                        ),
                      ),
                    ),
                  ],
                  if (!job.isActive && !job.isCancelled) ...[
                    const SizedBox(height: 4),
                    Text(
                      job.failureDetail,
                      style: const TextStyle(
                        color: AppTheme.muted,
                        fontSize: 13,
                        height: 1.4,
                      ),
                    ),
                  ],
                  const SizedBox(height: 4),
                  Text(
                    '帖子:${job.sourcePlatform == 'xiaohongshu' ? '小红书' : 'instagram'} · ${job.sourcePostId}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppTheme.muted,
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
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
                  icon: const Icon(LucideIcons.x, color: AppTheme.danger),
                ),
              if (onRetry != null)
                IconButton(
                  key: ValueKey('retry-job-${job.id}'),
                  tooltip: needsLogin ? '登录 Instagram' : '重试任务',
                  onPressed: onRetry,
                  icon: Icon(
                    needsLogin ? LucideIcons.logIn : LucideIcons.rotateCw,
                  ),
                ),
              if (onDelete != null)
                IconButton(
                  key: ValueKey('delete-job-${job.id}'),
                  tooltip: '删除任务',
                  onPressed: onDelete,
                  icon: const Icon(LucideIcons.trash),
                ),
            ],
          ],
        ),
      ),
    );
  }

  IconData get _icon => switch (job.status) {
    ArchiveJobStatus.queued => LucideIcons.clock,
    ArchiveJobStatus.fetching => LucideIcons.search,
    ArchiveJobStatus.downloading => LucideIcons.download,
    ArchiveJobStatus.committing => LucideIcons.save,
    ArchiveJobStatus.cancelling => LucideIcons.hourglass,
    ArchiveJobStatus.failed when job.isCancelled => LucideIcons.ban,
    ArchiveJobStatus.failed => LucideIcons.circleAlert,
  };

  Color get _iconColor => job.isFailure
      ? AppTheme.danger
      : job.isActive
      ? AppTheme.foreground
      : AppTheme.muted;
}
