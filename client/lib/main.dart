import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'controllers/app_controller.dart';
import 'controllers/providers.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final controller = AppController();
  await controller.initialize();
  runApp(
    ProviderScope(
      overrides: [appControllerProvider.overrideWith((ref) => controller)],
      child: const PhotoBookApp(),
    ),
  );
}
