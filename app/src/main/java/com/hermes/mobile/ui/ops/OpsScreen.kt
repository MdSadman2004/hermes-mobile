package com.hermes.mobile.ui.ops

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.components.Meter
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.PulseDot
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.files.FilesScreen
import com.hermes.mobile.ui.home.ConnectionPanel
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes
import com.hermes.mobile.ui.theme.revealOnEnter
import kotlinx.coroutines.delay

private enum class Pane(val title: String) {
    MENU("Ops"),
    FILES("Files"),
    MODELS("Model"),
    SKILLS("Skills"),
    CRON("Schedule"),
    GIT("Git"),
    USAGE("Usage"),
    LOGS("Logs"),
    CONNECTION("This PC"),
    APPEARANCE("Appearance"),
}

/**
 * Everything about the machine, one level down from the conversation.
 *
 * Ops is a menu of panels rather than a single scrolling wall because each of
 * these hits a different, sometimes slow, server endpoint — and because a
 * single wall would put "stop the gateway" one mis-scroll away from "list my
 * skills".
 */
@Composable
fun OpsScreen(vm: OpsViewModel = hiltViewModel()) {
    var pane by rememberSaveable { mutableStateOf(Pane.MENU.name) }
    val current = Pane.valueOf(pane)

    // System Back leaves the panel, not the app.
    BackHandler(enabled = current != Pane.MENU) { pane = Pane.MENU.name }

    if (current == Pane.MENU) {
        OpsMenu(vm) { pane = it.name }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { pane = Pane.MENU.name },
                modifier = Modifier.semantics { contentDescription = "Back to Ops" },
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Text(current.title, style = MaterialTheme.typography.headlineSmall)
        }
        when (current) {
            Pane.FILES -> FilesScreen()
            Pane.MODELS -> ModelPanel(vm)
            Pane.SKILLS -> SkillsPanel(vm)
            Pane.CRON -> CronPanel(vm)
            Pane.GIT -> GitPanel(vm)
            Pane.USAGE -> UsagePanel(vm)
            Pane.LOGS -> LogsPanel(vm)
            Pane.CONNECTION -> ConnectionPanel()
            Pane.APPEARANCE -> AppearancePanel()
            Pane.MENU -> Unit
        }
    }
}

