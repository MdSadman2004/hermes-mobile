package com.hermes.mobile.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How the app moves.
 *
 * Motion here is not decoration; it is the answer to "did that land?". Every
 * transition names a relationship: a screen that slides in from the direction
 * of the tab you tapped, a card that presses in when you touch it, a list
 * that reveals itself the way a hand deals cards. All of it is spring-based
 * rather than eased, because springs survive interruption — a tween restarts
 * from zero when the user changes their mind mid-gesture, which is exactly
 * when a slow animation feels broken.
 *
 * The numbers are deliberately unhurried (damping around 0.8, stiffness
 * medium-low): a phone driving a desktop agent waits a lot, and a UI that
 * races while the work is slow reads as anxiety.
 */
object HermesMotion {
    /** Big surfaces: screens, sheets, cards arriving. Soft settle, no wobble. */
    val Soft: SpringSpec<Float> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Same settle, typed for offset/size driven transitions (slides, expands)
     * — `slideInHorizontally` and friends require an `IntX` spec, so the
     * spring has to be spelled at those types separately.
     */
    val SoftOffset: SpringSpec<androidx.compose.ui.unit.IntOffset> = spring(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Direct manipulation: presses, toggles, chips. Fast, slight overshoot. */
    val Snappy: SpringSpec<Float> = spring(
        dampingRatio = 0.68f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Small playful accents: badges, dots, icon swaps. */
    val Bounce: SpringSpec<Float> = spring(
        dampingRatio = 0.5f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Fades and cross-cuts. Kept short so a screen-change never feels slow. */
    const val FADE_MS = 220
    const val FADE_OUT_MS = 150

    /** Stagger step between list rows / stacked sections revealing. */
    const val STAGGER_MS = 45L
}

/** Cross-fade + a small horizontal drift, used for tab-to-tab navigation. */
fun hermesScreenIn(): EnterTransition =
    fadeIn(tween(HermesMotion.FADE_MS)) +
        slideInHorizontally(HermesMotion.SoftOffset) { full -> full / 14 } +
        scaleIn(initialScale = 0.985f, animationSpec = HermesMotion.Soft)

fun hermesScreenOut(): ExitTransition =
    fadeOut(tween(HermesMotion.FADE_OUT_MS)) +
        scaleOut(targetScale = 0.992f, animationSpec = tween(HermesMotion.FADE_OUT_MS))

/**
 * A drop-in replacement for `clickable` that also compresses the element while
 * it is held.
 *
 * The press must be visible even when the follow-up action takes a moment —
 * silence during the first 100 ms is what makes a UI feel dead. Indication is
 * off because the scale IS the indication.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.tapScale(
    enabled: Boolean = true,
    scaleDown: Float = 0.965f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = HermesMotion.Snappy,
        label = "tap-scale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * Reveal content the first time it appears, optionally staggered by [index].
 *
 * For stacked sections and short lists that are not lazy. LazyColumn rows use
 * `Modifier.animateItem()` instead — this would re-fire on every scroll.
 */
@Composable
fun Modifier.revealOnEnter(index: Int = 0, key: Any? = Unit): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(index * HermesMotion.STAGGER_MS)
        progress.animateTo(1f, tween(430, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 26.dp.toPx()
    }
}
