package com.hermes.mobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.core.terminal.PtyState
import com.hermes.mobile.ui.components.PulseDot
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.HermesTerminalStyle
import com.hermes.mobile.ui.theme.hermes

/**
 * The full desktop TUI, over /api/pty.
 *
 * Scrollback is a real terminal surface — its own near-black material, no
 * wrapping, and a monospace face that here is doing its actual job rather than
 * signalling "technical". The key row exists because Android's IME has no Esc,
 * no Tab, no Ctrl and no arrows, and every one of those is load-bearing in a
 * TUI.
 */
@Composable
fun TerminalScreen(vm: TerminalViewModel = hiltViewModel()) {
    val scrollback by vm.scrollback.collectAsState()
    val state by vm.state.collectAsState()
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    val semantics = MaterialTheme.hermes

    LaunchedEffect(Unit) { vm.connect() }
    DisposableEffect(Unit) { onDispose { vm.disconnect() } }
    LaunchedEffect(scrollback) { vScroll.animateScrollTo(vScroll.maxValue) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PulseDot(
                color = when (state) {
                    PtyState.OPEN -> semantics.online
                    PtyState.ENDED, PtyState.CLOSED -> semantics.offline
                    else -> MaterialTheme.colorScheme.secondary
                },
                animating = state == PtyState.CONNECTING || state == PtyState.RECONNECTING,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (state) {
                    PtyState.OPEN -> "attached"
                    PtyState.CONNECTING -> "attaching…"
                    PtyState.RECONNECTING -> "reattaching…"
                    PtyState.ENDED -> "session ended"
                    PtyState.CLOSED -> "detached"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(scrollback)) },
                modifier = Modifier.semantics { contentDescription = "Copy all scrollback" },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, Modifier.size(17.dp))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .background(semantics.code, RoundedCornerShape(12.dp))
                // A hairline around the scrollback: the terminal is a pane of
                // glass over the ink, not a floating grey box.
                .border(1.dp, semantics.codeBorder, RoundedCornerShape(12.dp)),
        ) {
            Text(
                scrollback.ifBlank { "Waiting for the PTY…" },
                style = HermesTerminalStyle,
                color = semantics.onCode,
                softWrap = false,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(10.dp)
                    .semantics { contentDescription = "Terminal output" },
            )
        }

        Column(Modifier.navigationBarsPadding()) {
            KeyRow(vm)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { new ->
                        // Whole-value replacement from the IME → normalized resend.
                        vm.noteImeReplacement()
                        input = new
                    },
                    placeholder = { Text("type a command…") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = HermesMono),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Terminal input" },
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        if (input.isNotEmpty()) {
                            vm.sendInput(input + "\r")
                            input = ""
                        }
                    },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Send to terminal"
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The keys Android's soft keyboard does not have. Ported from the web client's
 * pty-mobile-input key set, in the order a hand reaches for them: escape and
 * completion first, then navigation, then the interrupts.
 */
@Composable
private fun KeyRow(vm: TerminalViewModel) {
    val keys = listOf(
        "Esc" to "\u001B",
        "Tab" to "\t",
        "\u2191" to "\u001B[A",
        "\u2193" to "\u001B[B",
        "\u2190" to "\u001B[D",
        "\u2192" to "\u001B[C",
        "^C" to "\u0003",
        "^D" to "\u0004",
        "^Z" to "\u001A",
        "|" to "|",
        "~" to "~",
        "/" to "/",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { (label, code) ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                onClick = { vm.sendInput(code) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Key $label" },
            ) {
                Box(
                    Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = HermesMono,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}
