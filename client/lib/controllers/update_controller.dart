import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../services/update_service.dart';

enum UpdateStatus {
  idle,
  checking,
  upToDate,
  available,
  downloading,
  readyToInstall,
  awaitingPermission,
  installing,
  failed,
}

class UpdateState {
  const UpdateState({
    this.status = UpdateStatus.idle,
    this.installedApp,
    this.manifest,
    this.downloadProgress,
    this.localPath,
    this.errorMessage,
    this.isManual = false,
  });

  final UpdateStatus status;
  final InstalledApp? installedApp;
  final UpdateManifest? manifest;
  final double? downloadProgress;
  final String? localPath;
  final String? errorMessage;
  final bool isManual;
}

class UpdateController extends ChangeNotifier {
  UpdateController({UpdateClient? client, bool? updatesSupported})
    : _client = client ?? UpdateService(),
      _updatesSupported = updatesSupported ?? Platform.isAndroid {
    if (_updatesSupported) {
      _runtimeSubscription = _client.runtimeEvents.listen(_handleRuntimeEvent);
    }
  }

  final UpdateClient _client;
  final bool _updatesSupported;
  StreamSubscription<UpdateRuntimeEvent>? _runtimeSubscription;
  bool _launchChecked = false;
  bool _checking = false;
  int _downloadOperation = 0;
  UpdateState _state = const UpdateState();

  UpdateState get state => _state;

  Future<void> checkOnLaunch() async {
    if (!_updatesSupported || _launchChecked) return;
    _launchChecked = true;
    await _check(manual: false);
  }

  Future<void> checkManually() async {
    if (!_updatesSupported || _checking) return;
    await _check(manual: true);
  }

  Future<void> _check({required bool manual}) async {
    if (_checking) return;
    _checking = true;
    _setState(
      UpdateState(
        status: UpdateStatus.checking,
        installedApp: _state.installedApp,
        isManual: manual,
      ),
    );
    InstalledApp? installedApp;
    try {
      installedApp = await _client.getInstalledApp();
      final manifest = await _client.checkForUpdate(installedApp);
      if (manifest == null) {
        _setState(
          UpdateState(
            status: manual ? UpdateStatus.upToDate : UpdateStatus.idle,
            installedApp: installedApp,
            isManual: manual,
          ),
        );
      } else {
        _setState(
          UpdateState(
            status: UpdateStatus.available,
            installedApp: installedApp,
            manifest: manifest,
            isManual: manual,
          ),
        );
      }
    } catch (error) {
      _setState(
        UpdateState(
          status: manual ? UpdateStatus.failed : UpdateStatus.idle,
          installedApp: installedApp ?? _state.installedApp,
          errorMessage: manual ? _messageFor(error) : null,
          isManual: manual,
        ),
      );
    } finally {
      _checking = false;
    }
  }

  Future<void> download() async {
    final manifest = _state.manifest;
    if (manifest == null || _state.status == UpdateStatus.downloading) return;
    final operation = ++_downloadOperation;
    _setState(
      UpdateState(
        status: UpdateStatus.downloading,
        installedApp: _state.installedApp,
        manifest: manifest,
        downloadProgress: 0,
        isManual: _state.isManual,
      ),
    );
    try {
      final path = await _client.downloadUpdate(manifest);
      if (operation != _downloadOperation) return;
      _setState(
        UpdateState(
          status: UpdateStatus.readyToInstall,
          installedApp: _state.installedApp,
          manifest: manifest,
          downloadProgress: 1,
          localPath: path,
          isManual: _state.isManual,
        ),
      );
    } catch (error) {
      if (operation != _downloadOperation) return;
      _setState(
        UpdateState(
          status: UpdateStatus.failed,
          installedApp: _state.installedApp,
          manifest: manifest,
          errorMessage: _messageFor(error),
          isManual: _state.isManual,
        ),
      );
    }
  }

  Future<void> cancelDownload() async {
    _downloadOperation += 1;
    try {
      await _client.cancelUpdate();
    } finally {
      reset();
    }
  }

  Future<void> install() async {
    final manifest = _state.manifest;
    final path = _state.localPath;
    if (manifest == null || path == null) return;
    _setState(
      UpdateState(
        status: UpdateStatus.installing,
        installedApp: _state.installedApp,
        manifest: manifest,
        localPath: path,
        isManual: _state.isManual,
      ),
    );
    try {
      final result = await _client.installUpdate(manifest, path);
      if (result == InstallUpdateResult.permissionRequired) {
        _setState(
          UpdateState(
            status: UpdateStatus.awaitingPermission,
            installedApp: _state.installedApp,
            manifest: manifest,
            localPath: path,
            isManual: _state.isManual,
          ),
        );
      }
    } catch (error) {
      _setState(
        UpdateState(
          status: UpdateStatus.failed,
          installedApp: _state.installedApp,
          manifest: manifest,
          localPath: path,
          errorMessage: _messageFor(error),
          isManual: _state.isManual,
        ),
      );
    }
  }

  void dismissAvailable() => reset();

  void reset() {
    _downloadOperation += 1;
    _setState(UpdateState(installedApp: _state.installedApp));
  }

  void _handleRuntimeEvent(UpdateRuntimeEvent event) {
    switch (event.type) {
      case UpdateRuntimeEventType.downloadProgress:
        if (_state.status != UpdateStatus.downloading) return;
        final progress = event.totalBytes <= 0
            ? null
            : (event.receivedBytes / event.totalBytes).clamp(0.0, 1.0);
        _setState(
          UpdateState(
            status: UpdateStatus.downloading,
            installedApp: _state.installedApp,
            manifest: _state.manifest,
            downloadProgress: progress,
            isManual: _state.isManual,
          ),
        );
      case UpdateRuntimeEventType.installFailed:
        _setState(
          UpdateState(
            status: UpdateStatus.failed,
            installedApp: _state.installedApp,
            manifest: _state.manifest,
            localPath: _state.localPath,
            errorMessage: event.errorMessage ?? '系统安装失败，请重试',
            isManual: _state.isManual,
          ),
        );
    }
  }

  String _messageFor(Object error) {
    if (error is UpdateException) return error.message;
    if (error is PlatformException) return error.message ?? '更新操作失败';
    return '更新操作失败，请稍后重试';
  }

  void _setState(UpdateState value) {
    _state = value;
    notifyListeners();
  }

  @override
  void dispose() {
    unawaited(_runtimeSubscription?.cancel());
    super.dispose();
  }
}
