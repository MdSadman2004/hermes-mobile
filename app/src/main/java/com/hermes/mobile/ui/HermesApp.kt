package com.hermes.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.ui.cockpit.ActivityScreen
import com.hermes.mobile.ui.cockpit.CockpitScreen
import com.hermes.mobile.ui.cockpit.CockpitViewModel
import com.hermes.mobile.ui.cockpit.SessionSheet
import com.hermes.mobile.ui.connect.ConnectScreen
import com.hermes.mobile.ui.connect.ConnectViewModel
import com.hermes.mobile.ui.components.ConnectionPill
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.ops.OpsScreen
import com.hermes.mobile.ui.sessions.SessionsScreen
import com.hermes.mobile.ui.terminal.TerminalScreen
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.hermes.mobile.ui.theme.hermesScreenIn
import com.hermes.mobile.ui.theme.hermesScreenOut

/**
 * Five destinations, which is what a Material navigation bar is for and what
 * this product actually has: the live conversation, the machine's own work,
 * its history, the raw TUI, and everything about the machine.
 * `NavigationSuiteScaffold` promotes the bar to a rail on a tablet or an
 * unfolded foldable without a second layout.
 *
 * Switching tabs is a *place change*, so it is animated as one: the incoming
 * screen fades up with a small drift while the outgoing one settles away.
 * Without that, tapping "History" reads as the same page with different text.
 */
