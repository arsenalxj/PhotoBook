import 'package:photobook/core/database/app_database.dart';
import 'package:photobook/models/post.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  sqfliteFfiInit();

  late AppDatabase database;

  setUp(() async {
    database = AppDatabase(
      databaseFactory: databaseFactoryFfi,
      databasePath: inMemoryDatabasePath,
    );
    await database.initialize();
  });

  tearDown(() => database.close());

  test('纯客户端任务表初始为空', () async {
    expect(await database.activeJobCount(), 0);
    expect(await database.failedJobCount(), 0);
    expect(await database.listVisibleJobs(), isEmpty);
  });

  test('读取持久化的同步错误', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await raw.insert('app_meta', {
      'key': 'last_sync_error',
      'value': 'R2 暂时不可用',
    });
    final status = await database.readSyncStatus();

    expect(status.lastError, 'R2 暂时不可用');
  });

  test('失败任务保留结构化错误码', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await raw.insert('capture_jobs', {
      'id': 'job-1',
      'source_url': 'https://www.instagram.com/p/ABC123/',
      'source_platform': 'instagram',
      'request_key': 'ABC123',
      'source_post_id': 'ABC123',
      'status': 'failed',
      'error_code': 'LOGIN_REQUIRED',
      'error_message': 'Instagram 要求登录',
      'created_at': 1,
      'updated_at': 1,
    });

    final failures = await database.listVisibleJobs();

    expect(failures.single.errorCode, 'LOGIN_REQUIRED');
  });

  test('小红书短链 token 不进入 Flutter 任务状态', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await raw.insert('capture_jobs', {
      'id': 'xhs-job',
      'source_url': 'https://xhslink.com/a/test?xsec_token=secret',
      'source_platform': 'xiaohongshu',
      'request_key': 'https://xhslink.com/a/test?xsec_token=secret',
      'source_post_id': null,
      'status': 'fetching',
      'created_at': 1,
      'updated_at': 1,
    });

    final job = (await database.listVisibleJobs()).single;

    expect(job.sourcePostId, '等待解析');
    expect(job.toString(), isNot(contains('secret')));
  });

  test('任务列表排除完成记录并按执行、排队、失败顺序返回', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await _insertJob(
      raw,
      id: 'failed-old',
      status: 'failed',
      createdAt: 1,
      updatedAt: 10,
    );
    await _insertJob(
      raw,
      id: 'queued-later',
      status: 'queued',
      createdAt: 3,
      updatedAt: 3,
    );
    await _insertJob(
      raw,
      id: 'downloading',
      status: 'downloading',
      createdAt: 4,
      updatedAt: 4,
    );
    await _insertJob(
      raw,
      id: 'cancelling',
      status: 'cancelling',
      createdAt: 5,
      updatedAt: 5,
    );
    await _insertJob(
      raw,
      id: 'queued-first',
      status: 'queued',
      createdAt: 2,
      updatedAt: 2,
    );
    await _insertJob(
      raw,
      id: 'failed-new',
      status: 'failed',
      createdAt: 5,
      updatedAt: 20,
    );
    await _insertJob(
      raw,
      id: 'completed',
      status: 'completed',
      createdAt: 0,
      updatedAt: 30,
    );

    final jobs = await database.listVisibleJobs();

    expect(jobs.map((job) => job.id), [
      'downloading',
      'cancelling',
      'queued-first',
      'queued-later',
      'failed-new',
      'failed-old',
    ]);
    expect(jobs.first.progressCurrent, 1);
    expect(jobs.first.progressTotal, 3);
  });

  test('Live Photo 物理文件在界面聚合为一个逻辑媒体', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await raw.insert('posts', {
      'id': 'xiaohongshu:live1',
      'source_platform': 'xiaohongshu',
      'source_post_id': 'live1',
      'source_url': 'https://www.xiaohongshu.com/explore/live1',
      'author_username': 'author',
      'author_display_name': '作者',
      'author_profile_url': 'https://www.xiaohongshu.com/user/profile/author',
      'has_author_avatar': 0,
      'caption': '',
      'published_at': 1,
      'cover_media_id': 'xiaohongshu:live1:0',
      'media_count': 2,
      'saved_at': 1,
      'updated_at': 1,
      'sync_device_id': 'device',
      'sync_seq': 1,
    });
    await _insertMedia(
      raw,
      id: 'xiaohongshu:live1:0',
      sortIndex: 0,
      role: 'live_still',
      type: 'image',
      mimeType: 'image/jpeg',
    );
    await _insertMedia(
      raw,
      id: 'xiaohongshu:live1:1',
      sortIndex: 1,
      role: 'live_motion',
      type: 'video',
      mimeType: 'video/mp4',
    );

    final post = (await database.listPosts()).single;

    expect(post.mediaCount, 1);
    expect(post.media.single.mediaRole, PostMediaRole.liveStill);
    expect(post.media.single.liveMotion?.id, 'xiaohongshu:live1:1');
  });
}

Future<void> _insertMedia(
  Database raw, {
  required String id,
  required int sortIndex,
  required String role,
  required String type,
  required String mimeType,
}) => raw.insert('post_media', {
  'id': id,
  'post_id': 'xiaohongshu:live1',
  'sort_index': sortIndex,
  'logical_index': 0,
  'media_role': role,
  'media_type': type,
  'mime_type': mimeType,
  'width': 1080,
  'height': 1440,
  'original_size': 1,
  'original_sha256': '${sortIndex + 1}'.padLeft(64, '0'),
  'thumbnail_sha256': '${sortIndex + 2}'.padLeft(64, '0'),
  'original_download_status': 'cached',
});

Future<void> _insertJob(
  Database raw, {
  required String id,
  required String status,
  required int createdAt,
  required int updatedAt,
}) => raw.insert('capture_jobs', {
  'id': id,
  'source_url': 'https://www.instagram.com/p/$id/',
  'source_platform': 'instagram',
  'request_key': id,
  'source_post_id': id,
  'status': status,
  'progress_current': 1,
  'progress_total': 3,
  'created_at': createdAt,
  'updated_at': updatedAt,
});
