import 'dart:io';

import 'package:yaml/yaml.dart';

void main(List<String> arguments) {
  if (arguments.length != 2) {
    stderr.writeln(
      '用法：dart run tool/release_metadata.dart <tag> <output-file>',
    );
    exitCode = 64;
    return;
  }

  final document = loadYaml(File('pubspec.yaml').readAsStringSync());
  if (document is! YamlMap || document['version'] is! String) {
    stderr.writeln('pubspec.yaml 缺少字符串 version');
    exitCode = 65;
    return;
  }
  final version = document['version']! as String;
  final match = RegExp(
    r'^([0-9]+\.[0-9]+\.[0-9]+)\+([1-9][0-9]*)$',
  ).firstMatch(version);
  if (match == null) {
    stderr.writeln('版本必须为 <major>.<minor>.<patch>+<positive-code>');
    exitCode = 65;
    return;
  }

  final versionName = match.group(1)!;
  final versionCode = match.group(2)!;
  final expectedTag = 'v$versionName+$versionCode';
  if (arguments[0] != expectedTag) {
    stderr.writeln('标签 ${arguments[0]} 与 pubspec.yaml 的 $expectedTag 不一致');
    exitCode = 65;
    return;
  }

  File(arguments[1]).writeAsStringSync(
    'version_name=$versionName\n'
    'version_code=$versionCode\n'
    'tag=$expectedTag\n',
    mode: FileMode.append,
  );
}
