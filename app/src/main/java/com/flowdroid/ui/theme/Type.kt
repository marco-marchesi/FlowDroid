package com.flowdroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * FlowDroid typography. Mostly Material 3 defaults; we override nothing visual except to
 * ensure consistent line height. The dedicated [bodyMonospace] style is used by the log
 * viewer and any code-like content.
 */
val Typography: Typography = Typography()

/**
 * Monospace style used for log lines and any code/raw text. Slightly smaller than `bodyMedium`
 * so a typical log line fits without wrapping on a Pixel 6-class device.
 */
val bodyMonospace: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.sp,
)
