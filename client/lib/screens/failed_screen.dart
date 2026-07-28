import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../controllers/providers.dart';
import '../core/theme/app_theme.dart';
import '../models/archive_job.dart';

class FailedScreen extends ConsumerStatefulWidget {
  const FailedScreen({super.key});

  @override
  ConsumerState<FailedScreen> createState() => _FailedScreenState();
}

class _FailedScreenState extends ConsumerState<FailedScreen> {
  late Future<List<ArchiveJob>> _failures;
  final Set<String> _retrying = {};

  @override
  void initState() {
    super.initState();
    _failures = ref.read(appControllerProvider).loadAllFailures();
  }

  Future<void> _reload() async {
    final future = ref.read(appControllerProvider).loadAllFailures();
    setState(() => _failures = future);
    await future;
  }

  Future<void> _retry(ArchiveJob job) async {
    if (!_retrying.add(job.id)) return;
    setState(() {});
    try {
      await ref.read(appControllerProvider).retryJob(job);
      await _reload();
    } on Object catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('重试失败：$error')));
    } finally {
      if (mounted) {
        setState(() => _retrying.remove(job.id));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('失败任务')),
      body: FutureBuilder<List<ArchiveJob>>(
        future: _failures,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting &&
              !snapshot.hasData) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _FailureLoadError(onRetry: _reload);
          }
          final captures = snapshot.data ?? const [];
          if (captures.isEmpty) {
            return RefreshIndicator(
              onRefresh: _reload,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: const [
                  SizedBox(height: 200),
                  Icon(
                    Icons.check_circle_outline,
                    size: 44,
                    color: Color(0xFF217A66),
                  ),
                  SizedBox(height: 14),
                  Text(
                    '没有失败任务',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppTheme.muted),
                  ),
                ],
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: _reload,
            child: ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: 32),
              itemCount: captures.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final job = captures[index];
                final retrying = _retrying.contains(job.id);
                return InkWell(
                  onTap: retrying ? null : () => _retry(job),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 14, 8, 14),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Padding(
                          padding: EdgeInsets.only(top: 2),
                          child: Icon(
                            Icons.link_off_outlined,
                            color: AppTheme.muted,
                            size: 22,
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                job.failureTitle,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const SizedBox(height: 5),
                              Text(
                                job.failureDetail,
                                style: const TextStyle(
                                  color: AppTheme.muted,
                                  height: 1.35,
                                ),
                              ),
                              const SizedBox(height: 7),
                              Text(
                                '帖子：${job.sourcePostId}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: AppTheme.muted,
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),
                        SizedBox.square(
                          dimension: 48,
                          child: retrying
                              ? const Padding(
                                  padding: EdgeInsets.all(14),
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : IconButton(
                                  tooltip: '重新下载',
                                  onPressed: () => _retry(job),
                                  icon: const Icon(Icons.refresh),
                                ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _FailureLoadError extends StatelessWidget {
  const _FailureLoadError({required this.onRetry});

  final Future<void> Function() onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.cloud_off_outlined, size: 40, color: AppTheme.muted),
          const SizedBox(height: 12),
          const Text('失败列表加载失败'),
          const SizedBox(height: 10),
          IconButton(
            tooltip: '重新加载',
            onPressed: onRetry,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
    );
  }
}
