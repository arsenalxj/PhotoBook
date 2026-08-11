import 'package:flutter_test/flutter_test.dart';
import 'package:photobook/core/database/app_database.dart';
import 'package:photobook/models/post.dart';
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

  test('读取持久化的备份错误', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    await raw.insert('app_meta', {
      'key': 'last_backup_error',
      'value': 'R2 暂时不可用',
    });

    final status = await database.readBackupStatus();

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
    await _insertPost(raw, postId: 'xiaohongshu:live1');
    await _insertMedia(
      raw,
      postId: 'xiaohongshu:live1',
      id: 'xiaohongshu:live1:0',
      sortIndex: 0,
      role: 'live_still',
      type: 'image',
      mimeType: 'image/jpeg',
    );
    await _insertMedia(
      raw,
      postId: 'xiaohongshu:live1',
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

  test('当前 generation 完成后才显示已备份且只属于当前目标', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    const postId = 'instagram:post1';
    await _insertPost(raw, postId: postId);
    await _insertMedia(raw, postId: postId, id: '$postId:0');

    expect((await database.listPosts()).single.isBackedUp, isFalse);
    expect(
      (await database.listPosts(backupTargetId: 'target-a')).single.isBackedUp,
      isFalse,
    );
    await _insertBackupJob(
      raw,
      backupSeq: 1,
      backupTargetId: 'target-a',
      postId: postId,
      generation: 1,
      status: 'pending',
    );
    expect(
      (await database.listPosts(backupTargetId: 'target-a')).single.isBackedUp,
      isFalse,
    );
    await raw.update('r2_backup_jobs', {
      'status': 'completed',
      'completed_at': 10,
    }, where: 'backup_seq = 1');
    expect(
      (await database.listPosts(backupTargetId: 'target-a')).single.isBackedUp,
      isTrue,
    );
    expect(
      (await database.listPosts(backupTargetId: 'target-b')).single.isBackedUp,
      isFalse,
    );
  });

  test('重新归档后的新 generation 在完成前不显示已备份', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    const postId = 'instagram:post2';
    await _insertPost(raw, postId: postId);
    await _insertMedia(raw, postId: postId, id: '$postId:0');
    await _insertBackupJob(
      raw,
      backupSeq: 1,
      backupTargetId: 'target-a',
      postId: postId,
      generation: 1,
      status: 'completed',
    );
    await raw.update(
      'posts',
      {'backup_generation': 2},
      where: 'id = ?',
      whereArgs: [postId],
    );

    expect(
      (await database.listPosts(backupTargetId: 'target-a')).single.isBackedUp,
      isFalse,
    );
    await _insertBackupJob(
      raw,
      backupSeq: 2,
      backupTargetId: 'target-a',
      postId: postId,
      generation: 2,
      status: 'completed',
    );
    expect(
      (await database.listPosts(backupTargetId: 'target-a')).single.isBackedUp,
      isTrue,
    );
  });

  test('部分删除不改变 generation 因而保留已备份标记', () async {
    final raw = await databaseFactoryFfi.openDatabase(inMemoryDatabasePath);
    const postId = 'instagram:post3';
    await _insertPost(raw, postId: postId, mediaCount: 2);
    await _insertMedia(raw, postId: postId, id: '$postId:0', sortIndex: 0);
    await _insertMedia(raw, postId: postId, id: '$postId:1', sortIndex: 1);
    await _insertBackupJob(
      raw,
      backupSeq: 1,
      backupTargetId: 'target-a',
      postId: postId,
      generation: 1,
      status: 'completed',
    );

    await raw.delete('post_media', where: 'id = ?', whereArgs: ['$postId:1']);

    final post = (await database.listPosts(backupTargetId: 'target-a')).single;
    expect(post.mediaCount, 1);
    expect(post.isBackedUp, isTrue);
  });
}

Future<void> _insertPost(
  Database raw, {
  required String postId,
  int mediaCount = 1,
}) async {
  final separator = postId.indexOf(':');
  final platform = postId.substring(0, separator);
  final sourcePostId = postId.substring(separator + 1);
  await raw.insert('posts', {
    'id': postId,
    'source_platform': platform,
    'source_post_id': sourcePostId,
    'source_url': platform == 'xiaohongshu'
        ? 'https://www.xiaohongshu.com/explore/$sourcePostId'
        : 'https://www.instagram.com/p/$sourcePostId/',
    'author_username': 'author',
    'author_display_name': 'Author',
    'author_profile_url': 'https://www.instagram.com/author/',
    'has_author_avatar': 0,
    'caption': '',
    'published_at': 1,
    'cover_media_id': '$postId:0',
    'media_count': mediaCount,
    'saved_at': 1,
    'updated_at': 1,
    'backup_generation': 1,
  });
}

Future<void> _insertBackupJob(
  Database raw, {
  required int backupSeq,
  required String backupTargetId,
  required String postId,
  required int generation,
  required String status,
}) => raw.insert('r2_backup_jobs', {
  'backup_seq': backupSeq,
  'backup_target_id': backupTargetId,
  'device_id': 'device-local',
  'post_id': postId,
  'source_platform': postId.substring(0, postId.indexOf(':')),
  'generation': generation,
  'snapshot_json': '{}',
  'status': status,
  'created_at': 1,
  if (status == 'completed') 'completed_at': 1,
});

Future<void> _insertMedia(
  Database raw, {
  required String postId,
  required String id,
  int sortIndex = 0,
  String role = 'primary',
  String type = 'image',
  String mimeType = 'image/jpeg',
}) => raw.insert('post_media', {
  'id': id,
  'post_id': postId,
  'sort_index': sortIndex,
  'logical_index': role.startsWith('live_') ? 0 : sortIndex,
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
