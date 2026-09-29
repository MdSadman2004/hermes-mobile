package com.hermes.mobile.ui.home

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
import com.hermes.mobile.core.connection.ConnectionProfile
import com.hermes.mobile.ui.components.ConnectionPill
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.PulseDot
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes
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
    val systemStates by vm.systemStates.collectAsState()
    var pendingForget by remember { mutableStateOf<ConnectionProfile?>(null) }

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
            SectionLabel("Connected to")
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
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

        SectionLabel("Systems")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { profile ->
                val active = (conn as? ConnState.Connected)?.profile?.id == profile.id
                NavRow(
                    title = profile.label,
                    subtitle = profile.displayAddress +
                        (profile.lastSeenAt?.let { " · seen ${formatSeen(it)}" } ?: ""),
                    icon = Icons.Outlined.Computer,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (active) MetaChip("current")
                            SystemStatus(systemStates[profile.id])
                        }
                    },
                    onClick = if (active) null else ({ vm.switchTo(profile) }),
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
}

/**
 * Reachability of one paired system, from a live probe. The state is spelled
 * out, not just dotted — colour alone is not a status.
 */
@Composable
private fun SystemStatus(online: Boolean?) {
    val dot = when (online) {
        true -> MaterialTheme.hermes.online
        false -> MaterialTheme.hermes.offline
        null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(color = dot, animating = false, size = 7)
        Spacer(Modifier.width(5.dp))
        Text(
            when (online) {
                true -> "online"
                false -> "offline"
                null -> "…"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
