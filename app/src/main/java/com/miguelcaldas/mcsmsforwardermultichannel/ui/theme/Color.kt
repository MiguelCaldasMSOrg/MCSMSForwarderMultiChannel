package com.miguelcaldas.mcsmsforwardermultichannel.ui.theme

import androidx.compose.ui.graphics.Color

// Static fallback palette used on devices/OS versions where Material You dynamic
// color is unavailable (or when the user opts out). Seeded around a teal/green
// "forwarding" brand hue. Dynamic color (dynamicLightColorScheme /
// dynamicDarkColorScheme) is preferred at runtime when supported.

internal val BrandPrimary = Color(0xFF00696E)
internal val BrandOnPrimary = Color(0xFFFFFFFF)
internal val BrandPrimaryContainer = Color(0xFF6FF6FE)
internal val BrandOnPrimaryContainer = Color(0xFF002022)
internal val BrandSecondary = Color(0xFF4A6365)
internal val BrandOnSecondary = Color(0xFFFFFFFF)
internal val BrandSecondaryContainer = Color(0xFFCCE8EA)
internal val BrandOnSecondaryContainer = Color(0xFF051F21)

internal val BrandPrimaryDark = Color(0xFF4CD9E1)
internal val BrandOnPrimaryDark = Color(0xFF00363A)
internal val BrandPrimaryContainerDark = Color(0xFF004F53)
internal val BrandOnPrimaryContainerDark = Color(0xFF6FF6FE)
internal val BrandSecondaryDark = Color(0xFFB0CBCE)
internal val BrandOnSecondaryDark = Color(0xFF1B3437)
internal val BrandSecondaryContainerDark = Color(0xFF324B4D)
internal val BrandOnSecondaryContainerDark = Color(0xFFCCE8EA)

// Semantic colors for the activity-log entry coloring, theme-aware so they stay
// legible in both light and dark.
internal val LogSuccessLight = Color(0xFF2E7D32)
internal val LogSuccessDark = Color(0xFF81C784)
internal val LogFailureLight = Color(0xFFC62828)
internal val LogFailureDark = Color(0xFFEF9A9A)
internal val LogFilteredLight = Color(0xFF8A5300)
internal val LogFilteredDark = Color(0xFFFFB74D)
internal val LogRemoteLight = Color(0xFF1565C0)
internal val LogRemoteDark = Color(0xFF90CAF9)
