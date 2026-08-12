import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';

import '../../models/archive_job.dart';
import '../../models/post.dart';

class BackupStatus {
  const BackupStatus({this.lastError});

  final String? lastError;
}

enum BackupTargetState { notBackedUp, pending, completed, failed }

class BackupTargetStatus {
  const BackupTargetStatus({required this.state, this.lastError});

  final BackupTargetState state;
  final String? lastError;
}

class AppDatabase {
  AppDatabase({DatabaseFactory? databaseFactory, String? databasePath})
    : _databaseFactory = databaseFactory ?? databaseFactorySqflitePlugin,
      _databasePath = databasePath;

  static const version = 4;

  final DatabaseFactory _databaseFactory;
  final String? _databasePath;
  Database? _database;

  Future<void> initialize() async {
    if (_database != null) return;
    final databasePath =
        _databasePath ?? path.join(await getDatabasesPath(), 'photobook.db');
    _database = await _databaseFactory.openDatabase(
      databasePath,
      options: OpenDatabaseOptions(
        version: version,
        onConfigure: (database) async {
          await database.execute('PRAGMA foreign_keys = ON');
          await database.rawQuery('PRAGMA journal_mode = WAL');
        },
        onCreate: _createSchema,
        onUpgrade: (_, oldVersion, newVersion) async {
          throw StateError(
            '开发阶段不支持数据库从 $oldVersion 升级到 $newVersion，请清除 App 数据后重新安装',
          );
        },
      ),
    );
  }

  Database get _db {
    final database = _database;
    if (database == null) throw StateError('数据库尚未初始化');
    return database;
  }

  Future<void> close() async {
    await _database?.close();
    _database = null;
  }

  Future<void> _createSchema(Database database, int _) async {
    await database.execute('''
      CREATE TABLE app_meta (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL
      )
    ''');
    await database.execute('''
      CREATE TABLE posts (
        id TEXT PRIMARY KEY,
        source_platform TEXT NOT NULL,
        source_post_id TEXT NOT NULL,
        source_url TEXT NOT NULL,
        author_username TEXT NOT NULL,
        author_display_name TEXT NOT NULL,
        author_profile_url TEXT NOT NULL,
        has_author_avatar INTEGER NOT NULL,
        author_avatar_sha256 TEXT,
        caption TEXT NOT NULL,
        published_at INTEGER NOT NULL,
        location_name TEXT,
        cover_media_id TEXT NOT NULL,
        media_count INTEGER NOT NULL,
        saved_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        local_avatar_path TEXT,
        backup_generation INTEGER NOT NULL,
        UNIQUE(source_platform, source_post_id)
      )
    ''');
    await database.execute(
      'CREATE INDEX posts_saved_at ON posts(saved_at DESC, id DESC)',
    );
    await database.execute('''
      CREATE TABLE post_media (
        id TEXT PRIMARY KEY,
        post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
        sort_index INTEGER NOT NULL,
        logical_index INTEGER NOT NULL,
        media_role TEXT NOT NULL,
        media_type TEXT NOT NULL,
        mime_type TEXT NOT NULL,
        width INTEGER NOT NULL,
        height INTEGER NOT NULL,
        duration_ms INTEGER,
        original_size INTEGER NOT NULL,
        original_sha256 TEXT NOT NULL,
        thumbnail_sha256 TEXT NOT NULL,
        local_thumbnail_path TEXT,
        local_original_path TEXT,
        original_download_status TEXT NOT NULL DEFAULT 'cached',
        original_download_error TEXT,
        UNIQUE(post_id, sort_index)
      )
    ''');
    await database.execute(
      'CREATE INDEX post_media_post_id ON post_media(post_id, sort_index)',
    );
    await database.execute('''
      CREATE TABLE post_backup_generations (
        post_id TEXT PRIMARY KEY,
        generation INTEGER NOT NULL
      )
    ''');
    await _createRuntimeSchema(database);
  }