@Composable
private fun OpsMenu(vm: OpsViewModel, onOpen: (Pane) -> Unit) {
    val conn by vm.connState.collectAsState()
    val channel by vm.channelState.collectAsState()
    val stats by vm.stats.collectAsState()
    val models by vm.models.collectAsState()
    val systems by vm.systems.collectAsState()
    var panicArmed by remember { mutableStateOf(false) }

    // Refresh while the menu is on screen: the systems strip is the one part
    // of Ops that is live data, and a stale "CPU 3%" reads as broken.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshOverview()
            delay(15_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // The scaffold already keeps content clear of the navigation bar,
            // so this is only an end-of-list margin — padding for the bar as
            // well left a dead band the height of three rows.
            .padding(bottom = 24.dp),
    ) {
        (conn as? ConnState.Connected)?.let { c ->
            Column(Modifier.revealOnEnter(0)) {
                SectionLabel("Machine")
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
                        Text(c.profile.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            c.profile.displayAddress,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = HermesMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "Hermes ${c.status?.version ?: "?"} · gateway " +
                                "${c.status?.gatewayState ?: "unknown"} · socket " +
                                channel.toString().substringAfterLast('.').lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        stats?.let { s ->
                            s.cpuPercent?.let { StatMeter("CPU", it) }
                            s.memoryPercent?.let { StatMeter("Memory", it) }
                            s.diskPercent?.let { StatMeter("Disk", it) }
                        }
                    }
                }
            }
        }

        if (systems.isNotEmpty()) {
            SectionLabel("Systems")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                systems.forEachIndexed { index, s ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (s.isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.fillMaxWidth().revealOnEnter(index + 1),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PulseDot(
                                color = if (s.online) MaterialTheme.hermes.online
                                else MaterialTheme.hermes.offline,
                                animating = false,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.label + if (s.isCurrent) " · current" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    s.address + (s.version?.let { " · v$it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = HermesMono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // The metrics get their own line. Squeezed into
                                // a right-hand column they wrapped through the
                                // address — "…:9119CPU 13%" — which reads as a
                                // corrupted address rather than as a reading.
                                if (s.online) {
                                    Text(
                                        listOfNotNull(
                                            s.cpuPercent?.let { "CPU $it%" },
                                            s.memPercent?.let { "Mem $it%" },
                                            s.diskPercent?.let { "Disk $it%" },
                                        ).joinToString(" · ").ifBlank { "online" },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = HermesMono,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                    )
                                } else {
                                    Text(
                                        "offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = HermesMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("Workspace")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavRow(
                "Files", "Browse and preview the PC's filesystem",
                Icons.Outlined.FolderOpen,
            ) { onOpen(Pane.FILES) }
            NavRow(
                "Git", "Branch, working tree, what changed",
                Icons.Outlined.Difference,
            ) { onOpen(Pane.GIT) }
        }

        SectionLabel("Agent")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavRow(
                "Model",
                models?.currentModel ?: "Choose which model new sessions use",
                Icons.Outlined.Memory,
            ) { onOpen(Pane.MODELS) }
            NavRow(
                "Skills", "Turn capabilities on and off",
                Icons.Outlined.AutoAwesome,
            ) { onOpen(Pane.SKILLS) }
            NavRow(
                "Schedule", "Cron jobs, and running one now",
                Icons.Outlined.Schedule,
            ) { onOpen(Pane.CRON) }
        }

        SectionLabel("Diagnostics")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavRow(
                "Usage", "Tokens and spend over time",
                Icons.Outlined.Insights,
            ) { onOpen(Pane.USAGE) }
            NavRow("Logs", "Tail the agent log", Icons.Outlined.Subject) { onOpen(Pane.LOGS) }
            NavRow(
                "This PC", "Paired machines, transport, unpairing",
                Icons.Outlined.Computer,
            ) { onOpen(Pane.CONNECTION) }
            NavRow(
                "Appearance", "Typeface and reading comfort",
                Icons.Outlined.Palette,
            ) { onOpen(Pane.APPEARANCE) }
        }

        SectionLabel("Gateway")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.gateway("start") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Start") }
            OutlinedButton(
                onClick = { vm.gateway("restart") },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Restart") }
        }

        // Two-step, because this stops message dispatch for every channel the
        // gateway serves. One tap arms it; the label says exactly what happens.
        SectionLabel("Stop everything")
        Button(
            onClick = {
                if (panicArmed) {
                    vm.gateway("stop")
                    panicArmed = false
                } else {
                    panicArmed = true
                }
            },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = if (panicArmed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (panicArmed) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics {
                    contentDescription =
                        if (panicArmed) "Confirm stopping the gateway" else "Stop the gateway"
                },
        ) {
            Text(if (panicArmed) "Tap again to stop the gateway" else "Stop the gateway")
        }
        Text(
            "Halts dispatch on every messaging channel until you start it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StatMeter(label: String, percent: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Meter(
            fraction = (percent / 100.0).toFloat(),
            modifier = Modifier.weight(1f),
            tone = when {
                percent >= 90 -> MaterialTheme.colorScheme.error
                percent >= 70 -> com.hermes.mobile.ui.theme.LocalHermesSemantics.current.warning
                else -> MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${percent.toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = HermesMono,
            modifier = Modifier.width(38.dp),
        )
    }
}

/** Shared by every panel: a hairline while its endpoint is in flight. */
@Composable
internal fun PanelProgress(vm: OpsViewModel, tag: String) {
    val busy by vm.busy.collectAsState()
    if (busy == tag) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    } else {
        Spacer(Modifier.height(2.dp))
    }
}
