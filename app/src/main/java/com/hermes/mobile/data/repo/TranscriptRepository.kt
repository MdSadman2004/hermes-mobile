package com.hermes.mobile.data.repo

import com.hermes.mobile.core.attach.AttachmentRefs
import com.hermes.mobile.core.transport.DeltaKind
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.HermesEvent
import com.hermes.mobile.domain.model.AgentActivity
import com.hermes.mobile.domain.model.ChatAttachment
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
import com.hermes.mobile.domain.model.activityPhraseFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles the live cockpit transcript from the event stream (spec §D.2).
 *
 * Jank rule: deltas are written into StringBuilders and published to the
 * StateFlow by a ~20fps flush ticker — recomposition never happens per token,
 * and the server already coalesces to ~30fps upstream (Phase 0 verified).
 *
 * One engine instance per ViewModel scope via [attach]; a fresh attach (new
 * session or post-reconnect reconcile) re-hydrates from session.history,
 * because the server is authoritative and local state is derived.
 *
 * **Tool calls are NOT transcript rows.** They stream into [activity] (a
 * single changing status line) and [toolLog] (the Activity screen). A phone
 * transcript that interleaves `terminal` invocations and their stdout between
 * two sentences of a reply is unreadable, and it buries the answer the user
 * unlocked their phone to read. The conversation shows conversation; the
 * machine detail lives one tap away and loses nothing.
 */
@Singleton
class TranscriptRepository @Inject constructor() {