private enum class Destination(val label: String, val icon: ImageVector) {
    COCKPIT("Chat", Icons.Outlined.Forum),
    // The tool-call feed the conversation no longer carries. It is a top-level
    // destination rather than a hidden panel because "what is it actually
    // doing on my PC right now" is a question you ask mid-turn, one-handed,
    // and it must never be more than one tap from the conversation.
    ACTIVITY("Activity", Icons.Outlined.Bolt),
    SESSIONS("History", Icons.Outlined.History),
    TERMINAL("Terminal", Icons.Outlined.Terminal),
    OPS("Ops", Icons.Outlined.Tune),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesApp(
    vm: ShellViewModel = hiltViewModel(),
    connectVm: ConnectViewModel = hiltViewModel(),
    cockpitVm: CockpitViewModel = hiltViewModel(),
    deepLinkBus: DeepLinkBus = vm.deepLinkBus,
    appearanceVm: com.hermes.mobile.ui.ops.AppearanceViewModel = hiltViewModel(),
) {
    // Read the stored typeface at the root so the choice is applied on the very
    // first frame; resolving it deeper would flash the default font on launch.
    val appFont by appearanceVm.font.collectAsState()
    HermesMobileTheme(appFont = appFont) {
        val conn by vm.connState.collectAsState()
        val toolLog by cockpitVm.toolLog.collectAsState()
        val liveActivity by cockpitVm.activity.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        var destination by rememberSaveable { mutableStateOf(Destination.COCKPIT.name) }
        var sheetOpen by remember { mutableStateOf(false) }
        val current = Destination.valueOf(destination)

        // Every ViewModel's userMessage channel lands here — errors are visible.
        LaunchedEffect(Unit) { connectVm.userMessage.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(Unit) { cockpitVm.userMessage.collect { snackbar.showSnackbar(it) } }
        // Connection notices (failover hops, rebinds) must surface in the
        // cockpit too — they were only visible on the pairing screen before,
        // so a silent system hop looked like a glitch.
        LaunchedEffect(Unit) { vm.notices.collect { snackbar.showSnackbar(it) } }

        // Doorbell / notification deep links → open that session.
        LaunchedEffect(Unit) {
            deepLinkBus.links.collect { storedId ->
                cockpitVm.resumeAndOpen(storedId, "Linked session")
                destination = Destination.COCKPIT.name
            }
        }

        // "Send to Hermes" shares → new prompt in the cockpit.
        LaunchedEffect(Unit) {
            vm.shareBus.shares.collect { text ->
                cockpitVm.send(text)
                destination = Destination.COCKPIT.name
            }
        }

        // Shared FILES are uploaded to the PC, then the user is shown where
        // they landed. Wait for a live connection first: a share that arrives
        // during a cold start would otherwise fail on "Not connected" while the
        // socket was two seconds from ready.
        LaunchedEffect(Unit) {
            vm.shareBus.files.collect { uris ->
                snapshotFlow { conn }.first { it is ConnState.Connected }
                vm.uploadShared(uris)
            }
        }

        LaunchedEffect(Unit) { vm.userMessage.collect { snackbar.showSnackbar(it) } }

        // System Back returns to the Cockpit before it leaves the app — the
        // predictive-back contract every Android user already has.
        BackHandler(enabled = current != Destination.COCKPIT) {
            destination = Destination.COCKPIT.name
        }

        // The pairing screen cross-fades into the app proper: the moment the
        // socket comes up is the app becoming usable, and it should look like
        // a door opening rather than a screen swap.
        AnimatedContent(
            targetState = conn is ConnState.Connected,
            transitionSpec = { hermesScreenIn() togetherWith hermesScreenOut() },
            label = "gate",
        ) { connected ->
            if (!connected) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ConnectScreen(vm = connectVm)
                    }
                }
                return@AnimatedContent
            }

            NavigationSuiteScaffold(
                navigationSuiteItems = {
                    Destination.entries.forEach { entry ->
                        item(
                            selected = current == entry,
                            onClick = { destination = entry.name },
                            icon = {
                                // A live dot on Activity is the only signal that
                                // work is happening while the user is on another
                                // tab. Without it, moving tool calls out of the
                                // chat would have made background work invisible.
                                // The badge scales in, so a turn *starting*
                                // catches the eye from another tab.
                                if (entry == Destination.ACTIVITY && liveActivity?.running == true) {
                                    BadgedBox(badge = { Badge() }) {
                                        Icon(entry.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(entry.icon, contentDescription = null)
                                }
                            },
                            label = { Text(entry.label) },
                            modifier = Modifier.semantics { contentDescription = "${entry.label} tab" },
                        )
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        Column {
                            CenterAlignedTopAppBar(
                                title = { CockpitTitle(cockpitVm, current) },
                                navigationIcon = {
                                    if (current == Destination.COCKPIT) {
                                        IconButton(
                                            onClick = { cockpitVm.createAndOpen() },
                                            modifier = Modifier.semantics {
                                                contentDescription = "Start a new session"
                                            },
                                        ) { Icon(Icons.Outlined.Add, contentDescription = null) }
                                    }
                                },
                                actions = {
                                    if (current == Destination.COCKPIT) {
                                        IconButton(
                                            onClick = {
                                                cockpitVm.refreshUsage()
                                                sheetOpen = true
                                            },
                                            modifier = Modifier.semantics {
                                                contentDescription = "Session actions"
                                            },
                                        ) { Icon(Icons.Outlined.MoreVert, contentDescription = null) }
                                    }
                                },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                            // A hairline instead of a shadow: the bars and the
                            // page are the same material, separated by a line.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                            )
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                    containerColor = MaterialTheme.colorScheme.background,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedContent(
                            targetState = current,
                            transitionSpec = { hermesScreenIn() togetherWith hermesScreenOut() },
                            label = "tab",
                        ) { dest ->
                            when (dest) {
                                Destination.COCKPIT -> CockpitScreen(
                                    vm = cockpitVm,
                                    onOpenActivity = { destination = Destination.ACTIVITY.name },
                                )
                                Destination.ACTIVITY -> ActivityScreen(entries = toolLog)
                                Destination.SESSIONS -> SessionsScreen(
                                    onOpen = { summary ->
                                        cockpitVm.resumeAndOpen(summary.id, summary.title)
                                        destination = Destination.COCKPIT.name
                                    },
                                    onNew = {
                                        cockpitVm.createAndOpen()
                                        destination = Destination.COCKPIT.name
                                    },
                                )
                                Destination.TERMINAL -> TerminalScreen()
                                Destination.OPS -> OpsScreen()
                            }
                        }
                    }
                }
            }
        }

        if (sheetOpen) {
            SessionSheet(vm = cockpitVm, onDismiss = { sheetOpen = false })
        }
    }
}

/**
 * The cockpit's title carries the state you'd otherwise have to go looking
 * for: which PC, which session, which model, how full the context is. On the
 * other tabs it is just the tab name.
 */
@Composable
private fun CockpitTitle(cockpitVm: CockpitViewModel, current: Destination) {
    val conn by cockpitVm.connState.collectAsState()
    val title by cockpitVm.activeTitle.collectAsState()
    val model by cockpitVm.model.collectAsState()
    val contextPercent by cockpitVm.contextPercent.collectAsState()

    // Compare the destination, not a label string: the tab was renamed to
    // "Chat" once and this check silently died with it.
    if (current != Destination.COCKPIT) {
        Text(
            current.label,
            style = MaterialTheme.typography.headlineSmall,
        )
        return
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Non-compact on purpose: the label answers "which system am I on?" —
        // 'foundry' or 'Hermes PC' — and it tracks the connection state.
        Box(Modifier.padding(end = 8.dp)) { ConnectionPill(conn, compact = false) }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        model?.let {
            Spacer(Modifier.width(8.dp))
            MetaChip(it.substringAfterLast('/'))
        }
        contextPercent?.takeIf { it > 0 }?.let {
            Spacer(Modifier.width(6.dp))
            MetaChip(
                "$it%",
                icon = Icons.Outlined.Bolt,
                tone = if (it >= 85) MaterialTheme.colorScheme.error else null,
            )
        }
    }
}
