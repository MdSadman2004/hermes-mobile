package com.hermes.mobile.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.theme.hermes

/**
 * The connection indicator.
 *
 * Colour alone would fail anyone who can't distinguish it, so the state is
 * always spelled out in the label too, and the dot only pulses while something
 * is genuinely in flight — a steady dot means settled, which is the whole
 * point of glancing at it.
 */
@Composable
fun ConnectionPill(
    conn: ConnState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val semantics = MaterialTheme.hermes
    val (dot, label, live) = when (conn) {
        is ConnState.Connected -> Triple(semantics.online, conn.profile.label, false)
        is ConnState.Connecting -> Triple(MaterialTheme.colorScheme.secondary, "connecting", true)
        is ConnState.Probing -> Triple(MaterialTheme.colorScheme.secondary, "searching", true)
        is ConnState.Reconnecting ->
            Triple(semantics.warning, "reconnecting · ${conn.attempt}", true)
        is ConnState.Failed -> Triple(MaterialTheme.colorScheme.error, "offline", false)
        ConnState.NoProfile -> Triple(semantics.offline, "not paired", false)
    }

    Row(
        modifier.semantics { contentDescription = "Connection: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(color = dot, animating = live)
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PulseDot(color: Color, animating: Boolean, size: Int = 8) {
    val alpha = if (animating) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse-alpha",
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(size.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

/**
 * A small keyed fact — model name, context fill, token count. Reads as data,
 * not as a button, so it is deliberately not a Material chip.
 *
 * Hairline border rather than a filled slab, so a row of four chips stays
 * quiet under a title instead of turning the app bar into a colour bar.
 */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Color? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f),
        border = BorderStroke(
            1.dp,
            (tone ?: MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.45f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