  Future<void> _createRuntimeSchema(Database database) async {
    await database.execute('''
      CREATE TABLE IF NOT EXISTS capture_jobs (
        id TEXT PRIMARY KEY,
        source_url TEXT NOT NULL,
        source_platform TEXT NOT NULL,
        request_key TEXT NOT NULL,
        source_post_id TEXT,
        status TEXT NOT NULL,
        progress_current INTEGER NOT NULL DEFAULT 0,
        progress_total INTEGER NOT NULL DEFAULT 0,
        attempt_count INTEGER NOT NULL DEFAULT 0,
        next_attempt_at INTEGER,
        error_code TEXT,
        error_message TEXT,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        UNIQUE(source_platform, request_key)
      )
    ''');
    await database.execute(
      'CREATE INDEX IF NOT EXISTS capture_jobs_status '
      'ON capture_jobs(status, created_at)',
    );
    await database.execute('''
      CREATE TABLE r2_backup_jobs (
        backup_seq INTEGER PRIMARY KEY,
        backup_target_id TEXT NOT NULL,
        device_id TEXT NOT NULL,
        post_id TEXT NOT NULL,
        source_platform TEXT NOT NULL,
        generation INTEGER NOT NULL,
        snapshot_json TEXT NOT NULL,
        status TEXT NOT NULL,
        last_error TEXT,
        created_at INTEGER NOT NULL,
        completed_at INTEGER,
        UNIQUE(backup_target_id, device_id, post_id, generation)
      )
    ''');
    await database.execute(
      'CREATE INDEX r2_backup_jobs_pending '
      'ON r2_backup_jobs(backup_target_id, device_id, status, backup_seq)',
    );
    await database.execute(
      'CREATE INDEX r2_backup_jobs_post_generation '
      'ON r2_backup_jobs(backup_target_id, post_id, generation, status)',
    );
  }

  Future<List<ArchivedPost>> listPosts() async {
    final postRows = await _db.query(
      'posts',
      columns: const [
        'id',
        'source_platform',
        'source_url',
        'author_username',
        'author_display_name',
        'caption',
        'published_at',
        'location_name',
        'cover_media_id',
        'media_count',
        'local_avatar_path',
        'backup_generation',
      ],
      orderBy: 'saved_at DESC, id DESC',
    );
    if (postRows.isEmpty) return const [];
    final mediaRows = await _db.query(
      'post_media',
      columns: const [
        'id',
        'post_id',
        'sort_index',
        'logical_index',
        'media_role',
        'media_type',
        'mime_type',
        'width',
        'height',
        'local_thumbnail_path',
        'local_original_path',
      ],
      orderBy: 'post_id, sort_index ASC',
    );
    final backedUpPostIds = await _backedUpPostIds();
    final groupedMedia = <String, List<PostMedia>>{};
    for (final row in mediaRows) {
      final postId = row['post_id']! as String;
      groupedMedia.putIfAbsent(postId, () => []).add(_mediaFromRow(row));
    }
    return postRows
        .map(
          (row) => _postFromRow(
            row,
            _logicalMedia(groupedMedia[row['id']] ?? const []),
            isBackedUp: backedUpPostIds.contains(row['id']),
          ),
        )
        .where((post) => post.media.isNotEmpty)
        .toList(growable: false);
  }

  Future<List<ArchiveJob>> listVisibleJobs() async {
    final rows = await _db.query(
      'capture_jobs',
      columns: const [
        'id',
        'source_platform',
        'source_post_id',
        'status',
        'progress_current',
        'progress_total',
        'next_attempt_at',
        'error_code',
        'error_message',
      ],
      where:
          "status IN ('queued', 'fetching', 'downloading', 'committing', 'cancelling', 'failed')",
      orderBy: '''
        CASE
          WHEN status IN ('fetching', 'downloading', 'committing', 'cancelling') THEN 0
          WHEN status = 'queued' THEN 1
          ELSE 2
        END,
        CASE WHEN status != 'failed' THEN created_at END ASC,
        CASE WHEN status = 'failed' THEN updated_at END DESC,
        id ASC
      ''',
    );
    return rows.map(ArchiveJob.fromDatabase).toList(growable: false);
  }

  Future<int> activeJobCount() async =>
      Sqflite.firstIntValue(
        await _db.rawQuery('''
          SELECT COUNT(*) FROM capture_jobs
          WHERE status IN ('queued', 'fetching', 'downloading', 'committing', 'cancelling')
        '''),
      ) ??
      0;

