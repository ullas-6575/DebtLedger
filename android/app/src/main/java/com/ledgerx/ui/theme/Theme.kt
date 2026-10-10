package com.ledgerx.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Colour palette ──────────────────────────────────────────────────
val Green = Color(0xFF136F55)
val GreenLight = Color(0xFF1A8A6B)
val GreenDark = Color(0xFF0D5641)
val GreenSurface = Color(0xFFEAF2EF)
val Background = Color(0xFFF6F8FA)
val Surface = Color.White
val Ink = Color(0xFF182D35)
val Muted = Color(0xFF647680)
val Red = Color(0xFFAD3434)
val RedSurface = Color(0xFFFDF2F2)
val Amber = Color(0xFFE8A317)
val AmberSurface = Color(0xFFFFF8E1)

// ── Material 3 colour scheme ────────────────────────────────────────
val LedgerXColorScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenSurface,
    onPrimaryContainer = GreenDark,
    secondary = Green,
    background = Background,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Background,
    onSurfaceVariant = Muted,
    error = Red,
    onError = Color.White,
    errorContainer = RedSurface,
    onErrorContainer = Red,
    outline = Color(0xFFCED4DA),
    outlineVariant = Color(0xFFE9ECEF),
)

@Composable
fun LedgerXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerXColorScheme,
        content = content
    )
}
