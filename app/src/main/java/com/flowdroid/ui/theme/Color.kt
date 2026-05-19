package com.flowdroid.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Material 3 color tokens for FlowDroid.
 *
 * Dark-first neutral palette: greys with a hint of cool blue. No decorative gradients,
 * no garish brand colors. Status accents are reserved for the Health screen.
 */

// --- Dark palette (default) ---
internal val DarkPrimary = Color(0xFFB9C7DC)
internal val DarkOnPrimary = Color(0xFF243042)
internal val DarkPrimaryContainer = Color(0xFF394759)
internal val DarkOnPrimaryContainer = Color(0xFFD7E3F8)

internal val DarkSecondary = Color(0xFFBEC6D2)
internal val DarkOnSecondary = Color(0xFF293039)
internal val DarkSecondaryContainer = Color(0xFF3F4750)
internal val DarkOnSecondaryContainer = Color(0xFFDAE2EE)

internal val DarkTertiary = Color(0xFFD5BFD3)
internal val DarkOnTertiary = Color(0xFF382B3A)
internal val DarkTertiaryContainer = Color(0xFF504152)
internal val DarkOnTertiaryContainer = Color(0xFFF1DBEF)

internal val DarkBackground = Color(0xFF11141A)
internal val DarkOnBackground = Color(0xFFE2E2E6)
internal val DarkSurface = Color(0xFF11141A)
internal val DarkOnSurface = Color(0xFFE2E2E6)
internal val DarkSurfaceVariant = Color(0xFF42474E)
internal val DarkOnSurfaceVariant = Color(0xFFC2C7CF)
internal val DarkOutline = Color(0xFF8C9199)
internal val DarkOutlineVariant = Color(0xFF42474E)

internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

// --- Light palette ---
internal val LightPrimary = Color(0xFF445E76)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFCBE0FB)
internal val LightOnPrimaryContainer = Color(0xFF00182C)

internal val LightSecondary = Color(0xFF555F6E)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD9E3F4)
internal val LightOnSecondaryContainer = Color(0xFF131C29)

internal val LightTertiary = Color(0xFF6F5A6F)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFF9DDF7)
internal val LightOnTertiaryContainer = Color(0xFF281729)

internal val LightBackground = Color(0xFFFCFCFF)
internal val LightOnBackground = Color(0xFF1A1C1F)
internal val LightSurface = Color(0xFFFCFCFF)
internal val LightOnSurface = Color(0xFF1A1C1F)
internal val LightSurfaceVariant = Color(0xFFDFE2EB)
internal val LightOnSurfaceVariant = Color(0xFF42474E)
internal val LightOutline = Color(0xFF72777F)
internal val LightOutlineVariant = Color(0xFFC2C7CF)

internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)

// --- Status accents (used by Health screen + log level coloring). ---
// Tuned for sufficient contrast on both light and dark surfaces.
val StatusGreen = Color(0xFF4CAF7B)
val StatusAmber = Color(0xFFE0A030)
val StatusRed = Color(0xFFE4554A)
val StatusUnknown = Color(0xFF8C9199)

// --- Family accents (used by the flow editor to color-code each action family). ---
// Sourced 1:1 from the mockup tokens. Reserved for the editor — never used for status
// signaling on the Health screen, which would conflict semantically with the StatusX palette.
val FamilyNotifications = Color(0xFF4CAF7B)     // green
val FamilyApps = Color(0xFF5B8DEF)              // blue
val FamilyUi = Color(0xFFB580E0)                // purple
val FamilyTiming = Color(0xFFE0A030)            // amber
val FamilyNetwork = Color(0xFF4FC8E0)           // cyan
val FamilyVariables = Color(0xFFE07AB5)         // pink
val FamilyData = Color(0xFFA4C842)              // olive/green
val FamilyLogic = Color(0xFFB9C7DC)             // primary-grey-blue
