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
// Darker than the tone the palette derivation gives: ATF's screenshot contrast check reads a
// text field's outline as its text, so this has to clear 4.5:1 against BackgroundLight (4.76:1).
val OutlineLight = Color(0xFF7F6D67)
// Tonal container band (the search bar's container tone in the design doc, used by the
// continue-listening shelf) and the hairline edge that closes it.
val SurfaceContainerLight = Color(0xFFF6EAE2)
val OutlineVariantLight = Color(0xFFE5D2C4)
// Error. An error surface is told apart by how colourful it is, not by which red it is
// (ux-design: Patterns). The M3 baseline errorContainer #F9DEDC carries chroma 10.0 against
// SurfaceVariantLight's 9.4 - the same colourless tint as the neutral notice card beside it on
// the sign-in screen. These raise chroma to 24.4 inside the warm hue family, which also lifts
// the separation from CopperContainerLight (dE 1.7 -> 6.9). ErrorLight is M3's Error30, far
// enough from the copper primary to stop reading as it (dE 3.6 -> 8.6).
val ErrorLight = Color(0xFF8C1D18)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFC4B6)
val OnErrorContainerLight = Color(0xFF4A1108)

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
// Error, dark: Material 3's own baseline values, pinned rather than inherited. Dark already
// satisfies the chroma rule - ErrorContainerDark carries chroma 55.8 against SurfaceVariantDark's
// 8.5, so the error band and the notice card are unmistakably different surfaces (dE 19.9) and
// there is nothing to fix. Pinned anyway because an inherited ramp shifts silently on a
// dependency bump, as OutlineLight above had to. Do not "deduplicate" these against M3.
// ErrorContainerDark is the same tone as ErrorLight: one tonal step serving two roles.
val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)
