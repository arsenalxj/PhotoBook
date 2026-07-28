import 'package:flutter/material.dart';

class AppTheme {
  static const accent = Color(0xFFE84A3C);
  static const ink = Color(0xFF202124);
  static const muted = Color(0xFF70757A);
  static const canvas = Color(0xFFF6F7F8);
  static const divider = Color(0xFFE3E5E7);

  static ThemeData get light {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: Brightness.light,
      primary: accent,
      secondary: const Color(0xFF217A66),
      surface: Colors.white,
      error: const Color(0xFFB3261E),
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: canvas,
      appBarTheme: const AppBarTheme(
        backgroundColor: canvas,
        foregroundColor: ink,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(
          color: ink,
          fontSize: 21,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
      ),
      cardTheme: const CardThemeData(
        color: Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(6)),
          side: BorderSide(color: divider),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: ink,
        contentTextStyle: TextStyle(
          color: Colors.white,
          fontSize: 14,
          letterSpacing: 0,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(6)),
        ),
      ),
      dividerColor: divider,
      textTheme: const TextTheme(
        bodyLarge: TextStyle(color: ink, letterSpacing: 0),
        bodyMedium: TextStyle(color: ink, letterSpacing: 0),
        bodySmall: TextStyle(color: muted, letterSpacing: 0),
        titleLarge: TextStyle(color: ink, letterSpacing: 0),
        titleMedium: TextStyle(color: ink, letterSpacing: 0),
        titleSmall: TextStyle(color: ink, letterSpacing: 0),
      ),
    );
  }
}
