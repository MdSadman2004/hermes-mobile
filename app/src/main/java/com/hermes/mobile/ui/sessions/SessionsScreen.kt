package com.hermes.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.domain.model.SessionSummary
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.SkeletonList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Every session from the same state.db the desktop uses.
 *
 * Search is here because the list is genuinely long for anyone who uses the
 * agent daily, and scrolling a phone through two hundred rows to find
 * yesterday afternoon's session is the difference between this tab being
 * useful and being decorative.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (SessionSummary) -> Unit,
    onNew: () -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    val loading by vm.loading.collectAsState()
    val hydrated by vm.hydrated.collectAsState()
    val query by vm.query.collectAsState()
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val haptics = LocalHapticFeedback.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                placeholder = { Text("Search sessions") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { vm.setQuery("") },
                            modifier = Modifier.semantics { contentDescription = "Clear search" },
                        ) { Icon(Icons.Outlined.Close, contentDescription = null, Modifier.size(18.dp)) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Search sessions" },
            )

            // A hairline that only appears while refreshing — a full-screen
            // spinner over a list that already has content is a regression.
            if (loading && sessions.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(max = 2.dp))
            }

            when {
                sessions.isEmpty() && !hydrated ->
                    SkeletonList(rows = 6, modifier = Modifier.padding(16.dp))

                sessions.isEmpty() && query.isNotBlank() -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "No match",
                    hint = "Nothing here mentions \"$query\". Titles and previews are searched.",
                )

                sessions.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = "No sessions yet",
                    hint = "Sessions you start here and on the desktop share one history.",
                    action = {
                        TextButton(
                            onClick = onNew,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Start one") }
                    },
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        // Rows animate on arrival and on re-order: the list is
                        // re-sorted by activity, so a session that jumps to the
                        // top visibly *moves* rather than teleporting.
                        Box(Modifier.animateItem()) {
                            SessionRow(
                                session = session,
                                onClick = { onOpen(session) },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    pendingDelete = session
                                },
                            )
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("New session") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .semantics { contentDescription = "Start a new session" },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this session?") },
            text = {
                Text(
                    "\"${target.title.ifBlank { "untitled" }}\" and its transcript are removed " +
                        "from your PC. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target)
                        pendingDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription =
                    "Session ${session.title.ifBlank { "untitled" }}, long press to delete"
            },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // A clay spine: the list reads as a shelf of records, and the eye
            // gets a landing edge without the row needing a heading style.
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 34.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(2.dp),
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    session.title.ifBlank { "Untitled session" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank()) {
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        relativeTime(session.startedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    )
                    if (session.messageCount > 0) {
                        Spacer(Modifier.width(2.dp))
                        MetaChip("${session.messageCount} msg")
                    }
                    if (session.source.isNotBlank()) {
                        MetaChip(session.source)
                    }
                }
            }
        }
    }
}

/**
 * "3h ago" beats "Sep 2, 14:05" for the only question anyone asks of this
 * list — how recent is it — and falls back to a date once that stops being
 * the interesting part.
 */
private fun relativeTime(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val millis = (epochSeconds * 1000).toLong()
    val delta = System.currentTimeMillis() - millis
    if (delta < 0) return "just now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
