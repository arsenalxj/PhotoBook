import 'dart:async';

import 'package:flutter/services.dart';

enum ArchiveRuntimeEventType {
  archiveChanged,
  jobChanged,
  runStarted,
  runFinished,
}

enum ClipboardImportOutcome {
  queued,
  completed,
  empty,
  unsupported,
  alreadyProcessed,
  skipped,
  stale,
  unavailable;

  factory ClipboardImportOutcome.fromWireValue(String value) => switch (value) {
    'queued' => queued,
    'completed' => completed,
    'empty' => empty,
    'unsupported' => unsupported,
    'already_processed' => alreadyProcessed,
    'skipped' => skipped,
    'stale' => stale,
    'unavailable' => unavailable,
    _ => throw FormatException('未知剪贴板导入结果'),
  };
}

class ArchiveRuntimeEvent {
  const ArchiveRuntimeEvent({
    required this.type,
    required this.timestamp,
    this.error,
  });

  final ArchiveRuntimeEventType type;
  final int timestamp;
  final String? error;

  factory ArchiveRuntimeEvent.fromMap(Map<Object?, Object?> map) {
    final type = switch (map['type']) {
      'archiveChanged' => ArchiveRuntimeEventType.archiveChanged,
      'jobChanged' => ArchiveRuntimeEventType.jobChanged,
      'runStarted' => ArchiveRuntimeEventType.runStarted,
      'runFinished' => ArchiveRuntimeEventType.runFinished,
      _ => throw FormatException('未知原生运行事件'),
    };
    final timestamp = map['timestamp'];
    if (timestamp is! num) throw const FormatException('原生运行事件时间无效');
    return ArchiveRuntimeEvent(
      type: type,
      timestamp: timestamp.toInt(),
      error: (map['error'] as String?)?.trim().nullIfEmpty,
    );
  }
}

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}

class ArchiveRuntimeState {
  const ArchiveRuntimeState({
    required this.activeJobCount,
    required this.failedJobCount,
    required this.r2Settings,
    this.instagramSession,
  });

  final int activeJobCount;
  final int failedJobCount;
  final InstagramSessionSummary? instagramSession;
  final R2SettingsSummary r2Settings;

  factory ArchiveRuntimeState.fromMap(Map<Object?, Object?> map) =>
      ArchiveRuntimeState(
        activeJobCount: map['activeJobCount']! as int,
        failedJobCount: map['failedJobCount']! as int,
        instagramSession: map['instagramSession'] is Map<Object?, Object?>
            ? InstagramSessionSummary.fromMap(
                map['instagramSession']! as Map<Object?, Object?>,
              )
            : null,
        r2Settings: map['r2Settings'] is Map<Object?, Object?>
            ? R2SettingsSummary.fromMap(
                map['r2Settings']! as Map<Object?, Object?>,
              )
            : const R2SettingsSummary(),
      );
}

enum InstagramSessionStatus { ready, needsRefresh }

class InstagramSessionSummary {
  const InstagramSessionSummary({
    required this.status,
    required this.username,
    required this.validatedAt,
  });

  final InstagramSessionStatus status;
  final String username;
  final int validatedAt;

  factory InstagramSessionSummary.fromMap(Map<Object?, Object?> map) {
    final username = map['username'];
    final validatedAt = map['validatedAt'];
    if (username is! String || username.trim().isEmpty) {
      throw const FormatException('Instagram 用户名无效');
    }
    if (validatedAt is! num || validatedAt <= 0) {
      throw const FormatException('Instagram Session 验证时间无效');
    }
    final status = switch (map['status']) {
      'ready' => InstagramSessionStatus.ready,
      'needs_refresh' => InstagramSessionStatus.needsRefresh,
      _ => throw const FormatException('Instagram Session 状态无效'),
    };
    return InstagramSessionSummary(
      status: status,
      username: username.trim(),
      validatedAt: validatedAt.toInt(),
    );
  }
}

class R2ConnectionSummary {
  const R2ConnectionSummary({
    required this.connectionId,
    required this.endpoint,
    required this.bucket,
    required this.accessKeyIdHint,
    required this.targetCount,
  });

  final String connectionId;
  final String endpoint;
  final String bucket;
  final String accessKeyIdHint;
  final int targetCount;

  factory R2ConnectionSummary.fromMap(Map<Object?, Object?> map) =>
      R2ConnectionSummary(
        connectionId: map['connectionId']! as String,
        endpoint: map['endpoint']! as String,
        bucket: map['bucket']! as String,
        accessKeyIdHint: map['accessKeyIdHint']! as String,
        targetCount: (map['targetCount']! as num).toInt(),
      );
}

class R2BackupTargetSummary {
  const R2BackupTargetSummary({
    required this.targetId,
    required this.connectionId,
    required this.name,
    required this.endpoint,
    required this.bucket,
    required this.prefix,
  });

  final String targetId;
  final String connectionId;
  final String name;
  final String endpoint;
  final String bucket;
  final String prefix;

