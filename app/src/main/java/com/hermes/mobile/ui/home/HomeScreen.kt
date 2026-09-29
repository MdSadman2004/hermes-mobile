package com.hermes.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.ConnectionProfile
import com.hermes.mobile.ui.components.ConnectionPill
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.Meter
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.PulseDot
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.connect.AddSystemDialog
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes
import com.hermes.mobile.ui.theme.revealOnEnter
import com.hermes.mobile.ui.theme.tapScale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The paired machines, and everything about the one you are on.
 *
 * This is also the only place that unpairs a PC. That is deliberate: it wipes
 * a credential out of the vault, so it belongs behind a confirmation on a
 * screen you had to navigate to, not on a toolbar next to "new session".
 */
@Composable
fun ConnectionPanel(vm: HomeViewModel = hiltViewModel()) {
    val conn by vm.connState.collectAsState()
    val channel by vm.channelState.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val systemViews by vm.systemViews.collectAsState()
    var pendingForget by remember { mutableStateOf<ConnectionProfile?>(null) }
    var addSystemOpen by remember { mutableStateOf(false) }

    // Badge each system online/offline while this panel is on screen — it is
    // the one place that answers "which of my machines can I reach right now".
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshSystemStates()
            delay(10_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        (conn as? ConnState.Connected)?.let { c ->
            Column(Modifier.revealOnEnter(0)) {
                SectionLabel("Connected to")
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionPill(conn, compact = true)
                            Spacer(Modifier.width(10.dp))
                            Text(c.profile.label, style = MaterialTheme.typography.titleMedium)
                        }
                        Fact("Address", c.profile.displayAddress)
                        Fact("Transport", if (c.profile.secure) "https / wss" else "http / ws")
                        Fact("Auth", c.profile.auth.name.lowercase())
                        Fact("Socket", channel.toString().substringAfterLast('.').lowercase())
                        c.status?.version?.let { Fact("Hermes", "v$it") }
                        c.status?.gatewayState?.let { Fact("Gateway", it) }
                        Fact("Active sessions", (c.status?.activeSessions ?: 0).toString())
                    }
                }
            }
        }

        Column(Modifier.revealOnEnter(1)) {
            SectionLabel("Systems")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEachIndexed { i, profile ->
                    val active = (conn as? ConnState.Connected)?.profile?.id == profile.id
                    SystemCard(
                        profile = profile,
                        view = systemViews.firstOrNull { it.id == profile.id },
                        active = active,
                        onSwitch = { vm.switchTo(profile) },
                    )
                }
                if (profiles.isEmpty()) {
                    Text(
                        "No systems paired.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { addSystemOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Pair another system" },
            ) {
                Text("Pair another system")
            }
        }

        Column(Modifier.revealOnEnter(2)) {
            SectionLabel("Danger zone")
            (conn as? ConnState.Connected)?.profile?.let { profile ->
                OutlinedButton(
                    onClick = { pendingForget = profile },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Unpair ${profile.label}" },
                ) {
                    Text("Unpair ${profile.label}", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Removes the saved credential from this phone. Nothing on the PC changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingForget?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingForget = null },
            title = { Text("Unpair ${profile.label}?") },
            text = {
                Text(
                    "The stored credential is deleted from this phone. You'll need the " +
                        "pairing QR again to reconnect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.forget(profile)
                        pendingForget = null
                    },
                ) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = null }) { Text("Cancel") }
            },
        )
    }

    if (addSystemOpen) {
        AddSystemDialog(onDismiss = { addSystemOpen = false })
    }
}

/**
 * One paired system: who it is, whether it answers, and — the question this
 * card exists for — how loaded it is. Reachability plus a memory/disk
 * fallback come from the public status endpoint; CPU and precise percentages
 * come from the authenticated stats endpoint when a credential is held.
 */
@Composable
private fun SystemCard(
    profile: ConnectionProfile,
    view: ConnectionManager.SystemView?,
    active: Boolean,
    onSwitch: () -> Unit,
) {
    val online = view?.online
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .tapScale(enabled = !active) { onSwitch() },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(
                    color = when (online) {
                        true -> MaterialTheme.hermes.online
                        false -> MaterialTheme.hermes.offline
                        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    animating = false,
                )
                Spacer(Modifier.width(8.dp))
                Text(profile.label, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (active) MetaChip("current")
                Spacer(Modifier.width(6.dp))
                Text(
                    when (online) {
                        true -> "online"
                        false -> "offline"
                        null -> "checking…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (online) {
                        true -> MaterialTheme.hermes.online
                        false -> MaterialTheme.hermes.offline
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(profile.displayAddress, style = MaterialTheme.typography.bodySmall,
                fontFamily = HermesMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                view?.version?.let { "v$it" },
                view?.gatewayState?.let { "gateway $it" },
            )
            if (meta.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    meta.forEach { MetaChip(it) }
                }
            }
            if (view != null && view.online) {
                UsageMeter("CPU", view.cpuPercent)
                UsageMeter("Memory", view.memPercent)
                UsageMeter("Disk", view.diskPercent)
            } else if (profile.lastSeenAt != null) {
                Text(
                    "last seen ${formatSeen(profile.lastSeenAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One labelled meter; dims to "—" when the server has no reading. */
@Composable
private fun UsageMeter(label: String, percent: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Meter(
            fraction = (percent ?: 0) / 100f,
            modifier = Modifier.weight(1f),
            tone = when {
                percent == null -> MaterialTheme.colorScheme.surfaceContainerHighest
                percent >= 90 -> MaterialTheme.colorScheme.error
                percent >= 70 -> MaterialTheme.hermes.warning
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            percent?.let { "$it%" } ?: "—",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = HermesMono,
            modifier = Modifier.width(38.dp),
        )
    }
}

@Composable
private fun Fact(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            key,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = HermesMono)
    }
}

private fun formatSeen(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
