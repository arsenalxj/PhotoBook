import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../core/database/app_database.dart';
import '../models/archive_job.dart';
import '../models/post.dart';
import '../services/archive_runtime_bridge.dart';

enum AppPhase { initializing, ready }

class AppController extends ChangeNotifier {
  AppController({AppDatabase? database, ArchiveRuntimeBridge? runtimeBridge})
    : _database = database ?? AppDatabase(),
      _runtimeBridge = runtimeBridge ?? ArchiveRuntimeBridge();

  final AppDatabase _database;
  final ArchiveRuntimeBridge _runtimeBridge;
  StreamSubscription<ArchiveRuntimeEvent>? _runtimeSubscription;

  AppPhase phase = AppPhase.initializing;
  List<ArchivedPost> posts = const [];
  int activeJobCount = 0;
  int failedCount = 0;
  bool isSyncing = false;
  InstagramSessionSummary? instagramSession;
  R2ConfigSummary? r2Config;
  SyncStatus syncStatus = const SyncStatus();

  String? message;
  int messageRevision = 0;

  int get savingCount => activeJobCount;

  Future<void> initialize() async {
    await _database.initialize();
    await _reloadLocalState();
    if (Platform.isAndroid) {
      _runtimeSubscription = _runtimeBridge.events.listen(_handleRuntimeEvent);
      try {
        final runtime = await _runtimeBridge.getRuntimeState();
        activeJobCount = runtime.activeJobCount;
        failedCount = runtime.failedJobCount;
        instagramSession = runtime.instagramSession;
        r2Config = runtime.r2Config;
        if (activeJobCount > 0 || r2Config != null) {
          unawaited(_runtimeBridge.syncNow());
        }
      } on PlatformException catch (error) {
        _publishMessage(error.message ?? '原生归档服务初始化失败');
      }
    }
    phase = AppPhase.ready;
    notifyListeners();
  }

  Future<void> synchronize({bool showErrors = true}) async {
    if (isSyncing) return;
    final shouldStart =
        Platform.isAndroid && (activeJobCount > 0 || r2Config != null);
    if (shouldStart) {
      isSyncing = true;
      notifyListeners();
    }
    try {
      if (shouldStart) {
        await _runtimeBridge.syncNow();
      }
      await _reloadLocalState();
    } on PlatformException catch (error) {
      isSyncing = false;
      if (showErrors) _publishMessage(error.message ?? '同步启动失败');
    }
  }

  Future<List<ArchiveJob>> loadAllFailures() => _database.listFailedJobs();

  Future<void> retryJob(ArchiveJob job) async {
    if (!Platform.isAndroid) return;
    await _runtimeBridge.retryJob(job.id);
    await _reloadLocalState();
  }

  Future<void> beginInstagramLogin() async {
    if (!Platform.isAndroid) throw StateError('Instagram 登录仅支持 Android');
    await _runtimeBridge.beginInstagramLogin();
  }

  Future<InstagramSessionSummary> captureInstagramSession() async {
    if (!Platform.isAndroid) throw StateError('Instagram 登录仅支持 Android');
    final session = await _runtimeBridge.captureInstagramSession();
    instagramSession = session;
    notifyListeners();
    return session;
  }

  Future<void> cancelInstagramLogin() async {
    if (!Platform.isAndroid) return;
    await _runtimeBridge.cancelInstagramLogin();
  }

  Future<void> clearInstagramSession() async {
    if (!Platform.isAndroid) return;
    await _runtimeBridge.clearInstagramSession();
    instagramSession = null;
    notifyListeners();
  }

  Future<File> ensureOriginal(PostMedia media) async {
    final existingPath = media.localOriginalPath;
    if (existingPath != null) {
      final existing = File(existingPath);
      if (await existing.exists()) return existing;
    }
    if (!Platform.isAndroid) throw StateError('原媒体恢复仅支持 Android');
    final localPath = await _runtimeBridge.ensureOriginal(media.id);
    await _reloadLocalState();
    return File(localPath);
  }

  Future<void> deletePost(String postId) async {
    if (!Platform.isAndroid) throw StateError('帖子删除仅支持 Android');
    await _runtimeBridge.deletePost(postId);
    await _reloadLocalState();
  }

  Future<DeleteMediaResult> deleteMedia(String mediaId) async {
    if (!Platform.isAndroid) throw StateError('媒体删除仅支持 Android');
    final result = await _runtimeBridge.deleteMedia(mediaId);
    await _reloadLocalState();
    return result;
  }

  Future<void> shareMedia(List<PostMedia> media) async {
    if (!Platform.isAndroid) throw StateError('媒体分享仅支持 Android');
    await _runtimeBridge.shareMedia(media.map((item) => item.id).toList());
    await _reloadLocalState();
  }

  Future<String> saveMedia(PostMedia media) async {
    if (!Platform.isAndroid) throw StateError('保存到系统相册仅支持 Android');
    final displayName = await _runtimeBridge.saveMedia(media.id);
    await _reloadLocalState();
    return displayName;
  }

  Future<void> setForeground(bool value) async {
    if (!value) return;
    await _reloadLocalState();
    await _reloadRuntimeState();
    if (Platform.isAndroid && (activeJobCount > 0 || r2Config != null)) {
      unawaited(_runtimeBridge.syncNow());
    }
  }

  Future<void> saveR2Config(R2ConfigInput config) async {
    if (!Platform.isAndroid) throw StateError('R2 同步仅支持 Android');
    r2Config = await _runtimeBridge.saveR2Config(config);
    notifyListeners();
  }

  Future<void> clearR2Config() async {
    if (!Platform.isAndroid) return;
    await _runtimeBridge.clearR2Config();
    r2Config = null;
    await _reloadLocalState();
  }

  Future<void> _reloadLocalState() async {
    posts = await _database.listPosts();
    activeJobCount = await _database.activeJobCount();
    failedCount = await _database.failedJobCount();
    syncStatus = await _database.readSyncStatus();
    notifyListeners();
  }

  Future<void> _reloadRuntimeState() async {
    if (!Platform.isAndroid) return;
    try {
      final runtime = await _runtimeBridge.getRuntimeState();
      activeJobCount = runtime.activeJobCount;
      failedCount = runtime.failedJobCount;
      instagramSession = runtime.instagramSession;
      r2Config = runtime.r2Config;
      notifyListeners();
    } on PlatformException catch (error) {
      _publishMessage(error.message ?? '原生归档状态刷新失败');
    }
  }

  void _handleRuntimeEvent(ArchiveRuntimeEvent event) {
    switch (event.type) {
      case ArchiveRuntimeEventType.runStarted:
        isSyncing = true;
        notifyListeners();
      case ArchiveRuntimeEventType.archiveChanged:
        unawaited(_reloadLocalState());
      case ArchiveRuntimeEventType.runFinished:
        isSyncing = false;
        unawaited(_reloadLocalState());
        unawaited(_reloadRuntimeState());
        final error = event.error;
        if (error != null) _publishMessage(error);
    }
  }

  void _publishMessage(String value) {
    message = value;
    messageRevision += 1;
    notifyListeners();
  }

  @override
  void dispose() {
    unawaited(_runtimeSubscription?.cancel());
    unawaited(_database.close());
    super.dispose();
  }
}
