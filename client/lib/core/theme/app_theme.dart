import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

abstract final class AppTheme {
  // handoff.md is the single source of truth for every UI color.
  static const background = Color(0xFFFFFFFF);
  static const surface = Color(0xFFFFFFFF);
  static const foreground = Color(0xFF111827);
  static const muted = Color(0xFF64748B);
  static const border = Color(0xFFE5E7EB);
  static const accent = Color(0xFF000000);
  static const accentOn = Color(0xFFFFFFFF);
  static const accentHover = Color(0xFF1A1A1A);
  static const accentActive = Color(0xFF2E2E2E);
  static const success = Color(0xFF16A34A);
  static const warning = Color(0xFFD97706);
  static const danger = Color(0xFFDC2626);

  // Compatibility aliases used by existing UI code.
  static const ink = foreground;
  static const canvas = background;
  static const divider = border;

  static const radiusSmall = 6.0;
  static const radiusMedium = 8.0;
  static const radiusLarge = 12.0;

  static const space1 = 4.0;
  static const space2 = 8.0;
  static const space3 = 12.0;
  static const space4 = 16.0;
  static const space5 = 20.0;
  static const space6 = 24.0;
  static const space8 = 32.0;
  static const space12 = 48.0;

  static ThemeData get light {
    const colorScheme = ColorScheme.light(
      primary: accent,
      onPrimary: accentOn,
      secondary: accent,
      onSecondary: accentOn,
      error: danger,
      onError: accentOn,
      surface: surface,
      onSurface: foreground,
    );
    const roundedSmall = RoundedRectangleBorder(
      borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: background,
      canvasColor: background,
      dividerColor: border,
      disabledColor: muted,
      splashColor: foreground.withValues(alpha: 0.06),
      highlightColor: foreground.withValues(alpha: 0.04),
      appBarTheme: const AppBarTheme(
        backgroundColor: background,
        foregroundColor: foreground,
        surfaceTintColor: background,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        toolbarHeight: 56,
        shape: Border(bottom: BorderSide(color: border)),
        titleSpacing: 16,
        titleTextStyle: TextStyle(
          color: foreground,
          fontSize: 20,
          height: 1.2,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
      ),
      actionIconTheme: ActionIconThemeData(
        backButtonIconBuilder: (_) => const Icon(LucideIcons.arrowLeft),
        closeButtonIconBuilder: (_) => const Icon(LucideIcons.x),
      ),
      cardTheme: const CardThemeData(
        color: surface,
        surfaceTintColor: surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusMedium)),
          side: BorderSide(color: border),
        ),
      ),
      dividerTheme: const DividerThemeData(
        color: border,
        thickness: 1,
        space: 1,
      ),
      iconTheme: const IconThemeData(color: foreground, size: 22),
      iconButtonTheme: IconButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size.square(44)),
          maximumSize: const WidgetStatePropertyAll(Size.square(44)),
          padding: const WidgetStatePropertyAll(EdgeInsets.all(10)),
          foregroundColor: const WidgetStatePropertyAll(foreground),
          shape: const WidgetStatePropertyAll(
            RoundedRectangleBorder(
              borderRadius: BorderRadius.all(Radius.circular(radiusMedium)),
            ),
          ),
          overlayColor: WidgetStatePropertyAll(
            foreground.withValues(alpha: 0.06),
          ),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(0, 44)),
          padding: const WidgetStatePropertyAll(
            EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          ),
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.disabled)) return border;
            if (states.contains(WidgetState.pressed)) return accentActive;
            if (states.contains(WidgetState.hovered)) return accentHover;
            return accent;
          }),
          foregroundColor: WidgetStateProperty.resolveWith((states) {
            return states.contains(WidgetState.disabled) ? muted : accentOn;
          }),
          textStyle: const WidgetStatePropertyAll(
            TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              letterSpacing: 0,
            ),
          ),
          shape: const WidgetStatePropertyAll(roundedSmall),
          elevation: const WidgetStatePropertyAll(0),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(0, 44)),
          padding: const WidgetStatePropertyAll(
            EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          ),
          foregroundColor: const WidgetStatePropertyAll(foreground),
          side: const WidgetStatePropertyAll(BorderSide(color: border)),
          textStyle: const WidgetStatePropertyAll(
            TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              letterSpacing: 0,
            ),
          ),
          shape: const WidgetStatePropertyAll(roundedSmall),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(44, 44)),
          foregroundColor: const WidgetStatePropertyAll(foreground),
          textStyle: const WidgetStatePropertyAll(
            TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              letterSpacing: 0,
            ),
          ),
          shape: const WidgetStatePropertyAll(roundedSmall),
        ),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: surface,
        contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 14),
        labelStyle: TextStyle(color: muted, fontSize: 14),
        hintStyle: TextStyle(color: muted, fontSize: 14),
        errorStyle: TextStyle(color: danger, fontSize: 12),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
          borderSide: BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
          borderSide: BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
          borderSide: BorderSide(color: accent, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
          borderSide: BorderSide(color: danger),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusSmall)),
          borderSide: BorderSide(color: danger, width: 2),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: foreground,
        contentTextStyle: TextStyle(
          color: accentOn,
          fontSize: 14,
          fontWeight: FontWeight.w500,
          letterSpacing: 0,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusMedium)),
        ),
      ),
      dialogTheme: const DialogThemeData(
        backgroundColor: surface,
        surfaceTintColor: surface,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.all(Radius.circular(radiusLarge)),
          side: BorderSide(color: border),
        ),
        titleTextStyle: TextStyle(
          color: foreground,
          fontSize: 17,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        contentTextStyle: TextStyle(
          color: muted,
          fontSize: 14,
          height: 1.5,
          letterSpacing: 0,
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: surface,
        surfaceTintColor: surface,
        modalBackgroundColor: surface,
        modalBarrierColor: accent.withValues(alpha: 0.60),
        elevation: 0,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(radiusLarge),
          ),
        ),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: accent,
        linearTrackColor: border,
        circularTrackColor: border,
      ),
      textTheme: const TextTheme(
        displaySmall: TextStyle(
          color: foreground,
          fontSize: 32,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        headlineSmall: TextStyle(
          color: foreground,
          fontSize: 24,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        titleLarge: TextStyle(
          color: foreground,
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: 0,
        ),
        titleMedium: TextStyle(
          color: foreground,
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
        ),
        titleSmall: TextStyle(
          color: foreground,
          fontSize: 14,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
        ),
        bodyLarge: TextStyle(
          color: foreground,
          fontSize: 16,
          fontWeight: FontWeight.w400,
          height: 1.5,
          letterSpacing: 0,
        ),
        bodyMedium: TextStyle(
          color: foreground,
          fontSize: 14,
          fontWeight: FontWeight.w400,
          height: 1.5,
          letterSpacing: 0,
        ),
        bodySmall: TextStyle(
          color: muted,
          fontSize: 12,
          fontWeight: FontWeight.w400,
          height: 1.5,
          letterSpacing: 0,
        ),
        labelLarge: TextStyle(
          color: foreground,
          fontSize: 14,
          fontWeight: FontWeight.w600,
          letterSpacing: 0,
        ),
      ),
    );
  }
}
