import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:photobook/services/update_service.dart';

void main() {
  const installed = InstalledApp(
    applicationId: 'com.mantou.photobook',
    versionName: '1.0.0',
    versionCode: 1,
    sdkInt: 33,
    canInstallPackages: false,
  );

  test(
    'accepts a newer manifest from the fixed PhotoBook release path',
    () async {
      final runtime = _FakeUpdateRuntime(installed);
      final service = UpdateService(
        runtime: runtime,
        manifestLoader: (_) async => jsonEncode(_manifest()),
      );

      final update = await service.checkForUpdate(installed);

      expect(update, isNotNull);
      expect(update!.versionCode, 2);
      expect(update.asset.size, 123456);
      expect(update.asset.sha256, _sha256);
    },
  );

  test('returns null when latest versionCode is not newer', () async {
    final runtime = _FakeUpdateRuntime(installed);
    final service = UpdateService(
      runtime: runtime,
      manifestLoader: (_) async => jsonEncode(_manifest(versionCode: 1)),
    );

    expect(await service.checkForUpdate(installed), isNull);
  });

  test('rejects a manifest for another application', () async {
    final runtime = _FakeUpdateRuntime(installed);
    final service = UpdateService(
      runtime: runtime,
      manifestLoader: (_) async =>
          jsonEncode(_manifest(applicationId: 'com.example.other')),
    );

    expect(
      () => service.checkForUpdate(installed),
      throwsA(isA<UpdateException>()),
    );
  });

  test('rejects an APK URL outside the fixed public repository', () async {
    final runtime = _FakeUpdateRuntime(installed);
    final manifest = _manifest();
    final asset = manifest['asset']! as Map<String, Object>;
    asset['download_url'] =
        'https://example.com/v1.1.0/photobook-v1.1.0+2-arm64-v8a.apk';
    final service = UpdateService(
      runtime: runtime,
      manifestLoader: (_) async => jsonEncode(manifest),
    );

    expect(
      () => service.checkForUpdate(installed),
      throwsA(isA<UpdateException>()),
    );
  });

  test(
    'rejects an update which does not support the current Android SDK',
    () async {
      final runtime = _FakeUpdateRuntime(installed);
      final service = UpdateService(
        runtime: runtime,
        manifestLoader: (_) async => jsonEncode(_manifest(minSdk: 34)),
      );

      expect(
        () => service.checkForUpdate(installed),
        throwsA(
          isA<UpdateException>().having(
            (error) => error.message,
            'message',
            contains('Android'),
          ),
        ),
      );
    },
  );
}

Map<String, Object> _manifest({
  String applicationId = 'com.mantou.photobook',
  int versionCode = 2,
  int minSdk = 24,
}) {
  const versionName = '1.1.0';
  final tag = 'v$versionName+$versionCode';
  final assetName = 'photobook-$tag-arm64-v8a.apk';
  return {
    'schema_version': 1,
    'application_id': applicationId,
    'version_name': versionName,
    'version_code': versionCode,
    'tag': tag,
    'min_sdk': minSdk,
    'release_notes': '首个 PhotoBook 正式版本',
    'asset': {
      'name': assetName,
      'download_url':
          'https://github.com/arsenalxj/PhotoBook/releases/download/$tag/$assetName',
      'size': 123456,
      'sha256': _sha256,
    },
  };
}

const _sha256 =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

class _FakeUpdateRuntime implements UpdateRuntime {
  _FakeUpdateRuntime(this.installed);

  final InstalledApp installed;
  final _events = StreamController<UpdateRuntimeEvent>.broadcast();

  @override
  Stream<UpdateRuntimeEvent> get events => _events.stream;

  @override
  Future<void> cancelUpdate() async {}

  @override
  Future<String> downloadUpdate(UpdateManifest manifest) async =>
      '/tmp/update.apk';

  @override
  Future<InstalledApp> getInstalledApp() async => installed;

  @override
  Future<InstallUpdateResult> installUpdate(
    UpdateManifest manifest,
    String path,
  ) async => InstallUpdateResult.installing;
}