    inner class Engine(
        private val client: HermesClient,
        private val sessionId: String,
        private val scope: CoroutineScope,
    ) {
        private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
        val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

        private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
        val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

        /** What the agent is doing, in words. Null when idle. */
        private val _activity = MutableStateFlow<AgentActivity?>(null)
        val activity: StateFlow<AgentActivity?> = _activity.asStateFlow()

        /** Every tool call this session, newest last. Rendered on the Activity screen. */
        private val _toolLog = MutableStateFlow<List<TranscriptItem.ToolCallItem>>(emptyList())
        val toolLog: StateFlow<List<TranscriptItem.ToolCallItem>> = _toolLog.asStateFlow()

        /** Latest context-window fill from usage, for the cockpit meter. */
        private val _contextPercent = MutableStateFlow<Int?>(null)
        val contextPercent: StateFlow<Int?> = _contextPercent.asStateFlow()

        /** Model the server says is answering, from session.info. */
        private val _model = MutableStateFlow<String?>(null)
        val model: StateFlow<String?> = _model.asStateFlow()

        private val lock = Mutex()
        private val rows = mutableListOf<TranscriptItem>()

        private val assistantBuf = StringBuilder()
        private val thinkingBuf = StringBuilder()
        private var streamingAssistantKey: String? = null
        private var liveThinkingKey: String? = null

        /** Tool calls in the CURRENT turn, for the step counter on the status line. */
        private var turnSteps = 0

        @Volatile
        private var dirty = false

        private var eventJob: Job? = null
        private var flushJob: Job? = null
        private var seq = 0L
        private fun nextKey(prefix: String) = "$prefix-${seq++}"

        // A session can be active when the phone attaches. Keep frames received
        // while history is being rebuilt; starting hydration in a second,
        // unrelated coroutine used to race the event collector and lose the
        // first deltas of a live desktop turn.
        private var hydrating = true
        private val pendingEvents = ArrayDeque<HermesEvent>()

        /**
         * Approval cards are keyed by an incrementing ordinal, not by a hash of
         * the command. Hashing meant a second, identical approval request in
         * the same session collided with the first — it landed on the already
         * answered card, which rendered "Answered: …" with no buttons and left
         * the turn blocked with no way to respond.
         */
        private var approvalOrdinal = 0
        private var openApprovalKey: String? = null

        fun start() {
            eventJob = scope.launch(Dispatchers.Default) {
                client.rpc.events.collect { event ->
                    if (event.sessionId.isNotEmpty() && event.sessionId != sessionId) return@collect
                    val queued = lock.withLock {
                        if (hydrating) {
                            pendingEvents.addLast(event)
                            true
                        } else false
                    }
                    if (!queued) handle(event)
                }
            }
            flushJob = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(50) // ~20fps publish — deltas are already coalesced server-side
                    if (dirty) {
                        dirty = false
                        publish()
                    }
                }
            }
            scope.launch {
                hydrateFromHistory()
                val queued = lock.withLock {
                    hydrating = false
                    buildList {
                        while (pendingEvents.isNotEmpty()) add(pendingEvents.removeFirst())
                    }
                }
                queued.forEach { handle(it) }
                replayPendingApproval()
            }
        }

        fun stop() {
            eventJob?.cancel()
            flushJob?.cancel()
        }

        /**
         * Whether this engine is still listening to [candidate].
         *
         * A reconnect builds a whole new client, and an engine bound to the
         * old one keeps running against a dead event flow — the cockpit looks
         * connected and receives nothing. The ViewModel asks this on every
         * client change so it can re-attach instead.
         */
        fun isBoundTo(candidate: HermesClient): Boolean = client === candidate

        private suspend fun publish() {
            lock.withLock { _items.value = rows.toList() }
        }

        private suspend fun upsert(item: TranscriptItem) {
            lock.withLock {
                val idx = rows.indexOfFirst { it.key == item.key }
                if (idx >= 0) rows[idx] = item else rows.add(item)
            }
            dirty = true
        }

        private fun logTool(item: TranscriptItem.ToolCallItem) {
            val current = _toolLog.value
            val idx = current.indexOfFirst { it.key == item.key }
            _toolLog.value = if (idx >= 0) {
                // Preserve the original timestamp across the start -> complete update.
                current.toMutableList().also { it[idx] = item.copy(atMillis = current[idx].atMillis) }
            } else {
                (current + item).takeLast(TOOL_LOG_CAP)
            }
        }

        private suspend fun handle(event: HermesEvent) {
            when (event) {
                is HermesEvent.SessionInfo -> {
                    event.model?.let { _model.value = it }
                    event.contextPercent?.let { _contextPercent.value = it }
                }
                is HermesEvent.MessageStart -> {
                    _turnPhase.value = TurnPhase.RUNNING
                    assistantBuf.clear()
                    thinkingBuf.clear()
                    streamingAssistantKey = null
                    liveThinkingKey = null
                    turnSteps = 0
                    _activity.value = AgentActivity("Thinking")
                }
                is HermesEvent.Delta -> when (event.kind) {
                    DeltaKind.MESSAGE -> {
                        assistantBuf.append(event.text)
                        val key = streamingAssistantKey ?: nextKey("asst").also {
                            streamingAssistantKey = it
                        }
                        // Mid-stream the text is still arriving, so file refs are
                        // not resolved yet — that happens once on completion.
                        upsert(
                            TranscriptItem.AssistantMessage(
                                key,
                                assistantBuf.toString(),
                                streaming = true,
                            ),
                        )
                        _activity.value = AgentActivity("Writing a reply", steps = turnSteps)
                    }
                    DeltaKind.THINKING, DeltaKind.REASONING -> {
                        if (event.text.isBlank()) return
                        thinkingBuf.append(event.text)
                        val key = liveThinkingKey ?: nextKey("think").also { liveThinkingKey = it }
                        upsert(TranscriptItem.ThinkingBlock(key, thinkingBuf.toString(), live = true))
                        _activity.value = AgentActivity("Thinking it through", steps = turnSteps)
                    }
                }
                // Tool traffic leaves the conversation entirely: a status phrase
                // for the chat, a full row for the Activity log.
                is HermesEvent.ToolStart -> {
                    turnSteps++
                    logTool(
                        TranscriptItem.ToolCallItem(
                            key = "tool-${event.toolId}",
                            toolId = event.toolId,
                            name = event.name,
                            context = event.context,
                            args = null, result = null, durationS = null,
                            running = true,
                        ),
                    )
                    _activity.value = AgentActivity(
                        phase = activityPhraseFor(event.name),
                        detail = event.context?.take(72)?.takeIf { it.isNotBlank() },
                        steps = turnSteps,
                    )
                }
                is HermesEvent.ToolComplete -> {
                    logTool(
                        TranscriptItem.ToolCallItem(
                            key = "tool-${event.toolId}",
                            toolId = event.toolId,
                            name = event.name,
                            context = null,
                            args = event.args?.let { prettyArgs(it) },
                            result = event.result?.let { prettyResult(it) },
                            durationS = event.durationS,
                            running = false,
                            approvalNote = event.result.obj().str("approval"),
                        ),
                    )
                    if (_turnPhase.value == TurnPhase.RUNNING) {
                        _activity.value = AgentActivity("Working through the results", steps = turnSteps)
                    }
                }
                is HermesEvent.ApprovalRequest -> {
                    _activity.value = AgentActivity("Waiting for your approval", running = false,
                        steps = turnSteps)
                    addApproval(event)
                }
                is HermesEvent.MessageComplete -> {
                    // The server sends the authoritative full text — replace the buffer.
                    val key = streamingAssistantKey ?: nextKey("asst")
                    val full = event.text.ifBlank { assistantBuf.toString() }
                    // Only now are file references complete enough to resolve:
                    // a path split across two deltas would otherwise be scanned
                    // half-written and produce a card pointing nowhere.
                    val files = AttachmentRefs.attachmentsIn(full, key)
                    upsert(
                        TranscriptItem.AssistantMessage(
                            key = key,
                            text = if (files.isEmpty()) full else AttachmentRefs.strip(full),
                            streaming = false,
                            usageContextPercent = event.usage?.contextPercent,
                            attachments = files,
                        ),
                    )
                    event.usage?.contextPercent?.let { _contextPercent.value = it }
                    event.usage?.model?.let { _model.value = it }
                    liveThinkingKey?.let { k ->
                        lock.withLock {
                            rows.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { idx ->
                                (rows[idx] as? TranscriptItem.ThinkingBlock)?.let {
                                    rows[idx] = it.copy(live = false)
                                }
                            }
                        }
                        dirty = true
                    }
                    streamingAssistantKey = null
                    liveThinkingKey = null
                    _turnPhase.value = TurnPhase.IDLE
                    _activity.value = null
                }
                is HermesEvent.SessionTitle -> { /* surfaced via the sessions list */ }
                // status.update is server plumbing ("compacting", "reconnecting").
                // It belongs on the status line, not as a permanent transcript row.
                is HermesEvent.StatusUpdate -> event.text?.takeIf { it.isNotBlank() }?.let {
                    _activity.value = AgentActivity(it.replaceFirstChar(Char::uppercase),
                        steps = turnSteps)
                }
                else -> { /* Ready/Global/Unknown — not transcript items */ }
            }
        }

        private suspend fun addApproval(event: HermesEvent.ApprovalRequest) {
            val key = "approval-${approvalOrdinal++}"
            openApprovalKey = key
            upsert(
                TranscriptItem.ApprovalCard(
                    key = key,
                    sessionId = sessionId,
                    command = event.command,
                    description = event.description,
                    choices = event.choices.ifEmpty { DEFAULT_CHOICES },
                    allowSession = event.allowSession,
                    allowPermanent = event.allowPermanent,
                ),
            )
        }

        /**
         * An approval raised while the app was backgrounded is not re-emitted on
         * reattach — the event already fired. Without this the turn looks hung.
         */
        private suspend fun replayPendingApproval() {
            val pending = runCatching { client.approvalPending(sessionId) }.getOrNull().obj()
                ?: return
            val command = pending.firstStr("command", "cmd") ?: return
            _turnPhase.value = TurnPhase.RUNNING
            addApproval(
                HermesEvent.ApprovalRequest(
                    sessionId = sessionId,
                    command = command,
                    description = pending.str("description"),
                    patternKey = pending.str("pattern_key"),
                    choices = pending.strings("choices"),
                    allowPermanent = pending.bool("allow_permanent"),
                    allowSession = pending.bool("allow_session"),
                ),
            )
        }

        /** Optimistic local echo of the user's own prompt. */
        suspend fun echoUser(text: String, attachments: List<ChatAttachment> = emptyList()) {
            upsert(TranscriptItem.UserMessage(nextKey("user"), text, attachments))
        }

        /**
         * Update an echoed user message in place as its attachments upload.
         *
         * Keyed by attachment id, not by position: a failed file must show its
         * own error next to its own name, and re-keying by index would move the
         * error onto whichever file happened to follow it.
         */
        suspend fun updateUserAttachment(messageKey: String, att: ChatAttachment) {
            lock.withLock {
                rows.indexOfFirst { it.key == messageKey }.takeIf { it >= 0 }?.let { idx ->
                    (rows[idx] as? TranscriptItem.UserMessage)?.let { msg ->
                        rows[idx] = msg.copy(
                            attachments = msg.attachments.map { if (it.id == att.id) att else it },
                        )
                    }
                }
            }
            dirty = true
        }

        /** The key of the most recent echoed user message, for attachment updates. */
        suspend fun lastUserKey(): String? = lock.withLock {
            rows.lastOrNull { it is TranscriptItem.UserMessage }?.key
        }

        /** Replace an attachment anywhere in the transcript (download completions). */
        suspend fun replaceAttachment(att: ChatAttachment) {
            lock.withLock {
                for (i in rows.indices) {
                    when (val row = rows[i]) {
                        is TranscriptItem.UserMessage ->
                            if (row.attachments.any { it.id == att.id }) {
                                rows[i] = row.copy(
                                    attachments = row.attachments.map {
                                        if (it.id == att.id) att else it
                                    },
                                )
                            }
                        is TranscriptItem.AssistantMessage ->
                            if (row.attachments.any { it.id == att.id }) {
                                rows[i] = row.copy(
                                    attachments = row.attachments.map {
                                        if (it.id == att.id) att else it
                                    },
                                )
                            }
                        is TranscriptItem.AttachmentGroup ->
                            if (row.attachments.any { it.id == att.id }) {
                                rows[i] = row.copy(
                                    attachments = row.attachments.map {
                                        if (it.id == att.id) att else it
                                    },
                                )
                            }
                        else -> Unit
                    }
                }
            }
            dirty = true
            publish()
        }

        /** A slash command's pager output, rendered inline in the transcript. */
        suspend fun addCommandOutput(command: String, output: String) {
            upsert(TranscriptItem.CommandOutput(nextKey("cmd"), command, output))
        }

        suspend fun markApprovalResolved(cardKey: String, choice: String) {
            lock.withLock {
                rows.indexOfFirst { it.key == cardKey }.takeIf { it >= 0 }?.let { idx ->
                    (rows[idx] as? TranscriptItem.ApprovalCard)?.let {
                        rows[idx] = it.copy(resolved = choice)
                    }
                }
            }
            if (openApprovalKey == cardKey) openApprovalKey = null
            dirty = true
        }

        /**
         * Cold-open / post-reconnect reconcile from the authoritative server.
         *
         * This is what makes history visible. `session.history` answers
         * `{"messages":[{"role":…,"text":…}]}` — note **text**, not `content`.
         * The previous reader looked for `content` first and fell back to a
         * `content` parts array, so EVERY row decoded to the empty string and
         * was then dropped by the blank filter: opening any existing chat, from
         * the phone or started on the PC, showed nothing at all. It read as
         * "history isn't synced"; the rows were arriving and being discarded.
         *
         * Empty is not the same as absent, either: a session whose history call
         * fails must keep whatever is on screen, while one that genuinely has
         * no messages must clear — hence the null/empty distinction below.
         */
        suspend fun hydrateFromHistory() {
            val result = runCatching { client.sessionHistory(sessionId) }.getOrNull() ?: return
            val messages = result.obj().objects("messages")
            val rebuilt = mutableListOf<TranscriptItem>()
            var n = 0L
            for (o in messages) {
                // display_kind="hidden" rows are model-facing scaffolding the
                // server already filters; skill_invocation rows are real user
                // turns and must stay.
                when (o.str("role")) {
                    "user" -> {
                        val key = "h-user-${n++}"
                        val raw = o.messageText()
                        val files = AttachmentRefs.attachmentsIn(raw, key)
                        val text = if (files.isEmpty()) raw else AttachmentRefs.strip(raw)
                        if (text.isNotBlank() || files.isNotEmpty()) {
                            rebuilt.add(TranscriptItem.UserMessage(key, text, files))
                        }
                    }
                    "assistant" -> {
                        val key = "h-asst-${n++}"
                        val raw = o.messageText()
                        val files = AttachmentRefs.attachmentsIn(raw, key)
                        val text = if (files.isEmpty()) raw else AttachmentRefs.strip(raw)
                        if (text.isNotBlank() || files.isNotEmpty()) {
                            rebuilt.add(TranscriptItem.AssistantMessage(key, text, attachments = files))
                        }
                    }
                    // Historical tool rows go to the Activity log, never the chat.
                    "tool" -> logTool(
                        TranscriptItem.ToolCallItem(
                            key = "h-tool-${n++}",
                            toolId = "",
                            name = o.firstStr("name", "tool_name") ?: "tool",
                            context = o.str("context"),
                            args = o.objAt("args")?.let { prettyArgs(it) },
                            result = o.messageText().takeIf { it.isNotBlank() },
                            durationS = null,
                            running = false,
                            atMillis = (o.dbl("timestamp") ?: 0.0).let {
                                if (it > 0) (it * 1000).toLong() else System.currentTimeMillis()
                            },
                        ),
                    )
                }
            }
            // A failed/absent fetch already returned above. An EMPTY answer is
            // authoritative: a genuinely empty session must not keep showing a
            // previous session's rows.
            lock.withLock {
                rows.clear()
                rows.addAll(rebuilt)
            }
            dirty = true
            publish()
        }
    }

    fun attach(client: HermesClient, sessionId: String, scope: CoroutineScope): Engine =
        Engine(client, sessionId, scope).also { it.start() }

    private companion object {
        val DEFAULT_CHOICES = listOf("once", "session", "deny")

        /** Activity log ceiling — a long session must not grow without bound. */
        const val TOOL_LOG_CAP = 400
    }
}