  factory R2BackupTargetSummary.fromMap(Map<Object?, Object?> map) =>
      R2BackupTargetSummary(
        targetId: map['targetId']! as String,
        connectionId: map['connectionId']! as String,
        name: map['name']! as String,
        endpoint: map['endpoint']! as String,
        bucket: map['bucket']! as String,
        prefix: map['prefix']! as String,
      );
}

class R2SettingsSummary {
  const R2SettingsSummary({
    this.connections = const [],
    this.targets = const [],
  });

  final List<R2ConnectionSummary> connections;
  final List<R2BackupTargetSummary> targets;

  bool get isEmpty => targets.isEmpty;

  factory R2SettingsSummary.fromMap(Map<Object?, Object?> map) =>
      R2SettingsSummary(
        connections: (map['connections'] as List<Object?>? ?? const [])
            .cast<Map<Object?, Object?>>()
            .map(R2ConnectionSummary.fromMap)
            .toList(growable: false),
        targets: (map['targets'] as List<Object?>? ?? const [])
            .cast<Map<Object?, Object?>>()
            .map(R2BackupTargetSummary.fromMap)
            .toList(growable: false),
      );

  R2ConnectionSummary? connection(String connectionId) {
    for (final connection in connections) {
      if (connection.connectionId == connectionId) return connection;
    }
    return null;
  }

  R2BackupTargetSummary? target(String targetId) {
    for (final target in targets) {
      if (target.targetId == targetId) return target;
    }
    return null;
  }
}

class R2ConnectionInput {
  const R2ConnectionInput({
    required this.endpoint,
    required this.bucket,
    required this.targetName,
    required this.prefix,
    required this.accessKeyId,
    required this.secretAccessKey,
  });

  final String endpoint;
  final String bucket;
  final String targetName;
  final String prefix;
  final String accessKeyId;
  final String secretAccessKey;

  Map<String, String> toMap() => {
    'endpoint': endpoint,
    'bucket': bucket,
    'targetName': targetName,
    'prefix': prefix,
    'accessKeyId': accessKeyId,
    'secretAccessKey': secretAccessKey,
  };
}

class R2CredentialsInput {
  const R2CredentialsInput({
    required this.connectionId,
    required this.endpoint,
    required this.bucket,
    required this.accessKeyId,
    required this.secretAccessKey,
  });

  final String connectionId;
  final String endpoint;
  final String bucket;
  final String accessKeyId;
  final String secretAccessKey;

  Map<String, String> toMap() => {
    'connectionId': connectionId,
    'endpoint': endpoint,
    'bucket': bucket,
    'accessKeyId': accessKeyId,
    'secretAccessKey': secretAccessKey,
  };
}

class R2TargetInput {
  const R2TargetInput({
    required this.connectionId,
    required this.name,
    required this.prefix,
    this.previousTargetId,
  });

  final String connectionId;
  final String name;
  final String prefix;
  final String? previousTargetId;

  Map<String, String> toMap() => {
    'connectionId': connectionId,
    'name': name,
    'prefix': prefix,
    'previousTargetId': ?previousTargetId,
  };
}

enum ManualBackupEnqueueStatus {
  queued,
  pending,
  completed;

  factory ManualBackupEnqueueStatus.parse(String value) => switch (value) {
    'queued' => queued,
    'pending' => pending,
    'completed' => completed,
    _ => throw FormatException('未知手动备份入队状态'),
  };
}

class DeleteMediaSelectionResult {
  const DeleteMediaSelectionResult({
    required this.postId,
    required this.postDeleted,
  });

  final String postId;
  final bool postDeleted;

  factory DeleteMediaSelectionResult.fromMap(Map<Object?, Object?> map) =>
      DeleteMediaSelectionResult(
        postId: map['postId']! as String,
        postDeleted: map['postDeleted']! as bool,
      );
}

class ArchiveRuntimeBridge {
  ArchiveRuntimeBridge({
    MethodChannel? methodChannel,
    EventChannel? eventChannel,
  }) : _methodChannel =
           methodChannel ?? const MethodChannel('com.mantou.photobook/archive'),
       _eventChannel =
           eventChannel ??
           const EventChannel('com.mantou.photobook/archive_events');

  final MethodChannel _methodChannel;
  final EventChannel _eventChannel;

  Stream<ArchiveRuntimeEvent> get events => _eventChannel
      .receiveBroadcastStream()
      .where((event) => event is Map<Object?, Object?>)
      .cast<Map<Object?, Object?>>()
      .map(ArchiveRuntimeEvent.fromMap)
      .handleError((Object _) {});

