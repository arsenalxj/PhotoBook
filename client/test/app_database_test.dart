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
    expect(await database.listFailedJobs(), isEmpty);
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

    final failures = await database.listFailedJobs();

    expect(failures.single.errorCode, 'LOGIN_REQUIRED');
  });
}