/**
 * Text of one history message.
 *
 * The gateway's `_history_to_messages` projection emits `{"role","text"}` and
 * nothing else for ordinary turns, so **`text` is checked first** — reading
 * `content` first is what made every history row render blank. The `content`
 * fallbacks below are for raw/legacy payloads (a plain string, or the
 * provider's multi-part blocks) so an older server still renders.
 */
internal fun kotlinx.serialization.json.JsonObject?.messageText(): String {
    this.str("text")?.let { return it }
    this.str("content")?.let { return it }
    val parts = this.arrAt("content") ?: return ""
    return parts.mapNotNull { part ->
        part.obj()?.let {
            it.str("text")
                ?: it.str("content")
                ?: it.str("type")?.let { t -> "[$t]" }
        }
    }.joinToString("\n")
}

/** Tool args as compact `key=value` text — the raw JSON object is unreadable on a phone. */
private fun prettyArgs(args: kotlinx.serialization.json.JsonObject): String =
    args.entries.joinToString("  ") { (k, v) ->
        "$k=" + v.toString().removeSurrounding("\"").take(120)
    }

private fun prettyResult(result: kotlinx.serialization.json.JsonElement): String {
    val o = result.obj() ?: return result.toString().removeSurrounding("\"")
    return o.firstStr("output", "text", "content", "result", "stdout") ?: o.toString()
}
