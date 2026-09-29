package com.hermes.mobile.ui.cockpit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.hermes.mobile.domain.model.SlashCommand
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile
import com.hermes.mobile.ui.components.ActivityStrip
import com.hermes.mobile.ui.components.ComposerAttachmentStrip
import com.hermes.mobile.ui.components.ArtifactCard
import com.hermes.mobile.ui.components.AttachmentGroupView
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
import com.hermes.mobile.ui.components.CodeBlock
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MarkdownText
import com.hermes.mobile.ui.components.RunningTurnStrip
import com.hermes.mobile.ui.components.ThinkingOrb
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.HermesMotion
import com.hermes.mobile.ui.theme.hermes

/**
 * The Cockpit — streaming transcript, tool timeline, live-turn controls and
 * the composer.
 *
 * The scene this screen is designed for: the phone comes out of a pocket
 * because a turn is running or blocked. So the two things that must be
 * readable in one glance are *what the agent is doing right now* and *whether
 * it needs an answer* — which is why the approval card is the loudest element
 * on the surface and everything else is deliberately quiet.
 */
@Composable
fun CockpitScreen(
    vm: CockpitViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onOpenActivity: () -> Unit = {},
) {
    val items by vm.items.collectAsState()
    val phase by vm.turnPhase.collectAsState()
    val activity by vm.activity.collectAsState()
    val commands by vm.commands.collectAsState()
    val pendingAttachments by vm.pendingAttachments.collectAsState()
    val sending by vm.sending.collectAsState()
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Follow the stream unless the user DELIBERATELY scrolled away. A bare
    // `!canScrollForward` check un-pinned on every streamed line — content
    // grows faster than the follow lands — so the transcript quietly stopped
    // auto-scrolling mid-turn. Only a real drag breaks the follow now, and
    // returning to the bottom restores it.
    var followStream by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followStream = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            if (!canForward) followStream = true
        }
    }

    // A new row animates into place; text GROWING inside the streaming row
    // follows instantly. Animating on every delta queued one scroll animation
    // per tick, and the overlapping tweens read as stutter.
    val streamingLength = (items.lastOrNull() as? TranscriptItem.AssistantMessage)
        ?.takeIf { it.streaming }?.text?.length ?: 0
    LaunchedEffect(items.size, streamingLength) {
        if (items.isEmpty() || !followStream) return@LaunchedEffect
        if (streamingLength > 0) listState.scrollToItem(items.lastIndex)
        else listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(phase) {
        if (phase == TurnPhase.RUNNING) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = "Nothing here yet",
                        hint = "Send a prompt, or type / to run one of your PC's " +
                            "slash commands. Anything you start here keeps running " +
                            "on the desktop.",
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        // New rows fade/slide in via animateItem; placement
                        // changes animate too. Streaming growth inside a row
                        // changes its size, not its position, so it never
                        // re-triggers the entry animation.
                        Box(Modifier.fillMaxWidth().animateItem()) {
                        when (item) {
                            is TranscriptItem.UserMessage -> Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (item.attachments.isNotEmpty()) {
                                    AttachmentGroupView(
                                        attachments = item.attachments,
                                        onOpen = vm::openAttachment,
                                        onSave = vm::saveAttachment,
                                        onNeedsFetch = vm::materialize,
                                    )
                                }
                                if (item.text.isNotBlank()) UserBubble(item.text)
                            }
                            is TranscriptItem.AssistantMessage -> AssistantBlock(
                                item,
                                onOpen = vm::openAttachment,
                                onSave = vm::saveAttachment,
                                onNeedsFetch = vm::materialize,
                            )
                            is TranscriptItem.ThinkingBlock -> ThinkingRow(item)
                            // Tool calls deliberately do NOT render here — they
                            // live on the Activity screen. See ActivityStrip.
                            is TranscriptItem.ToolCallItem -> Unit
                            is TranscriptItem.CommandOutput -> CommandOutputRow(item)
                            is TranscriptItem.ApprovalCard -> ApprovalRow(
                                item,
                                onRespond = { choice, remember ->
                                    vm.respondApproval(item, choice, remember)
                                },
                            )
                            is TranscriptItem.StatusLine -> StatusRow(item.text)
                            is TranscriptItem.ArtifactItem -> ArtifactCard(
                                state = item.artifact,
                                onRecompile = { vm.recompileArtifact(item.artifact) },
                            )
                            is TranscriptItem.AttachmentGroup -> AttachmentGroupView(
                                attachments = item.attachments,
                                onOpen = vm::openAttachment,
                                onSave = vm::saveAttachment,
                                onNeedsFetch = vm::materialize,
                            )
                        }
                        }
                    }
                }
            }

            // What the PC is doing right now, in one changing line. This is
            // what replaced the inline terminal spam.
            ActivityStrip(activity = activity, onOpenActivity = onOpenActivity)

            // Save/Open attachments when the user taps the buttons on their cards.
            LaunchedEffect(Unit) {
                vm.attachmentSaves.collect { att ->
                    vm.saveAttachmentToPhone(att)
                }
            }
            LaunchedEffect(Unit) {
                vm.attachmentOpens.collect { att -> vm.openAttachment(att) }
            }

            // Auto-download files the agent delivered via MEDIA: — the user said
            // "send me the deck", and they said it on the phone, so the file should
            // just appear in Downloads without tapping every card.
            LaunchedEffect(items.size) {
                items.filterIsInstance<TranscriptItem.AssistantMessage>()
                    .lastOrNull()
                    ?.attachments
                    ?.filter { it.autoDownload && it.remotePath != null && !it.downloading && !it.downloadedToPhone }
                    ?.forEach { vm.saveAttachmentToPhone(it) }
            }

            AnimatedVisibility(
                visible = phase == TurnPhase.RUNNING,
                enter = expandVertically(spring(dampingRatio = 0.78f, stiffness = 320f)) +
                    fadeIn(tween(180)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(140)),
            ) {
                LiveTurnBar(
                    activity = activity,
                    onInterrupt = vm::interrupt,
                    onSteer = vm::steer,
                )
            }

            Composer(
                commands = commands,
                onSend = vm::send,
                onSlash = vm::runSlashCommand,
                onOpenCommands = vm::loadCommands,
                pendingAttachments = pendingAttachments,
                onAttach = vm::attachUri,
                onRemoveAttachment = vm::removeAttachment,
                sending = sending,
                onSendDetached = vm::sendDetached,
            )
        }

        // Jump-to-latest, shown only when the user has scrolled away from it.
        AnimatedVisibility(
            visible = items.isNotEmpty() && !followStream,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 96.dp),
        ) {
            FloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(items.size - 1) } },
                modifier = Modifier.size(44.dp).semantics {
                    contentDescription = "Scroll to the newest message"
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null, Modifier.size(20.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transcript rows
// ---------------------------------------------------------------------------

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
            // A warm hairline on the clay fill keeps the bubble from looking
            // like a flat sticker against the ink ground.
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The agent's own words. Rendered as Markdown, because that is what it writes:
 * a fenced command and the sentence introducing it used to look identical.
 *
 * Files the agent produced are rendered as real cards underneath, not as the
 * raw `@image:D:/…` / `MEDIA:` markers it writes — those are a machine handle,
 * and a Windows path wrapped across three lines of a phone screen is not an
 * answer to "send me the deck".
 */
@Composable
private fun AssistantBlock(
    item: TranscriptItem.AssistantMessage,
    onOpen: (com.hermes.mobile.domain.model.ChatAttachment) -> Unit = {},
    onSave: (com.hermes.mobile.domain.model.ChatAttachment) -> Unit = {},
    onNeedsFetch: (com.hermes.mobile.domain.model.ChatAttachment) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A small clay dot rather than a word mark: the reply is already
            // unambiguously the agent's, so the label only needs to say *when*
            // it is still being written.
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (item.streaming) 1f else 0.55f,
                        ),
                        RoundedCornerShape(3.dp),
                    ),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (item.streaming) "Hermes · writing" else "Hermes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            item.usageContextPercent?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$it% context",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!item.streaming && item.text.isNotBlank()) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(item.text)) },
                    modifier = Modifier.size(32.dp).semantics {
                        contentDescription = "Copy this reply"
                    },
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item.text.isNotBlank()) {
            MarkdownText(item.text, Modifier.padding(top = 2.dp))
        }
        if (item.attachments.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AttachmentGroupView(
                attachments = item.attachments,
                onOpen = onOpen,
                onSave = onSave,
                onNeedsFetch = onNeedsFetch,
            )
        }
        if (item.streaming) {
            Caret()
        }
    }
}

