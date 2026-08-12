import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../models/post.dart';

class PostBackupIndicator extends StatelessWidget {
  const PostBackupIndicator({
    required this.state,
    required this.dimension,
    required this.iconSize,
    required this.backgroundColor,
    required this.foregroundColor,
    required this.progressKey,
    required this.successKey,
    super.key,
  });

  final PostBackupState state;
  final double dimension;
  final double iconSize;
  final Color backgroundColor;
  final Color foregroundColor;
  final Key progressKey;
  final Key successKey;

  @override
  Widget build(BuildContext context) => switch (state) {
    PostBackupState.notBackedUp => const SizedBox.shrink(),
    PostBackupState.backingUp => Tooltip(
      message: '正在备份到 R2',
      child: DecoratedBox(
        key: progressKey,
        decoration: BoxDecoration(
          color: backgroundColor,
          borderRadius: BorderRadius.circular(999),
        ),
        child: SizedBox.square(
          dimension: dimension,
          child: Center(
            child: SizedBox.square(
              dimension: iconSize,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: foregroundColor,
              ),
            ),
          ),
        ),
      ),
    ),
    PostBackupState.completed => Tooltip(
      message: '已备份到 R2',
      child: DecoratedBox(
        key: successKey,
        decoration: BoxDecoration(
          color: backgroundColor,
          borderRadius: BorderRadius.circular(999),
        ),
        child: SizedBox.square(
          dimension: dimension,
          child: Icon(
            LucideIcons.cloudCheck,
            size: iconSize,
            color: foregroundColor,
          ),
        ),
      ),
    ),
  };
}
