package com.hermes.mobile.ui.cockpit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.ui.components.BreathingDots
import com.hermes.mobile.ui.components.CodeBlock
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.ThinkingOrb
import com.hermes.mobile.ui.components.toolIconFor
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the terminal commands went.
 *
 * Every tool the agent invokes — the command line, its arguments, its output,
 * how long it took — is here, newest last, in full. Nothing was removed from
 * the product; it was moved out of the conversation, which is a place for
 * sentences, and into the place you go when you want to know exactly what the
 * machine did.
 *
 * Rows are collapsed by default and expand to the raw call. That is the right
 * default for a log you scan and occasionally interrogate: the name plus its
 * context is enough to find the row you want, and the payload is one tap away
 * rather than permanently eating the screen.
 */
@Composable
fun ActivityScreen(
    entries: List<TranscriptItem.ToolCallItem>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.Terminal,
                title = "Nothing has run yet",
                hint = "Commands, file reads and searches your PC runs for this " +
                    "session show up here — with their arguments and output — " +
                    "instead of cluttering the conversation.",
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        reverseLayout = true,
    ) {
        items(entries.asReversed(), key = { it.key }) { entry ->
            // New rows arrive from the bottom as the agent works, so each one
            // animates in: the log visibly *grows* instead of the list jumping.
            Box(Modifier.fillMaxWidth().animateItem()) { ActivityRow(entry) }
        }
    }
}

@Composable
private fun ActivityRow(item: TranscriptItem.ToolCallItem) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val semantics = MaterialTheme.hermes
    val hasDetail = item.args != null || item.result != null

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (item.running) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
            .semantics { contentDescription = "Tool ${item.name}" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.running) {
                    // The same orb as everywhere else: a running tool is the
                    // agent working, at the smallest scale it appears.
                    ThinkingOrb(size = 15.dp)
                } else {
                    Icon(
                        toolIconFor(item.name),
                        contentDescription = null,
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = HermesMono,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    TIME.format(Date(item.atMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (item.running) {
                    BreathingDots(dot = 4.dp)
                } else {
                    Text(
                        item.durationS?.let { formatDuration(it) } ?: "done",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantics.success,
                    )
                }
                if (hasDetail) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        Modifier.padding(start = 4.dp).size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.context?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 4 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            item.approvalNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AnimatedVisibility(expanded && hasDetail) {
                Column(
                    Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.args?.takeIf { it.isNotBlank() }?.let { CodeBlock("arguments", it) }
                    item.result?.takeIf { it.isNotBlank() }?.let {
                        CodeBlock("result", it.take(4000))
                    }
                }
            }
        }
    }
}

private val TIME = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

internal fun formatDuration(seconds: Double): String = when {
    seconds < 1 -> "${(seconds * 1000).toInt()}ms"
    seconds < 60 -> String.format(Locale.US, "%.1fs", seconds)
    else -> "${(seconds / 60).toInt()}m ${(seconds % 60).toInt()}s"
}