/** A blinking block cursor while text streams — cheaper to read than a spinner. */
@Composable
private fun Caret() {
    val blink = rememberInfiniteTransition(label = "caret")
    // Eased rather than linear: a linear blink reads as a hardware fault,
    // an eased one reads as a pen pausing between words.
    val alpha by blink.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            tween(760, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "caret-alpha",
    )
    Box(
        Modifier
            .padding(top = 3.dp)
            .size(width = 7.dp, height = 14.dp)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                RoundedCornerShape(2.dp),
            ),
    )
}

@Composable
private fun ThinkingRow(item: TranscriptItem.ThinkingBlock) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable { expanded = !expanded }
            .semantics {
                contentDescription =
                    if (expanded) "Reasoning, expanded" else "Reasoning, tap to expand"
            },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // While the model is actually reasoning the mark spins; once
                // it is done the row goes quiet. Motion here is a status, not
                // decoration — it means "this text is still being written".
                if (item.live) {
                    ThinkingOrb(size = 16.dp)
                } else {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = null,
                        Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (item.live) "reasoning…" else "reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) +
                    fadeIn(tween(160)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
            ) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = HermesMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CommandOutputRow(item: TranscriptItem.CommandOutput) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Terminal,
                contentDescription = null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                item.command,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = HermesMono,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(6.dp))
        CodeBlock(null, item.output.ifBlank { "(no output)" })
    }
}

