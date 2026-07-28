import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart';

typedef ManifestLoader = Future<String> Function(Uri uri);

const _applicationId = 'com.mantou.photobook';
const _manifestUri =
    'https://github.com/arsenalxj/PhotoBook/releases/latest/download/'
    'photobook-update.json';

class UpdateException implements Exception {
  const UpdateException(this.message);

  final String message;

  @override
  String toString() => message;
}

class InstalledApp {
  const InstalledApp({
    required this.applicationId,
    required this.versionName,
    required this.versionCode,
    required this.sdkInt,
    required this.canInstallPackages,
  });

  final String applicationId;
  final String versionName;
  final int versionCode;
  final int sdkInt;
  final bool canInstallPackages;

  factory InstalledApp.fromMap(Map<Object?, Object?> map) => InstalledApp(
    applicationId: _requiredString(map, 'applicationId'),
    versionName: _requiredString(map, 'versionName'),
    versionCode: _requiredInt(map, 'versionCode'),
    sdkInt: _requiredInt(map, 'sdkInt'),
    canInstallPackages: map['canInstallPackages'] == true,
  );
}

class UpdateAsset {
  const UpdateAsset({
    required this.name,
    required this.downloadUrl,
    required this.size,
    required this.sha256,
  });

  final String name;
  final Uri downloadUrl;
  final int size;
  final String sha256;
}

class UpdateManifest {
  const UpdateManifest({
    required this.schemaVersion,
    required this.applicationId,
    required this.versionName,
    required this.versionCode,
    required this.tag,
    required this.minSdk,
    required this.releaseNotes,
    required this.asset,
  });

  final int schemaVersion;
  final String applicationId;
  final String versionName;
  final int versionCode;
  final String tag;
  final int minSdk;
  final String releaseNotes;
  final UpdateAsset asset;

  factory UpdateManifest.parse(String source) {
    final Object? decoded;
    try {
      decoded = jsonDecode(source);
    } on FormatException {
      throw const UpdateException('更新清单不是有效 JSON');
    }
    if (decoded is! Map<String, dynamic>) {
      throw const UpdateException('更新清单格式无效');
    }

    final schemaVersion = _jsonInt(decoded, 'schema_version');
    final applicationId = _jsonString(decoded, 'application_id');
    final versionName = _jsonString(decoded, 'version_name');
    final versionCode = _jsonInt(decoded, 'version_code');
    final tag = _jsonString(decoded, 'tag');
    final minSdk = _jsonInt(decoded, 'min_sdk');
    final releaseNotes = _jsonString(
      decoded,
      'release_notes',
      allowEmpty: true,
    );
    final assetValue = decoded['asset'];
    if (assetValue is! Map<String, dynamic>) {
      throw const UpdateException('更新清单缺少 APK 信息');
    }
    final assetName = _jsonString(assetValue, 'name');
    final downloadUrl = Uri.tryParse(_jsonString(assetValue, 'download_url'));
    final size = _jsonInt(assetValue, 'size');
    final sha256 = _jsonString(assetValue, 'sha256');

    if (schemaVersion != 1) {
      throw const UpdateException('不支持此更新清单版本');
    }
    if (applicationId != _applicationId) {
      throw const UpdateException('更新包不属于 PhotoBook');
    }
    if (versionCode <= 0 || minSdk <= 0) {
      throw const UpdateException('更新版本信息无效');
    }
    final expectedTag = 'v$versionName+$versionCode';
    if (tag != expectedTag) {
      throw const UpdateException('更新标签与版本不一致');
    }
    final expectedAssetName = 'photobook-$tag-arm64-v8a.apk';
    if (assetName != expectedAssetName) {
      throw const UpdateException('更新文件名与版本不一致');
    }
    if (downloadUrl == null ||
        !_isTrustedAssetUrl(downloadUrl, tag, assetName)) {
      throw const UpdateException('更新下载地址不可信');
    }
    if (size <= 0) throw const UpdateException('更新文件大小无效');
    if (!RegExp(r'^[0-9a-f]{64}$').hasMatch(sha256)) {
      throw const UpdateException('更新文件 SHA-256 无效');
    }

    return UpdateManifest(
      schemaVersion: schemaVersion,
      applicationId: applicationId,
      versionName: versionName,
      versionCode: versionCode,
      tag: tag,
      minSdk: minSdk,
      releaseNotes: releaseNotes,
      asset: UpdateAsset(
        name: assetName,
        downloadUrl: downloadUrl,
        size: size,
        sha256: sha256,
      ),
    );
  }

