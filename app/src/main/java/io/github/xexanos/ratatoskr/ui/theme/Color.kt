/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.theme

import androidx.compose.ui.graphics.Color

// Ratatoskr brand palette, derived from the logo (docs/logo/): copper squirrel
// (#A93B28) as primary, ash-leaf green frame (#4F6B35, "Eschenlaub") as secondary,
// on a warm-neutral base. 60/30/10: warm neutrals carry the surface, dark warm text,
// copper reserved for primary actions, ash green for secondary accents.

// Light
val CopperPrimaryLight = Color(0xFFA93B28)
val CopperOnPrimaryLight = Color(0xFFFFFFFF)
val CopperContainerLight = Color(0xFFFFDAD2)
val CopperOnContainerLight = Color(0xFF3E0400)
val AshSecondaryLight = Color(0xFF4F6B35)
val AshContainerLight = Color(0xFFD1E8B0)
val AshOnContainerLight = Color(0xFF121F04)
val BackgroundLight = Color(0xFFFFFBF8)
val OnBackgroundLight = Color(0xFF211A17)
val SurfaceVariantLight = Color(0xFFF4DED5)
val OnSurfaceVariantLight = Color(0xFF53433D)
val OutlineLight = Color(0xFF85736C)
// Tonal container band (the search bar's container tone in the design doc, used by the
// continue-listening shelf) and the hairline edge that closes it.
val SurfaceContainerLight = Color(0xFFF6EAE2)
val OutlineVariantLight = Color(0xFFE5D2C4)

// Dark
val CopperPrimaryDark = Color(0xFFFFB4A3)
val CopperOnPrimaryDark = Color(0xFF641D0E)
val CopperContainerDark = Color(0xFF872F1F)
val CopperOnContainerDark = Color(0xFFFFDAD2)
val AshSecondaryDark = Color(0xFFB5D18B)
val AshContainerDark = Color(0xFF395020)
val AshOnContainerDark = Color(0xFFD1E8B0)
val BackgroundDark = Color(0xFF1A120F)
val OnBackgroundDark = Color(0xFFF1DFD9)
val SurfaceVariantDark = Color(0xFF53433D)
val OnSurfaceVariantDark = Color(0xFFD8C2B9)
val OutlineDark = Color(0xFFA08D85)
// See the light-side note: shelf band and its hairline edge.
val SurfaceContainerDark = Color(0xFF241914)
val OutlineVariantDark = Color(0xFF3A2A22)