  Future<int> failedJobCount() async =>
      Sqflite.firstIntValue(
        await _db.rawQuery(
          "SELECT COUNT(*) FROM capture_jobs WHERE status = 'failed'",
        ),
      ) ??
      0;

  Future<BackupStatus> readBackupStatus() async {
    final rows = await _db.query(
      'app_meta',
      columns: const ['value'],
      where: 'key = ?',
      whereArgs: const ['last_backup_error'],
      limit: 1,
    );
    return BackupStatus(
      lastError: rows.isEmpty ? null : rows.first['value'] as String?,
    );
  }

  Future<Map<String, BackupTargetStatus>> readBackupTargetStatuses(
    String postId,
  ) async {
    final rows = await _db.rawQuery(
      '''
      SELECT j.backup_target_id, j.status, j.last_error
      FROM r2_backup_jobs j
      JOIN posts p
        ON p.id = j.post_id
       AND p.backup_generation = j.generation
      WHERE j.post_id = ?
      ''',
      [postId],
    );
    return {
      for (final row in rows)
        row['backup_target_id']! as String: _backupTargetStatusFromRow(row),
    };
  }

  BackupTargetStatus _backupTargetStatusFromRow(Map<String, Object?> row) {
    if (row['status'] == 'completed') {
      return const BackupTargetStatus(state: BackupTargetState.completed);
    }
    final lastError = (row['last_error'] as String?)?.trim();
    if (lastError != null && lastError.isNotEmpty) {
      return BackupTargetStatus(
        state: BackupTargetState.failed,
        lastError: lastError,
      );
    }
    return const BackupTargetStatus(state: BackupTargetState.pending);
  }

  Future<Set<String>> _backedUpPostIds() async {
    final rows = await _db.rawQuery('''
      SELECT DISTINCT j.post_id
      FROM r2_backup_jobs j
      JOIN posts p
        ON p.id = j.post_id
       AND p.backup_generation = j.generation
      WHERE j.status = 'completed'
      ''');
    return rows.map((row) => row['post_id']! as String).toSet();
  }

  ArchivedPost _postFromRow(
    Map<String, Object?> row,
    List<PostMedia> media, {
    required bool isBackedUp,
  }) => ArchivedPost(
    id: row['id']! as String,
    sourcePlatform: PostSourcePlatform.parse(row['source_platform']),
    sourceUrl: row['source_url']! as String,
    authorUsername: row['author_username']! as String,
    authorDisplayName: row['author_display_name']! as String,
    caption: row['caption']! as String,
    publishedAt: row['published_at']! as int,
    locationName: row['location_name'] as String?,
    coverMediaId: row['cover_media_id']! as String,
    mediaCount: media.length,
    localAvatarPath: row['local_avatar_path'] as String?,
    media: media,
    isBackedUp: isBackedUp,
  );

  PostMedia _mediaFromRow(Map<String, Object?> row) => PostMedia(
    id: row['id']! as String,
    sortIndex: row['sort_index']! as int,
    logicalIndex: row['logical_index']! as int,
    mediaRole: PostMediaRole.parse(row['media_role']),
    mediaType: PostMediaType.parse(row['media_type']),
    mimeType: row['mime_type']! as String,
    width: row['width']! as int,
    height: row['height']! as int,
    localThumbnailPath: row['local_thumbnail_path'] as String?,
    localOriginalPath: row['local_original_path'] as String?,
  );

  List<PostMedia> _logicalMedia(List<PostMedia> physicalMedia) {
    final grouped = <int, List<PostMedia>>{};
    for (final media in physicalMedia) {
      grouped.putIfAbsent(media.logicalIndex, () => []).add(media);
    }
    final logical = <PostMedia>[];
    for (final entry
        in grouped.entries.toList()..sort((a, b) => a.key.compareTo(b.key))) {
      final visible = entry.value.where(
        (item) => item.mediaRole != PostMediaRole.liveMotion,
      );
      if (visible.isEmpty) continue;
      final primary = visible.first;
      final motion = entry.value.cast<PostMedia?>().firstWhere(
        (item) => item?.mediaRole == PostMediaRole.liveMotion,
        orElse: () => null,
      );
      logical.add(primary.copyWith(liveMotion: motion));
    }
    return logical;
  }
}
