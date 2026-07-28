import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:photobook/controllers/update_controller.dart';
import 'package:photobook/services/update_service.dart';

void main() {
  late _FakeUpdateClient client;
  late UpdateController controller;

  setUp(() {
    client = _FakeUpdateClient();
    controller = UpdateController(client: client, updatesSupported: true);
  });

  tearDown(() async {
    controller.dispose();
    await client.close();
  });

  test(
    'cold start exposes an available update without downloading it',
    () async {
      client.availableUpdate = _manifest;

      await controller.checkOnLaunch();

      expect(controller.state.status, UpdateStatus.available);
      expect(controller.state.manifest, _manifest);
      expect(client.downloadCalls, 0);
    },
  );

  test('cold start check failure remains silent', () async {
    client.checkError = const UpdateException('网络不可用');

    await controller.checkOnLaunch();

    expect(controller.state.status, UpdateStatus.idle);
    expect(controller.state.errorMessage, isNull);
  });

  test('manual check reports up-to-date state', () async {
    await controller.checkManually();

    expect(controller.state.status, UpdateStatus.upToDate);
    expect(controller.state.isManual, isTrue);
    expect(controller.state.installedApp, _installed);
  });

  test('download reports progress and keeps verified local path', () async {
    client.availableUpdate = _manifest;
    await controller.checkManually();
    final completed = Completer<String>();
    client.downloadResult = completed.future;

    final download = controller.download();
    client.events.add(const UpdateRuntimeEvent.downloadProgress(50, 100));
    await Future<void>.delayed(Duration.zero);

    expect(controller.state.status, UpdateStatus.downloading);
    expect(controller.state.downloadProgress, 0.5);

    completed.complete('/cache/updates/photobook.apk');
    await download;
    expect(controller.state.status, UpdateStatus.readyToInstall);
    expect(controller.state.localPath, '/cache/updates/photobook.apk');
  });

  test('installer permission is represented as awaitingPermission', () async {
    client.availableUpdate = _manifest;
    client.installResult = InstallUpdateResult.permissionRequired;
    await controller.checkManually();
    await controller.download();

    await controller.install();

    expect(controller.state.status, UpdateStatus.awaitingPermission);
  });

  test('cancelled download ignores a late native failure', () async {
    client.availableUpdate = _manifest;
    await controller.checkManually();
    final completed = Completer<String>();
    client.downloadResult = completed.future;
    final download = controller.download();

    await controller.cancelDownload();
    completed.completeError(const UpdateException('连接已关闭'));
    await download;

    expect(controller.state.status, UpdateStatus.idle);
    expect(controller.state.errorMessage, isNull);
  });
}

const _installed = InstalledApp(
  applicationId: 'com.mantou.photobook',
  versionName: '1.0.0',
  versionCode: 1,
  sdkInt: 33,
  canInstallPackages: false,
);

final _manifest = UpdateManifest(
  schemaVersion: 1,
  applicationId: 'com.mantou.photobook',
  versionName: '1.1.0',
  versionCode: 2,
  tag: 'v1.1.0+2',
  minSdk: 24,
  releaseNotes: '更新说明',
  asset: UpdateAsset(
    name: 'photobook-v1.1.0+2-arm64-v8a.apk',
    downloadUrl: Uri.parse(
      'https://github.com/arsenalxj/PhotoBook/releases/download/'
      'v1.1.0+2/photobook-v1.1.0+2-arm64-v8a.apk',
    ),
    size: 100,
    sha256: _sha256,
  ),
);

const _sha256 =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

class _FakeUpdateClient implements UpdateClient {
  final events = StreamController<UpdateRuntimeEvent>.broadcast();
  UpdateManifest? availableUpdate;
  UpdateException? checkError;
  Future<String>? downloadResult;
  InstallUpdateResult installResult = InstallUpdateResult.installing;
  int downloadCalls = 0;

  Future<void> close() => events.close();

  @override
  Stream<UpdateRuntimeEvent> get runtimeEvents => events.stream;

  @override
  Future<void> cancelUpdate() async {}

  @override
  Future<UpdateManifest?> checkForUpdate(InstalledApp installedApp) async {
    final error = checkError;
    if (error != null) throw error;
    return availableUpdate;
  }

  @override
  Future<String> downloadUpdate(UpdateManifest manifest) {
    downloadCalls += 1;
    return downloadResult ?? Future.value('/cache/updates/photobook.apk');
  }

  @override
  Future<InstalledApp> getInstalledApp() async => _installed;

  @override
  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  ) async => installResult;
}
