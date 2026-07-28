import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_controller.dart';
import 'update_controller.dart';

final appControllerProvider = ChangeNotifierProvider<AppController>((ref) {
  throw UnimplementedError('AppController 必须在 main.dart 注入');
});

final updateControllerProvider = ChangeNotifierProvider<UpdateController>(
  (ref) => UpdateController(),
);
