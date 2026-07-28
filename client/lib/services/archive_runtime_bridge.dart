import 'dart:async';

import 'package:flutter/services.dart';

enum ArchiveRuntimeEventType { archiveChanged, runStarted, runFinished }

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
    this.r2Config,
  });

  final int activeJobCount;
  final int failedJobCount;
  final R2ConfigSummary? r2Config;

  factory ArchiveRuntimeState.fromMap(Map<Object?, Object?> map) =>
      ArchiveRuntimeState(
        activeJobCount: map['activeJobCount']! as int,
        failedJobCount: map['failedJobCount']! as int,
        r2Config: map['r2Config'] is Map<Object?, Object?>
            ? R2ConfigSummary.fromMap(map['r2Config']! as Map<Object?, Object?>)
            : null,
      );
}

class R2ConfigSummary {
  const R2ConfigSummary({
    required this.endpoint,
    required this.bucket,
    required this.prefix,
    required this.accessKeyIdHint,
  });

  final String endpoint;
  final String bucket;
  final String prefix;
  final String accessKeyIdHint;

  factory R2ConfigSummary.fromMap(Map<Object?, Object?> map) => R2ConfigSummary(
    endpoint: map['endpoint']! as String,
    bucket: map['bucket']! as String,
    prefix: map['prefix']! as String,
    accessKeyIdHint: map['accessKeyIdHint']! as String,
  );
}

class R2ConfigInput {
  const R2ConfigInput({
    required this.endpoint,
    required this.bucket,
    required this.prefix,
    required this.accessKeyId,
    required this.secretAccessKey,
  });

  final String endpoint;
  final String bucket;
  final String prefix;
  final String accessKeyId;
  final String secretAccessKey;

  Map<String, String> toMap() => {
    'endpoint': endpoint,
    'bucket': bucket,
    'prefix': prefix,
    'accessKeyId': accessKeyId,
    'secretAccessKey': secretAccessKey,
  };
}

class DeleteMediaResult {
  const DeleteMediaResult({
    required this.postId,
    required this.postDeleteRequired,
  });

  final String postId;
  final bool postDeleteRequired;

  factory DeleteMediaResult.fromMap(Map<Object?, Object?> map) =>
      DeleteMediaResult(
        postId: map['postId']! as String,
        postDeleteRequired: map['postDeleteRequired']! as bool,
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

  Future<void> retryJob(String jobId) =>
      _methodChannel.invokeMethod<void>('retryJob', {'jobId': jobId});

  Future<void> syncNow() => _methodChannel.invokeMethod<void>('syncNow');

  Future<R2ConfigSummary> saveR2Config(R2ConfigInput config) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'saveR2Config',
      config.toMap(),
    );
    if (value == null) throw StateError('R2 保存结果为空');
    return R2ConfigSummary.fromMap(value);
  }

  Future<void> clearR2Config() =>
      _methodChannel.invokeMethod<void>('clearR2Config');

  Future<String> ensureOriginal(String mediaId) async {
    final path = await _methodChannel.invokeMethod<String>('ensureOriginal', {
      'mediaId': mediaId,
    });
    if (path == null || path.isEmpty) throw StateError('原媒体下载结果为空');
    return path;
  }

  Future<void> deletePost(String postId) =>
      _methodChannel.invokeMethod<void>('deletePost', {'postId': postId});

  Future<DeleteMediaResult> deleteMedia(String mediaId) async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'deleteMedia',
      {'mediaId': mediaId},
    );
    if (value == null) throw StateError('媒体删除结果为空');
    return DeleteMediaResult.fromMap(value);
  }

  Future<void> shareMedia(List<String> mediaIds) =>
      _methodChannel.invokeMethod<void>('shareMedia', {'mediaIds': mediaIds});

  Future<String> saveMedia(String mediaId) async {
    final displayName = await _methodChannel.invokeMethod<String>('saveMedia', {
      'mediaId': mediaId,
    });
    if (displayName == null || displayName.isEmpty) {
      throw StateError('系统相册保存结果为空');
    }
    return displayName;
  }
}