  static bool _isTrustedAssetUrl(Uri uri, String tag, String assetName) {
    if (uri.scheme != 'https' ||
        uri.host != 'github.com' ||
        uri.hasPort ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment) {
      return false;
    }
    final expectedSegments = [
      'arsenalxj',
      'PhotoBook',
      'releases',
      'download',
      tag,
      assetName,
    ];
    final segments = uri.pathSegments;
    if (segments.length != expectedSegments.length) return false;
    for (var index = 0; index < segments.length; index += 1) {
      if (segments[index] != expectedSegments[index]) return false;
    }
    return true;
  }
}

enum UpdateRuntimeEventType { downloadProgress, installFailed }

class UpdateRuntimeEvent {
  const UpdateRuntimeEvent.downloadProgress(this.receivedBytes, this.totalBytes)
    : type = UpdateRuntimeEventType.downloadProgress,
      errorMessage = null;

  const UpdateRuntimeEvent.installFailed(this.errorMessage)
    : type = UpdateRuntimeEventType.installFailed,
      receivedBytes = 0,
      totalBytes = 0;

  final UpdateRuntimeEventType type;
  final int receivedBytes;
  final int totalBytes;
  final String? errorMessage;

  factory UpdateRuntimeEvent.fromMap(Map<Object?, Object?> map) {
    return switch (map['type']) {
      'downloadProgress' => UpdateRuntimeEvent.downloadProgress(
        _requiredInt(map, 'receivedBytes'),
        _requiredInt(map, 'totalBytes'),
      ),
      'installFailed' => UpdateRuntimeEvent.installFailed(
        _requiredString(map, 'message'),
      ),
      _ => throw const FormatException('未知更新事件'),
    };
  }
}

enum InstallUpdateResult { installing, permissionRequired }

abstract interface class UpdateRuntime {
  Stream<UpdateRuntimeEvent> get events;

  Future<InstalledApp> getInstalledApp();

  Future<String> downloadUpdate(UpdateManifest manifest);

  Future<void> cancelUpdate();

  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  );
}

class UpdateRuntimeBridge implements UpdateRuntime {
  UpdateRuntimeBridge({
    MethodChannel? methodChannel,
    EventChannel? eventChannel,
  }) : _methodChannel =
           methodChannel ?? const MethodChannel('com.mantou.photobook/update'),
       _eventChannel =
           eventChannel ??
           const EventChannel('com.mantou.photobook/update_events');

  final MethodChannel _methodChannel;
  final EventChannel _eventChannel;

  @override
  Stream<UpdateRuntimeEvent> get events => _eventChannel
      .receiveBroadcastStream()
      .where((event) => event is Map<Object?, Object?>)
      .cast<Map<Object?, Object?>>()
      .map(UpdateRuntimeEvent.fromMap)
      .handleError((Object _) {});

  @override
  Future<InstalledApp> getInstalledApp() async {
    final value = await _methodChannel.invokeMapMethod<Object?, Object?>(
      'getInstalledApp',
    );
    if (value == null) throw const UpdateException('无法读取当前 App 版本');
    return InstalledApp.fromMap(value);
  }

  @override
  Future<String> downloadUpdate(UpdateManifest manifest) async {
    final path = await _methodChannel.invokeMethod<String>('downloadUpdate', {
      'downloadUrl': manifest.asset.downloadUrl.toString(),
      'fileName': manifest.asset.name,
      'size': manifest.asset.size,
      'sha256': manifest.asset.sha256,
      'versionCode': manifest.versionCode,
    });
    if (path == null || path.isEmpty) {
      throw const UpdateException('更新下载结果为空');
    }
    return path;
  }

  @override
  Future<void> cancelUpdate() =>
      _methodChannel.invokeMethod<void>('cancelUpdate');

