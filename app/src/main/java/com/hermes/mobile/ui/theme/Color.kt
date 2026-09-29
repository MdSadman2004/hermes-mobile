package com.hermes.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------------------
// Identity: warm ink, sand and clay
// ---------------------------------------------------------------------------
//
// The app reads as a letterpress instrument rather than a control panel:
// warm near-black grounds (never blue-black), sand-paper text rather than
// silver, and a single clay accent that appears only where something is
// alive or actionable. Nothing here is cool — even the "green" of an online
// state is a warm sage, because a cold signal colour on a warm ground reads
// as a bug, not a state.
//
// Surfaces still step in five discrete layers. On OLED a single background
// makes every card edge disappear; the warm ladder from #0B0908 to #292019
// keeps depth readable without a single visible border.

private val Clay = Color(0xFFD98A5F)          // primary signal (terracotta)
private val ClayDeep = Color(0xFF5C2F17)      // pressed / container
private val ClayLit = Color(0xFFA85B34)       // light-scheme primary
private val SandBright = Color(0xFFF0E7DB)    // primary text on dark
private val SandMuted = Color(0xFFB4A493)     // secondary text on dark

private val DarkColorScheme = darkColorScheme(
    primary = Clay,
    onPrimary = Color(0xFF3B1D0C),
    primaryContainer = ClayDeep,
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = ClayLit,

    // Secondary is deliberately a desaturated sand, not a second hue: two
    // competing accents on a dense control surface reads as noise.
    secondary = Color(0xFFCDBBA6),
    onSecondary = Color(0xFF2A231D),
    secondaryContainer = Color(0xFF3A3029),
    onSecondaryContainer = Color(0xFFE8DCCA),

    tertiary = Color(0xFFD9B173),
    onTertiary = Color(0xFF3A2A0D),
    tertiaryContainer = Color(0xFF4E3A18),
    onTertiaryContainer = Color(0xFFF5DEB0),

    error = Color(0xFFFF9C86),
    onError = Color(0xFF47140C),
    errorContainer = Color(0xFF6B2A1E),
    onErrorContainer = Color(0xFFFFDAD2),

    background = Color(0xFF100D0B),
    onBackground = SandBright,
    surface = Color(0xFF100D0B),
    onSurface = SandBright,
    surfaceVariant = Color(0xFF211A16),
    onSurfaceVariant = Color(0xFFBCAB98),

    surfaceContainerLowest = Color(0xFF0B0908),
    surfaceContainerLow = Color(0xFF151110),
    surfaceContainer = Color(0xFF1A1512),
    surfaceContainerHigh = Color(0xFF211A16),
    surfaceContainerHighest = Color(0xFF292019),

    outline = Color(0xFF7A6B5E),
    outlineVariant = Color(0xFF332C26),
    scrim = Color(0xFF000000),
    inverseSurface = SandBright,
    inverseOnSurface = Color(0xFF241C17),
)

private val LightColorScheme = lightColorScheme(
    primary = ClayLit,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF3A1A09),
    inversePrimary = Clay,

    secondary = Color(0xFF6B5B4B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0E4D4),
    onSecondaryContainer = Color(0xFF241C15),

    tertiary = Color(0xFF7C6A2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E5C0),
    onTertiaryContainer = Color(0xFF241C00),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBF7F1),
    onBackground = Color(0xFF241C16),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF241C16),
    surfaceVariant = Color(0xFFEFE7DC),
    onSurfaceVariant = Color(0xFF6E6156),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF7F1),
    surfaceContainer = Color(0xFFF5EFE7),
    surfaceContainerHigh = Color(0xFFEFE7DC),
    surfaceContainerHighest = Color(0xFFE8DFD2),

    outline = Color(0xFF857567),
    outlineVariant = Color(0xFFDDD2C4),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF382F28),
    inverseOnSurface = Color(0xFFFBF3EA),
)

// ---------------------------------------------------------------------------
// Roles Material does not have
// ---------------------------------------------------------------------------

/**
 * Semantic colours outside the M3 scheme, provided through a CompositionLocal
 * so they resolve light/dark exactly like the built-in roles do.
 *
 * [warning] exists because an approval request is not an *error*. Painting a
 * routine "may I run this command?" in error red trains the user to dismiss
 * the colour that should mean something has gone wrong. Approvals are amber —
 * blocked, waiting on you — and red stays reserved for actual failures.
 *
 * [code] is the surface behind fenced code and terminal scrollback. It sits a
 * step off the neutral ramp so a code block reads as a different material from
 * the message that contains it.
 */
@Immutable
data class HermesSemantics(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val code: Color,
    val onCode: Color,
    val codeBorder: Color,
    val diffAdded: Color,
    val diffRemoved: Color,
    /** Live-connection indicator; deliberately not `tertiary`, which also means "done". */
    val online: Color,
    val offline: Color,
)

private val DarkSemantics = HermesSemantics(
    warning = Color(0xFFEFC062),
    onWarning = Color(0xFF402F00),
    warningContainer = Color(0xFF4C3A08),
    onWarningContainer = Color(0xFFFFE7A8),
    success = Color(0xFF9DBF77),
    successContainer = Color(0xFF2C3D1B),
    onSuccessContainer = Color(0xFFD8F0B8),
    code = Color(0xFF181310),
    onCode = Color(0xFFE6DCCE),
    codeBorder = Color(0xFF2E2620),
    diffAdded = Color(0xFF8FBF6A),
    diffRemoved = Color(0xFFFF8A6E),
    online = Color(0xFF93B573),
    offline = Color(0xFF7C7065),
)

private val LightSemantics = HermesSemantics(
    warning = Color(0xFF8A6100),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFEFC4),
    onWarningContainer = Color(0xFF2A1D00),
    success = Color(0xFF4C6B2F),
    successContainer = Color(0xFFD9EDBC),
    onSuccessContainer = Color(0xFF152100),
    code = Color(0xFFF3EDE4),
    onCode = Color(0xFF2A231C),
    codeBorder = Color(0xFFE0D6C8),
    diffAdded = Color(0xFF3F6B1F),
    diffRemoved = Color(0xFFA8391F),
    online = Color(0xFF4C7A34),
    offline = Color(0xFF8C8074),
)

val LocalHermesSemantics = staticCompositionLocalOf { DarkSemantics }

/** `MaterialTheme.hermes.warning` reads like the built-in roles do. */
val MaterialTheme.hermes: HermesSemantics
    @Composable get() = LocalHermesSemantics.current

// ---------------------------------------------------------------------------

@Composable
fun HermesMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You is off by default. The identity of this app is the warm
     * ink-and-clay look the phone client owns — deriving it from wallpaper
     * would make the phone and the PC look like two different products. It is
     * offered as an opt-in rather than removed.
     */
    dynamicColor: Boolean = false,
    /** User-selected type. Rebuilds the scale so the choice reaches every role. */
    appFont: AppFont = AppFont.DEFAULT,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Edge-to-edge: the system bars are transparent, so their icon colour has to
    // follow the scheme or they vanish into the background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val typography = remember(appFont) {
        hermesTypography(sans = appFont.family(), display = HermesDisplay)
    }

    CompositionLocalProvider(
        LocalHermesSemantics provides if (darkTheme) DarkSemantics else LightSemantics,
        LocalAppFont provides appFont,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