  Future<ArchiveRuntimeState> getRuntimeState() async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'getRuntimeState',
    );
    if (value == null) throw StateError('原生运行状态为空');
    return ArchiveRuntimeState.fromMap(value);
  }

  Future<ClipboardImportOutcome> importClipboard({
    required bool automatic,
  }) async {
    final value = await _methodChannel.invokeMethod<String>('importClipboard', {
      'automatic': automatic,
    });
    if (value == null) throw StateError('剪贴板导入结果为空');
    return ClipboardImportOutcome.fromWireValue(value);
  }

  Future<void> retryJob(String jobId) =>
      _methodChannel.invokeMethod<void>('retryJob', {'jobId': jobId});

  Future<void> cancelJob(String jobId) =>
      _methodChannel.invokeMethod<void>('cancelJob', {'jobId': jobId});

  Future<void> deleteJob(String jobId) =>
      _methodChannel.invokeMethod<void>('deleteJob', {'jobId': jobId});

  Future<void> copyJobSourceUrl(String jobId) =>
      _methodChannel.invokeMethod<void>('copyJobSourceUrl', {'jobId': jobId});

  Future<void> beginInstagramLogin() =>
      _methodChannel.invokeMethod<void>('beginInstagramLogin');

  Future<InstagramSessionSummary> captureInstagramSession() async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'captureInstagramSession',
    );
    if (value == null) throw StateError('Instagram 登录结果为空');
    return InstagramSessionSummary.fromMap(value);
  }

  Future<void> cancelInstagramLogin() =>
      _methodChannel.invokeMethod<void>('cancelInstagramLogin');

  Future<InstagramSessionSummary> importInstagramCookies(
    String cookieHeader,
  ) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'importInstagramCookies',
      {'cookieHeader': cookieHeader},
    );
    if (value == null) throw StateError('Instagram Cookie 登录结果为空');
    return InstagramSessionSummary.fromMap(value);
  }

  Future<void> copyInstagramCookies() =>
      _methodChannel.invokeMethod<void>('copyInstagramCookies');

  Future<void> clearInstagramSession() =>
      _methodChannel.invokeMethod<void>('clearInstagramSession');

  Future<void> resumeCaptureJobs() =>
      _methodChannel.invokeMethod<void>('resumeCaptureJobs');

  Future<void> resumeBackupJobs() =>
      _methodChannel.invokeMethod<void>('resumeBackupJobs');

  Future<R2SettingsSummary> saveR2Connection(R2ConnectionInput input) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'saveR2Connection',
      input.toMap(),
    );
    if (value == null) throw StateError('R2 连接保存结果为空');
    return R2SettingsSummary.fromMap(value);
  }

  Future<R2SettingsSummary> updateR2Connection(R2CredentialsInput input) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'updateR2Connection',
      input.toMap(),
    );
    if (value == null) throw StateError('R2 凭证更新结果为空');
    return R2SettingsSummary.fromMap(value);
  }

  Future<R2SettingsSummary> saveR2Target(R2TargetInput input) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'saveR2Target',
      input.toMap(),
    );
    if (value == null) throw StateError('R2 备份位置保存结果为空');
    return R2SettingsSummary.fromMap(value);
  }

  Future<R2SettingsSummary> deleteR2Target(String targetId) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'deleteR2Target',
      {'targetId': targetId},
    );
    if (value == null) throw StateError('R2 备份位置删除结果为空');
    return R2SettingsSummary.fromMap(value);
  }

  Future<R2SettingsSummary> deleteR2Connection(String connectionId) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'deleteR2Connection',
      {'connectionId': connectionId},
    );
    if (value == null) throw StateError('R2 连接删除结果为空');
    return R2SettingsSummary.fromMap(value);
  }

  Future<ManualBackupEnqueueStatus> enqueueR2Backup(
    String postId,
    String targetId,
  ) async {
    final value = await _methodChannel.invokeMethod<String>('enqueueR2Backup', {
      'postId': postId,
      'targetId': targetId,
    });
    if (value == null) throw StateError('R2 手动备份结果为空');
    return ManualBackupEnqueueStatus.parse(value);
  }

  Future<String> ensureOriginal(String mediaId) async {
    final path = await _methodChannel.invokeMethod<String>('ensureOriginal', {
      'mediaId': mediaId,
    });
    if (path == null || path.isEmpty) throw StateError('原媒体下载结果为空');
    return path;
  }

  Future<void> deletePost(String postId) =>
      _methodChannel.invokeMethod<void>('deletePost', {'postId': postId});

  Future<DeleteMediaSelectionResult> deleteMediaSelection(
    String postId,
    List<String> mediaIds,
  ) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'deleteMediaSelection',
      {'postId': postId, 'mediaIds': mediaIds},
    );
    if (value == null) throw StateError('媒体删除结果为空');
    return DeleteMediaSelectionResult.fromMap(value);
  }

  Future<void> shareMedia(
    List<String> mediaIds, {
    String exportMode = 'original',
  }) => _methodChannel.invokeMethod<void>('shareMedia', {
    'mediaIds': mediaIds,
    'exportMode': exportMode,
  });

  Future<String> saveMedia(
    String mediaId, {
    String exportMode = 'original',
  }) async {
    final displayName = await _methodChannel.invokeMethod<String>('saveMedia', {
      'mediaId': mediaId,
      'exportMode': exportMode,
    });
    if (displayName == null || displayName.isEmpty) {
      throw StateError('系统相册保存结果为空');
    }
    return displayName;
  }
}
