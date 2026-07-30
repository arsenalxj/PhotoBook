import 'package:photobook/core/database/app_database.dart';
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
}

Future<void> _insertJob(
  Database raw, {
  required String id,
  required String status,
  required int createdAt,
  required int updatedAt,
}) => raw.insert('capture_jobs', {
  'id': id,
  'source_url': 'https://www.instagram.com/p/$id/',
  'source_post_id': id,
  'status': status,
  'progress_current': 1,
  'progress_total': 3,
  'created_at': createdAt,
  'updated_at': updatedAt,
});
