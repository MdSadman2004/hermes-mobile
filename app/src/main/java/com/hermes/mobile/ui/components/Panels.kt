package com.hermes.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.HermesMotion
import com.hermes.mobile.ui.theme.revealOnEnter
import com.hermes.mobile.ui.theme.tapScale

/**
 * The building blocks every panel in this app is made of.
 *
 * Deliberately few: one row, one section label, one empty state, one skeleton.
 * A control surface earns trust by looking the same everywhere, so a screen
 * that needs "something a bit different" gets a variant here rather than a
 * one-off Surface with its own padding.
 *
 * The shared visual grammar: a hairline border instead of a shadow, radii
 * that nest, and motion on every touch. Shadows are avoided because they
 * fight the warm near-black ground — the border is what defines a card here.
 */

/** A tappable row that opens something. 56dp minimum, well past the 48dp floor. */
@Composable
fun NavRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = title },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp).size(18.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            } else if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Groups rows. More space above than below, so the label belongs to what
 * follows. The small clay tick is the one decoration in the whole system: it
 * is what makes a wall of panels scan as sections rather than as a list.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * An empty state that teaches. [hint] says what to do next, never just
 * "nothing here" — a blank panel with no next step is where a first-run user
 * decides the app is broken.
 *
 * The icon settles in when the state appears: an empty screen is the moment
 * the app most needs to look intentional rather than frozen.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val pop = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1f, HermesMotion.Soft)
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
            .revealOnEnter(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(14.dp).size(30.dp).alpha(pop.value),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

/**
 * Placeholder rows while a list loads. A skeleton preserves the layout the
 * content will occupy, so the screen does not jump when data lands — which a
 * centred spinner guarantees it will.
 *
 * Each row carries a travelling warm shimmer rather than a whole-block alpha
 * pulse: a list of eight identically blinking grey slabs reads as broken,
 * while a highlight moving down the list reads as the list *filling*.
 */
@Composable
fun SkeletonList(rows: Int = 4, rowHeight: Int = 72, modifier: Modifier = Modifier) {
    val shimmer = rememberInfiniteTransition(label = "skeleton")
    val x by shimmer.animateFloat(
        -0.5f, 1.5f,
        infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing)),
        label = "skeleton-sweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val hi = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { i ->
            // Rows enter one after another instead of appearing all at once.
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(i * 55L)
                appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight.dp)
                    .alpha(appear.value)
                    .background(base, RoundedCornerShape(14.dp))
                    .border(1.dp, edge, RoundedCornerShape(14.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, hi, Color.Transparent),
                                startX = 900f * (x - 0.3f),
                                endX = 900f * (x + 0.3f),
                            ),
                            RoundedCornerShape(14.dp),
                        ),
                )
            }
        }
    }
}

/**
 * A thin horizontal meter — context fill, disk, memory. Never a progress ring.
 *
 * The fill animates in from the left on first draw and springs to any later
 * value, so a CPU reading that jumps 20% → 60% shows the movement rather than
 * teleporting; movement is what tells the user the reading is live.
 */
@Composable
fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: Color = MaterialTheme.colorScheme.primary,
) {
    val target = fraction.coerceIn(0f, 1f)
    val width = remember { Animatable(0f) }
    LaunchedEffect(target) { width.animateTo(target, HermesMotion.Soft) }
    Box(
        modifier
            .height(5.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(width.value)
                .height(5.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(tone.copy(alpha = 0.75f), tone),
                    ),
                    RoundedCornerShape(3.dp),
                ),
        )
    }
}
