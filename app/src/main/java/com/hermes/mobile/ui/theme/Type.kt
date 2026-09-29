package com.hermes.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Two voices, one page.
 *
 * Display and headline roles are set in a warm serif ([HermesDisplay]) — the
 * editorial voice that gives a screen a title worth reading. Everything that
 * is *operated* (titles, body, labels, buttons) stays in the user's chosen
 * sans, because serif UI chrome reads as decoration on a control surface.
 *
 * The scale itself stays tight: a control surface has far more type elements
 * than a marketing page, so the ratio between steps is near 1.15 —
 * exaggerated contrast reads as noise when a screen carries a title, a chip,
 * four labels and a wall of body at once. Sizes are sp throughout, so the
 * system font-size setting still works.
 */
/** Code, terminal scrollback, command strings, token counts — never chrome. */
val HermesMono: FontFamily = HermesMonoFamily

/** Trim the extra leading Compose adds by default; dense UI needs the pixels. */
private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
    family: FontFamily = FontFamily.Default,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = Trim,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * The scale, bound to the chosen families.
 *
 * Typography used to be a single `val`, which meant the family was fixed at
 * class-init and a user font choice could not reach it. It is a function now
 * so the theme can rebuild the scale when the preference changes.
 */
fun hermesTypography(
    sans: FontFamily = FontFamily.Default,
    display: FontFamily = FontFamily.Serif,
) = Typography(
    // The editorial voice: welcome lines, empty states, section heroes.
    displayLarge = style(42, 49, FontWeight.SemiBold, -0.8, family = display),
    displayMedium = style(33, 40, FontWeight.SemiBold, -0.5, family = display),
    displaySmall = style(27, 34, FontWeight.SemiBold, -0.3, family = display),

    headlineLarge = style(25, 32, FontWeight.SemiBold, -0.3, family = display),
    headlineMedium = style(22, 29, FontWeight.SemiBold, -0.2, family = display),
    headlineSmall = style(19, 26, FontWeight.SemiBold, -0.1, family = display),

    // The operating voice: everything you tap or scan.
    titleLarge = style(18, 25, FontWeight.SemiBold, -0.1, family = sans),
    titleMedium = style(15, 22, FontWeight.SemiBold, family = sans),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1, family = sans),

    bodyLarge = style(16, 25, family = sans),
    bodyMedium = style(14, 21, tracking = 0.1, family = sans),
    bodySmall = style(12, 18, tracking = 0.2, family = sans),

    labelLarge = style(14, 20, FontWeight.Medium, 0.1, family = sans),
    labelMedium = style(12, 16, FontWeight.Medium, 0.4, family = sans),
    labelSmall = style(11, 15, FontWeight.Medium, 0.4, family = sans),
)

/** Fenced code and inline spans inside a transcript message. */
val HermesCodeStyle = style(12, 18, family = HermesMono)

/** Terminal scrollback — one step tighter, because 80 columns has to fit. */
val HermesTerminalStyle = style(11, 15, family = HermesMono)
