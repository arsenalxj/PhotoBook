import 'package:photobook/models/archive_job.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('登录限制显示为明确原因', () {
    final job = ArchiveJob.fromDatabase({
      'id': 'job-1',
      'source_post_id': 'ABC123',
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
        'error_code': code,
        'error_message': 'Instagram 返回的数据无法解析',
      });

      expect(job.failureTitle, '帖子解析失败');
    }
  });
}