@Composable
private fun StatusRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * The approval card — the reason this app exists on a phone.
 *
 * It is amber, not red: a routine "may I run this?" is a decision, not a
 * failure, and spending error red on the common case would make the colour
 * meaningless when something actually breaks. Every control clears 48dp,
 * because this gets tapped one-handed, walking.
 */
@Composable
private fun ApprovalRow(card: TranscriptItem.ApprovalCard, onRespond: (String, Boolean) -> Unit) {
    val semantics = MaterialTheme.hermes
    val resolved = card.resolved != null
    var alsoRemember by remember { mutableStateOf(false) }

    Surface(
        color = if (resolved) MaterialTheme.colorScheme.surfaceContainer
        else semantics.warningContainer,
        contentColor = if (resolved) MaterialTheme.colorScheme.onSurfaceVariant
        else semantics.onWarningContainer,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = if (resolved) 0.dp else 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (resolved) "Answered — ${card.resolved}" else "Waiting on you",
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            card.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            card.command?.takeIf { it.isNotBlank() }?.let { command ->
                val scroll = rememberScrollState()
                Surface(
                    color = semantics.code,
                    contentColor = semantics.onCode,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = HermesMono,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(scroll)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }

            if (!resolved) {
                if (card.allowSession || card.allowPermanent) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { alsoRemember = !alsoRemember }
                            .semantics {
                                contentDescription = "Also allow future identical commands"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = alsoRemember,
                            onCheckedChange = { alsoRemember = it },
                        )
                        Text(
                            if (card.allowPermanent) "Don't ask again for this command"
                            else "Allow for the rest of this session",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ApprovalChoices(card.choices, alsoRemember, onRespond)
            }
        }
    }
}

/**
 * Choices in server order, but with a deliberate visual hierarchy: the
 * permissive answer is filled, the middle ground tonal, and refusal outlined.
 * A row of identical text buttons made "deny" as easy to hit by accident as
 * "allow".
 */
@Composable
private fun ApprovalChoices(
    choices: List<String>,
    alsoRemember: Boolean,
    onRespond: (String, Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice ->
            val label = choice.replaceFirstChar { it.uppercase() }
            val modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Approval: $label" }
            when {
                choice.equals("deny", true) || choice.equals("no", true) ->
                    OutlinedButton(
                        onClick = { onRespond(choice, false) },
                        modifier = modifier,
                    ) { Text(label, maxLines = 1) }

                choice.equals("once", true) || choice.equals("yes", true) ->
                    Button(
                        onClick = { onRespond(choice, alsoRemember) },
                        modifier = modifier,
                    ) { Text(label, maxLines = 1) }

                else -> FilledTonalButton(
                    onClick = { onRespond(choice, alsoRemember) },
                    modifier = modifier,
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Live turn + composer
// ---------------------------------------------------------------------------

@Composable
private fun LiveTurnBar(
    activity: com.hermes.mobile.domain.model.AgentActivity?,
    onInterrupt: () -> Unit,
    onSteer: (String) -> Unit,
) {
    var steerMode by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (steerMode) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = steerText,
                    onValueChange = { steerText = it },
                    placeholder = { Text("Add a note to this turn…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (steerText.isNotBlank()) {
                            onSteer(steerText)
                            steerText = ""
                            steerMode = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Send the steer"
                    },
                ) { Text("Send") }
            }
        } else {
            Column {
                // The strip is the latency surface: an orb that keeps moving,
                // the PC's own phase in plain words, and a travelling accent
                // line. All of it reports real state, none of it fakes a
                // percentage.
                RunningTurnStrip(
                    phases = listOfNotNull(
                        activity?.phase?.takeIf { it.isNotBlank() },
                        "Working on it",
                    ),
                    detail = activity?.detail
                        ?: activity?.steps?.takeIf { it > 0 }?.let {
                            "$it step" + if (it == 1) "" else "s"
                        },
                )
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onInterrupt,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Interrupt this turn"
                        },
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Interrupt", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = { steerMode = true },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Steer this turn"
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Steer")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    commands: List<SlashCommand>,
    onSend: (String) -> Unit,
    onSlash: (String) -> Unit,
    onOpenCommands: () -> Unit,
    pendingAttachments: List<com.hermes.mobile.domain.model.ChatAttachment> = emptyList(),
    onAttach: (android.net.Uri) -> Unit = {},
    onRemoveAttachment: (com.hermes.mobile.domain.model.ChatAttachment) -> Unit = {},
    sending: Boolean = false,
    onSendDetached: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val voice = remember { com.hermes.mobile.core.voice.VoiceInputController(context) }
    val listening by voice.listening.collectAsState()
    val partial by voice.partial.collectAsState()

    DisposableEffect(Unit) {
        voice.onFinalResult = { spoken -> text = spoken }
        onDispose { voice.destroy() }
    }

    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

    // System picker; "*/*" so any app that can share a file can feed the chat.
    // The URI is copied into app cache by the caller before upload, because a
    // grant from another app can expire before a deferred upload runs.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onAttach) }

    // Typing "/" opens the command list, filtered as you keep typing — the same
    // affordance the desktop TUI has, which is where these commands live.
    val slashQuery = text.takeIf { it.startsWith("/") }
    val matches = remember(slashQuery, commands) {
        slashQuery?.let { q ->
            commands.filter { it.name.startsWith(q, ignoreCase = true) }.take(6)
        }.orEmpty()
    }
    LaunchedEffect(slashQuery != null) {
        if (slashQuery != null) onOpenCommands()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            if (matches.isNotEmpty()) {
                CommandSuggestions(matches) { picked ->
                    text = if (picked.name.endsWith(" ")) picked.name else picked.name + " "
                }
            }
            if (listening && partial.isNotBlank()) {
                Text(
                    partial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ComposerAttachmentStrip(
                attachments = pendingAttachments,
                onRemove = onRemoveAttachment,
            )
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Attach: any MIME type. The picker is the system one, so files
                // from Drive/Photos/Downloads all arrive through the same path.
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach a file",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    // Short enough to survive the attach + mic + send buttons
                    // eating the row's width in a wide geometric face.
                    placeholder = { Text("Message Hermes", maxLines = 1) },
                    maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Message input" },
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        if (micPermission.status.isGranted) {
                            if (listening) voice.stop() else voice.start()
                        } else {
                            micPermission.launchPermissionRequest()
                        }
                    },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = if (listening) "Stop dictation" else "Dictate a prompt"
                    },
                ) {
                    Icon(
                        if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                        contentDescription = null,
                        tint = if (listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val canSend = (text.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending
                // The send button *announces* readiness: it warms to clay,
                // lifts a touch, and springs back when the draft empties.
                // A button that looks identical whether or not it will do
                // anything is the most common dead-feeling UI there is.
                val sendScale by animateFloatAsState(
                    targetValue = if (canSend) 1f else 0.94f,
                    animationSpec = HermesMotion.Snappy,
                    label = "send-scale",
                )
                val sendColor by animateColorAsState(
                    targetValue = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    animationSpec = tween(240),
                    label = "send-bg",
                )
                val sendContent by animateColorAsState(
                    targetValue = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(240),
                    label = "send-fg",
                )
                // Long-press sends the task detached: it keeps running on the
                // PC after the phone locks or the app is swiped away, which is
                // the whole reason to start long work from a phone. Kept as a
                // long-press rather than a second button so the common case
                // stays a one-thumb tap.
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = sendScale
                            scaleY = sendScale
                        }
                        .combinedClickable(
                            enabled = canSend,
                            onClick = {
                                val payload = text
                                text = ""
                                if (payload.startsWith("/")) onSlash(payload) else onSend(payload)
                            },
                            onLongClick = {
                                val payload = text
                                if (payload.isNotBlank() && !payload.startsWith("/")) {
                                    text = ""
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSendDetached(payload)
                                }
                            },
                        )
                        .semantics {
                            contentDescription =
                                "Send to Hermes. Long-press to run it detached on your PC."
                        },
                    color = sendColor,
                    contentColor = sendContent,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // While attachments stage, the button reports it rather
                    // than looking dead — a multi-megabyte upload is seconds
                    // of silence otherwise, and silence reads as "it ignored me".
                    AnimatedContent(
                        targetState = sending,
                        label = "send-icon",
                    ) { isSending ->
                        if (isSending) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                Modifier.size(20.dp),
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandSuggestions(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        matches.forEach { command ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onPick(command) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Command ${command.name}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    command.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = HermesMono,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
