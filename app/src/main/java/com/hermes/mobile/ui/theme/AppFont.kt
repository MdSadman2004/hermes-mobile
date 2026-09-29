package com.hermes.mobile.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.hermes.mobile.R

/**
 * Selectable type.
 *
 * A control surface is read by whoever is holding the phone, not only by the
 * person who built it. A monospace-first interface looks correct to an engineer
 * and hostile to everyone else, so the family is a user preference rather than
 * a hard-coded taste, and the default is the friendliest of the set.
 *
 * All five are variable fonts, so one file covers the whole weight axis instead
 * of shipping four static cuts per family (720 KB total for the set).
 *
 * Code is exempt: fenced blocks, terminal scrollback and token counts always
 * use the mono family, because column alignment carries meaning there and a
 * proportional font destroys it.
 */
@Immutable
enum class AppFont(
    val id: String,
    val label: String,
    /** One line the picker shows so the choice is legible before it is applied. */
    val blurb: String,
) {
    OUTFIT("outfit", "Outfit", "Rounded and warm — approachable, easy on the eyes"),
    JAKARTA("jakarta", "Plus Jakarta", "Clean and neutral — best for long reading"),
    SORA("sora", "Sora", "Modern with character — slightly editorial"),
    GROTESK("grotesk", "Space Grotesk", "Geometric and distinctive — a designed feel"),
    SYSTEM("system", "System default", "Whatever your phone already uses");

    companion object {
        val DEFAULT = OUTFIT
        fun from(id: String?): AppFont = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

private fun variable(resId: Int) = FontFamily(
    Font(resId, FontWeight.Light),
    Font(resId, FontWeight.Normal),
    Font(resId, FontWeight.Medium),
    Font(resId, FontWeight.SemiBold),
    Font(resId, FontWeight.Bold),
    Font(resId, FontWeight.ExtraBold),
)

fun AppFont.family(): FontFamily = when (this) {
    AppFont.OUTFIT -> variable(R.font.outfit_variable)
    AppFont.JAKARTA -> variable(R.font.jakarta_variable)
    AppFont.SORA -> variable(R.font.sora_variable)
    AppFont.GROTESK -> variable(R.font.space_grotesk_variable)
    AppFont.SYSTEM -> FontFamily.Default
}

/**
 * The display voice — fixed, and deliberately NOT user-selectable.
 *
 * Fraunces is a warm editorial serif; it carries the display/headline roles
 * and the app's welcome moments. Body and controls stay in the chosen sans,
 * so the picker keeps controlling what the user actually reads long-form.
 */
val HermesDisplay: FontFamily = variable(R.font.fraunces_variable)

/** The mono family is fixed — alignment is semantic in code and terminal output. */
val HermesMonoFamily: FontFamily = variable(R.font.jetbrains_mono_variable)

/** Current selection, so any composable can react without prop-drilling. */
val LocalAppFont = staticCompositionLocalOf { AppFont.DEFAULT }
