import 'package:photobook/models/archive_job.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('登录限制显示为明确原因', () {
    final job = ArchiveJob.fromDatabase({
      'id': 'job-1',
      'source_post_id': 'ABC123',
      'status': 'failed',
      'progress_current': 0,
      'progress_total': 0,
      'next_attempt_at': null,
      'error_code': 'LOGIN_REQUIRED',
      'error_message': null,
    });

    expect(job.errorCode, 'LOGIN_REQUIRED');
    expect(job.failureTitle, '需要 Instagram 登录');
    expect(job.failureDetail, contains('官方登录页'));
  });

  test('解析类错误统一显示为帖子解析失败', () {
    for (final code in [
      'INSTAGRAM_ERROR',
      'INVALID_RESPONSE',
      'SOURCE_MISMATCH',
    ]) {
      final job = ArchiveJob.fromDatabase({
        'id': 'job-$code',
        'source_post_id': 'ABC123',
        'status': 'failed',
        'progress_current': 0,
        'progress_total': 0,
        'next_attempt_at': null,
        'error_code': code,
        'error_message': 'Instagram 返回的数据无法解析',
      });

      expect(job.failureTitle, '帖子解析失败');
    }
  });

  test('全部活动阶段映射为稳定中文文案', () {
    final cases = {
      'queued': '等待处理',
      'fetching': '正在解析帖子',
      'downloading': '正在下载媒体 2/4',
      'committing': '正在保存到本机',
      'cancelling': '正在取消',
    };

    for (final entry in cases.entries) {
      final job = ArchiveJob.fromDatabase({
        'id': 'job-${entry.key}',
        'source_post_id': 'ABC123',
        'status': entry.key,
        'progress_current': 2,
        'progress_total': 4,
        'next_attempt_at': null,
        'error_code': null,
        'error_message': null,
      });

      expect(job.stageLabel, entry.value);
      expect(job.isActive, isTrue);
      expect(job.canCancel, entry.key != 'cancelling');
    }
  });

  test('等待自动重试和用户取消有独立语义', () {
    final retrying = ArchiveJob.fromDatabase({
      'id': 'job-retrying',
      'source_post_id': 'ABC123',
      'status': 'queued',
      'progress_current': 0,
      'progress_total': 0,
      'next_attempt_at': 1750000000000,
      'error_code': 'NETWORK_ERROR',
      'error_message': '暂时断网',
    });
    final cancelled = ArchiveJob.fromDatabase({
      'id': 'job-cancelled',
      'source_post_id': 'ABC123',
      'status': 'failed',
      'progress_current': 1,
      'progress_total': 3,
      'next_attempt_at': null,
      'error_code': 'CANCELLED',
      'error_message': '任务已取消',
    });

    expect(retrying.stageLabel, '等待自动重试');
    expect(cancelled.isCancelled, isTrue);
    expect(cancelled.failureTitle, '任务已取消');
  });

  test('未知任务状态会被拒绝', () {
    expect(
      () => ArchiveJob.fromDatabase({
        'id': 'job-unknown',
        'source_post_id': 'ABC123',
        'status': 'paused',
        'progress_current': 0,
        'progress_total': 0,
        'next_attempt_at': null,
        'error_code': null,
        'error_message': null,
      }),
      throwsFormatException,
    );
  });
}
