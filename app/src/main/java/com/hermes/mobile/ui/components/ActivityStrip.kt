package com.hermes.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.domain.model.AgentActivity

/**
 * The one line that says what the PC is doing.
 *
 * This replaces the wall of tool invocations that used to be interleaved into
 * the conversation. A chat transcript is for the conversation; a phone screen
 * is six inches tall; and `terminal  command=git rev-parse --abbrev-ref HEAD`
 * between two sentences of a reply costs a full screen of scrolling to read
 * past and tells the user nothing they wanted. So the machine detail moves to
 * the Activity screen, and what stays here is a plain-language phase that
 * changes as the work moves: "Reading files" → "Running a command" →
 * "Writing a reply".
 *
 * It is deliberately one line, deliberately animated on change (a label that
 * swaps silently is indistinguishable from a frozen one), and deliberately
 * tappable — the detail did not disappear, it is one tap away.
 */
@Composable
fun ActivityStrip(
    activity: AgentActivity?,
    modifier: Modifier = Modifier,
    onOpenActivity: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = activity != null,
        enter = slideInVertically { it } + fadeIn(tween(160)),
        exit = slideOutVertically { it } + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        val current = activity ?: return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .heightIn(min = 44.dp)
                .clickable(onClick = onOpenActivity)
                .semantics {
                    contentDescription = "Hermes is ${current.phase}. Tap to see the activity log."
                },
        ) {
            Column {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current.running) {
                    // The orb, not a spinner: it is the same mark the live-turn
                    // bar uses, so "the PC is working" has one face everywhere.
                    ThinkingOrb(size = 16.dp)
                    Spacer(Modifier.width(10.dp))
                }
                // Crossfade the phrase: a silent text swap reads as a frozen
                // UI, and this is the only motion proving work is happening.
                AnimatedContent(
                    targetState = current.phase,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically { it / 2 })
                            .togetherWith(fadeOut(tween(120)) + slideOutVertically { -it / 2 })
                    },
                    label = "phase",
                    modifier = Modifier.weight(1f, fill = false),
                ) { phase ->
                    Column {
                        Text(
                            "$phase…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        current.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (current.steps > 0) {
                    MetaChip("${current.steps} step" + if (current.steps == 1) "" else "s")
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // While the turn runs, the strip carries the same travelling accent
            // line as the live-turn bar: the two surfaces are one idea at two
            // sizes, and the line is what makes "still working" obvious from
            // across the room.
            if (current.running) IndeterminateAccentBar()
            }
        }
    }
}