  @override
  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  ) async {
    final result = await _methodChannel.invokeMethod<String>('installUpdate', {
      'path': path,
      'size': manifest.asset.size,
      'sha256': manifest.asset.sha256,
      'versionCode': manifest.versionCode,
    });
    return switch (result) {
      'installing' => InstallUpdateResult.installing,
      'permissionRequired' => InstallUpdateResult.permissionRequired,
      _ => throw const UpdateException('系统安装器返回了未知状态'),
    };
  }
}

abstract interface class UpdateClient {
  Stream<UpdateRuntimeEvent> get runtimeEvents;

  Future<InstalledApp> getInstalledApp();

  Future<UpdateManifest?> checkForUpdate(InstalledApp installedApp);

  Future<String> downloadUpdate(UpdateManifest manifest);

  Future<void> cancelUpdate();

  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  );
}

class UpdateService implements UpdateClient {
  UpdateService({UpdateRuntime? runtime, ManifestLoader? manifestLoader})
    : _runtime = runtime ?? UpdateRuntimeBridge(),
      _manifestLoader = manifestLoader ?? _loadManifest;

  final UpdateRuntime _runtime;
  final ManifestLoader _manifestLoader;

  @override
  Stream<UpdateRuntimeEvent> get runtimeEvents => _runtime.events;

  @override
  Future<InstalledApp> getInstalledApp() => _runtime.getInstalledApp();

  @override
  Future<UpdateManifest?> checkForUpdate(InstalledApp installedApp) async {
    if (installedApp.applicationId != _applicationId) {
      throw const UpdateException('当前安装包不是 PhotoBook 正式包');
    }
    final source = await _manifestLoader(Uri.parse(_manifestUri));
    final manifest = UpdateManifest.parse(source);
    if (manifest.minSdk > installedApp.sdkInt) {
      throw UpdateException('新版本需要 Android API ${manifest.minSdk} 或更高版本');
    }
    if (manifest.versionCode <= installedApp.versionCode) return null;
    return manifest;
  }

  @override
  Future<String> downloadUpdate(UpdateManifest manifest) =>
      _runtime.downloadUpdate(manifest);

  @override
  Future<void> cancelUpdate() => _runtime.cancelUpdate();

  @override
  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  ) => _runtime.installUpdate(manifest, path);

  static Future<String> _loadManifest(Uri uri) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client
          .getUrl(uri)
          .timeout(const Duration(seconds: 10));
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.followRedirects = true;
      request.maxRedirects = 5;
      final response = await request.close().timeout(
        const Duration(seconds: 10),
      );
      if (response.statusCode != HttpStatus.ok) {
        throw UpdateException('检查更新失败：HTTP ${response.statusCode}');
      }
      if (response.contentLength > 64 * 1024) {
        throw const UpdateException('更新清单过大');
      }
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in response.timeout(const Duration(seconds: 10))) {
        bytes.add(chunk);
        if (bytes.length > 64 * 1024) {
          throw const UpdateException('更新清单过大');
        }
      }
      return utf8.decode(bytes.takeBytes());
    } on UpdateException {
      rethrow;
    } on TimeoutException {
      throw const UpdateException('检查更新超时，请稍后重试');
    } on SocketException {
      throw const UpdateException('无法连接 GitHub，请检查网络');
    } on FormatException {
      throw const UpdateException('更新清单编码无效');
    } finally {
      client.close(force: true);
    }
  }
}

String _requiredString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! String || value.trim().isEmpty) {
    throw FormatException('$key 无效');
  }
  return value;
}

int _requiredInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! int) throw FormatException('$key 无效');
  return value;
}

String _jsonString(
  Map<String, dynamic> map,
  String key, {
  bool allowEmpty = false,
}) {
  final value = map[key];
  if (value is! String || (!allowEmpty && value.trim().isEmpty)) {
    throw UpdateException('更新清单字段 $key 无效');
  }
  return value;
}

int _jsonInt(Map<String, dynamic> map, String key) {
  final value = map[key];
  if (value is! int) throw UpdateException('更新清单字段 $key 无效');
  return value;
}
