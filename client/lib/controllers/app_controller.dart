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
  AppController({
    AppDatabase? database,
    ArchiveRuntimeBridge? runtimeBridge,
    bool? isAndroid,
  }) : _database = database ?? AppDatabase(),
       _runtimeBridge = runtimeBridge ?? ArchiveRuntimeBridge(),
       _isAndroid = isAndroid ?? Platform.isAndroid;

  final AppDatabase _database;
  final ArchiveRuntimeBridge _runtimeBridge;
  final bool _isAndroid;
  StreamSubscription<ArchiveRuntimeEvent>? _runtimeSubscription;
  Future<void>? _localReloadFuture;
  int _localReloadRevision = 0;
  bool _fullReloadRequested = false;
  bool _clipboardImportInProgress = false;
  bool _isForeground = false;

  AppPhase phase = AppPhase.initializing;
  List<ArchivedPost> posts = const [];
  List<ArchiveJob> tasks = const [];
  bool isSyncing = false;
  InstagramSessionSummary? instagramSession;
  R2ConfigSummary? r2Config;
  SyncStatus syncStatus = const SyncStatus();

  String? message;
  int messageRevision = 0;

  int get activeJobCount => tasks.where((job) => job.isActive).length;
  int get failedCount =>
      tasks.where((job) => job.status == ArchiveJobStatus.failed).length;
  int get taskCount => tasks.length;
  bool get hasRealFailures => tasks.any((job) => job.isFailure);
  int get savingCount => activeJobCount;
  bool get isImportingClipboard => _clipboardImportInProgress;

  Future<void> initialize() async {
    await _database.initialize();
    if (_isAndroid) {
      _runtimeSubscription = _runtimeBridge.events.listen(_handleRuntimeEvent);
    }
    await _reloadLocalState();
    if (_isAndroid) {
      try {
        final runtime = await _runtimeBridge.getRuntimeState();
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
    final shouldStart = _isAndroid && (activeJobCount > 0 || r2Config != null);
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

  Future<void> refreshTasks() => _reloadTaskState();

  Future<void> importClipboard({bool automatic = false}) async {
    if (!_isAndroid || _clipboardImportInProgress) return;
    _clipboardImportInProgress = true;
    notifyListeners();
    try {
      final outcome = await _runtimeBridge.importClipboard(
        automatic: automatic,
      );
      if (outcome == ClipboardImportOutcome.queued ||
          outcome == ClipboardImportOutcome.completed) {
        await _reloadTaskState();
      }
      if (!automatic) {
        switch (outcome) {
          case ClipboardImportOutcome.queued:
            _publishMessage('已添加到保存任务');
          case ClipboardImportOutcome.completed:
            _publishMessage('这条帖子已经保存');
          case ClipboardImportOutcome.empty:
          case ClipboardImportOutcome.unsupported:
            _publishMessage('剪贴板里没有支持的帖子链接');
          case ClipboardImportOutcome.unavailable:
            _publishMessage('暂时无法读取剪贴板，请稍后重试');
          case ClipboardImportOutcome.alreadyProcessed:
          case ClipboardImportOutcome.skipped:
          case ClipboardImportOutcome.stale:
            break;
        }
      }
    } on PlatformException catch (error) {
      if (!automatic) _publishMessage(error.message ?? '读取剪贴板失败');
    } finally {
      _clipboardImportInProgress = false;
      notifyListeners();
    }
  }

  Future<void> retryJob(ArchiveJob job) async {
    if (!_isAndroid) return;
    await _runtimeBridge.retryJob(job.id);
    await _reloadTaskState();
  }

  Future<void> cancelJob(ArchiveJob job) async {
    if (!_isAndroid) return;
    await _runtimeBridge.cancelJob(job.id);
    await _reloadTaskState();
  }

  Future<void> deleteJob(ArchiveJob job) async {
    if (!_isAndroid) return;
    await _runtimeBridge.deleteJob(job.id);
    await _reloadTaskState();
  }

  Future<void> beginInstagramLogin() async {
    if (!_isAndroid) throw StateError('Instagram 登录仅支持 Android');
    await _runtimeBridge.beginInstagramLogin();
  }

  Future<InstagramSessionSummary> captureInstagramSession() async {
    if (!_isAndroid) throw StateError('Instagram 登录仅支持 Android');
    final session = await _runtimeBridge.captureInstagramSession();
    instagramSession = session;
    notifyListeners();
    return session;
  }

  Future<void> cancelInstagramLogin() async {
    if (!_isAndroid) return;
    await _runtimeBridge.cancelInstagramLogin();
  }

  Future<void> copyInstagramCookies() async {
    if (!_isAndroid) throw StateError('Instagram Cookie 复制仅支持 Android');
    await _runtimeBridge.copyInstagramCookies();
  }

  Future<void> clearInstagramSession() async {
    if (!_isAndroid) return;
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
    if (!_isAndroid) throw StateError('原媒体恢复仅支持 Android');
    final localPath = await _runtimeBridge.ensureOriginal(media.id);
    await _reloadLocalState();
    return File(localPath);
  }

  Future<void> deletePost(String postId) async {
    if (!_isAndroid) throw StateError('帖子删除仅支持 Android');
    await _runtimeBridge.deletePost(postId);
    await _reloadLocalState();
  }

  Future<DeleteMediaSelectionResult> deleteMediaSelection(
    String postId,
    List<PostMedia> media,
  ) async {
    if (!_isAndroid) throw StateError('媒体删除仅支持 Android');
    final mediaIds = media.map((item) => item.id).toSet();
    final result = await _runtimeBridge.deleteMediaSelection(
      postId,
      mediaIds.toList(growable: false),
    );
    try {
      await _reloadLocalState();
    } on Object {
      _applyDeletedMediaSelection(postId, mediaIds, result.postDeleted);
      _publishMessage('删除已完成，但列表刷新失败，稍后会自动重试');
      unawaited(_retryLocalStateReload());
    }
    return result;
  }

  Future<void> shareMedia(
    List<PostMedia> media, {
    MediaExportMode exportMode = MediaExportMode.original,
  }) async {
    if (!_isAndroid) throw StateError('媒体分享仅支持 Android');
    await _runtimeBridge.shareMedia(
      media.map((item) => item.id).toList(),
      exportMode: exportMode.wireValue,
    );
    await _reloadLocalState();
  }

  Future<String> saveMedia(
    PostMedia media, {
    MediaExportMode exportMode = MediaExportMode.original,
  }) async {
    if (!_isAndroid) throw StateError('保存到系统相册仅支持 Android');
    return _runtimeBridge.saveMedia(media.id, exportMode: exportMode.wireValue);
  }

  Future<void> refreshAfterMediaSave() async {
    try {
      await _reloadLocalState();
    } on Object {
      _publishMessage('媒体已保存，但本地状态刷新失败');
      unawaited(_retryLocalStateReload());
    }
  }

  Future<void> setForeground(bool value) async {
    if (_isForeground == value) return;
    _isForeground = value;
    if (!value) return;
    await _reloadLocalState();
    await _reloadRuntimeState();
    await importClipboard(automatic: true);
    if (_isAndroid && (activeJobCount > 0 || r2Config != null)) {
      unawaited(_runtimeBridge.syncNow());
    }
  }

  Future<void> saveR2Config(R2ConfigInput config) async {
    if (!_isAndroid) throw StateError('R2 同步仅支持 Android');
    r2Config = await _runtimeBridge.saveR2Config(config);
    notifyListeners();
  }

  Future<void> clearR2Config() async {
    if (!_isAndroid) return;
    await _runtimeBridge.clearR2Config();
    r2Config = null;
    await _reloadLocalState();
  }

  Future<void> _reloadLocalState() {
    _fullReloadRequested = true;
    return _requestLocalReload();
  }

  Future<void> _reloadTaskState() => _requestLocalReload();

  Future<void> _requestLocalReload() {
    _localReloadRevision += 1;
    return _localReloadFuture ??= _drainLocalReloads();
  }

  Future<void> _drainLocalReloads() async {
    try {
      var handledRevision = -1;
      while (handledRevision != _localReloadRevision) {
        handledRevision = _localReloadRevision;
        final reloadAll = _fullReloadRequested;
        _fullReloadRequested = false;
        if (reloadAll) {
          final loadedPosts = await _database.listPosts();
          final loadedTasks = await _database.listVisibleJobs();
          final loadedSyncStatus = await _database.readSyncStatus();
          posts = loadedPosts;
          tasks = loadedTasks;
          syncStatus = loadedSyncStatus;
        } else {
          tasks = await _database.listVisibleJobs();
        }
        notifyListeners();
      }
    } finally {
      _localReloadFuture = null;
    }
  }

  void _applyDeletedMediaSelection(
    String postId,
    Set<String> mediaIds,
    bool postDeleted,
  ) {
    final postIndex = posts.indexWhere((post) => post.id == postId);
    if (postIndex < 0) return;
    if (postDeleted) {
      posts = [
        for (final post in posts)
          if (post.id != postId) post,
      ];
      notifyListeners();
      return;
    }

    final post = posts[postIndex];
    final remaining = post.media
        .where((item) => !mediaIds.contains(item.id))
        .toList(growable: false);
    if (remaining.isEmpty) return;
    final coverMediaId = remaining.any((item) => item.id == post.coverMediaId)
        ? post.coverMediaId
        : remaining.first.id;
    final updated = ArchivedPost(
      id: post.id,
      sourcePlatform: post.sourcePlatform,
      sourceUrl: post.sourceUrl,
      authorUsername: post.authorUsername,
      authorDisplayName: post.authorDisplayName,
      caption: post.caption,
      publishedAt: post.publishedAt,
      locationName: post.locationName,
      coverMediaId: coverMediaId,
      mediaCount: remaining.length,
      localAvatarPath: post.localAvatarPath,
      media: remaining,
    );
    posts = [
      for (final candidate in posts)
        if (candidate.id == postId) updated else candidate,
    ];
    notifyListeners();
  }

  Future<void> _retryLocalStateReload() async {
    await Future<void>.delayed(Duration.zero);
    try {
      await _reloadLocalState();
    } on Object {
      // A later foreground or archive event will request another refresh.
    }
  }

  Future<void> _reloadRuntimeState() async {
    if (!_isAndroid) return;
    try {
      final runtime = await _runtimeBridge.getRuntimeState();
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
      case ArchiveRuntimeEventType.jobChanged:
        unawaited(_reloadTaskState());
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
