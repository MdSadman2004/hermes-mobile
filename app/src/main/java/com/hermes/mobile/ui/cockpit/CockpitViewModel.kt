package com.hermes.mobile.ui.cockpit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.notify.HermesNotifier
import com.hermes.mobile.data.repo.AttachmentRepository
import com.hermes.mobile.data.repo.CommandRepository
import com.hermes.mobile.data.repo.OutboxRepository
import com.hermes.mobile.data.repo.TransferRepository
import com.hermes.mobile.data.repo.firstDbl
import com.hermes.mobile.data.repo.firstStr
import com.hermes.mobile.data.repo.obj
import com.hermes.mobile.data.repo.objects
import com.hermes.mobile.data.repo.str
import com.hermes.mobile.data.repo.SessionRepository
import com.hermes.mobile.data.repo.TranscriptRepository
import com.hermes.mobile.domain.model.AgentActivity
import com.hermes.mobile.domain.model.ChatAttachment
import com.hermes.mobile.domain.model.SessionUsage
import com.hermes.mobile.domain.model.SlashCommand
import com.hermes.mobile.domain.model.TranscriptItem
import com.hermes.mobile.domain.model.TurnPhase
import com.hermes.mobile.service.TurnForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CockpitViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionRepository: SessionRepository,
    private val transcriptRepository: TranscriptRepository,
    private val outboxRepository: OutboxRepository,
    private val commandRepository: CommandRepository,
    private val attachmentRepository: AttachmentRepository,
    private val transferRepository: TransferRepository,
    private val notifier: HermesNotifier,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _items = MutableStateFlow<List<TranscriptItem>>(emptyList())
    val items: StateFlow<List<TranscriptItem>> = _items.asStateFlow()

    private val _turnPhase = MutableStateFlow(TurnPhase.IDLE)
    val turnPhase: StateFlow<TurnPhase> = _turnPhase.asStateFlow()

    /** The changing "what I'm doing" line under the transcript. */
    private val _activity = MutableStateFlow<AgentActivity?>(null)
    val activity: StateFlow<AgentActivity?> = _activity.asStateFlow()

    /** Tool calls, for the Activity screen. Never rendered in the chat. */
    private val _toolLog = MutableStateFlow<List<TranscriptItem.ToolCallItem>>(emptyList())
    val toolLog: StateFlow<List<TranscriptItem.ToolCallItem>> = _toolLog.asStateFlow()

    private val _activeTitle = MutableStateFlow("New session")
    val activeTitle: StateFlow<String> = _activeTitle.asStateFlow()

    private val _contextPercent = MutableStateFlow<Int?>(null)
    val contextPercent: StateFlow<Int?> = _contextPercent.asStateFlow()

    private val _model = MutableStateFlow<String?>(null)
    val model: StateFlow<String?> = _model.asStateFlow()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    private val _usage = MutableStateFlow<SessionUsage?>(null)
    val usage: StateFlow<SessionUsage?> = _usage.asStateFlow()

    /** Sending state for the composer: true while attachments are staging. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    val connState: StateFlow<ConnState> = connectionManager.state

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var engine: TranscriptRepository.Engine? = null
    private var liveSessionId: String? = null

    /** The stored id the open session came from — session.delete needs it, not the live handle. */
    private var storedSessionId: String? = null
    private var bindingJob: Job? = null
    private var reconnectJob: Job? = null

    val hasSession: Boolean get() = liveSessionId != null

    init {
        // Hold process importance while a turn runs; release immediately after.
        viewModelScope.launch {
            turnPhase.collect { phase ->
                when (phase) {
                    TurnPhase.RUNNING ->
                        TurnForegroundService.start(appContext, "Hermes turn running")
                    TurnPhase.IDLE -> TurnForegroundService.stop(appContext)
                }
            }
        }
        viewModelScope.launch {
            outboxRepository.notices.collect { _userMessage.emit(it) }
        }
        // Every reconnect builds a NEW HermesClient — a fresh socket with a
        // fresh event flow. The engine holds the client it was created with,
        // so without re-attaching here the cockpit stays on screen looking
        // connected while receiving nothing, which is worse than showing an
        // error. With no session open yet, adopt whatever the desktop has
        // live instead of opening an empty cockpit next to a running turn.
        viewModelScope.launch {
            connectionManager.clientFlow.collect { client ->
                if (client == null) return@collect
                val sid = liveSessionId
                if (sid == null) {
                    adoptMostRecent()
                } else if (engine?.isBoundTo(client) == false) {
                    // A reconnect may cross a gateway restart. The live handle is
                    // process-local, so resolve it through the stable stored key
                    // whenever one exists instead of blindly reusing a stale id.
                    val storedId = storedSessionId
                    if (storedId != null) {
                        reconnectJob?.cancel()
                        reconnectJob = viewModelScope.launch {
                            runCatching { sessionRepository.resumeSession(storedId) }
                                .onSuccess { resumed ->
                                    if (connectionManager.clientFlow.value === client &&
                                        storedSessionId == storedId
                                    ) {
                                        openLiveSession(resumed.liveId, _activeTitle.value, resumed.storedId)
                                    }
                                }
                                .onFailure {
                                    if (isActive) {
                                        _userMessage.emit("Couldn't reconnect this conversation: ${it.message}")
                                    }
                                }
                        }
                    } else {
                        openLiveSession(sid, _activeTitle.value, null)
                    }
                }
            }
        }
        viewModelScope.launch { loadAutonomy() }
    }

    // ------------------------------------------------------------- sessions

    /** Open a session by live handle (from create/resume) and attach the engine. */
    fun openLiveSession(sessionId: String, title: String? = null, storedId: String? = null) {
        val client = connectionManager.clientFlow.value
        if (client == null) {
            _userMessage.tryEmit("Not connected to your PC")
            return
        }
        bindingJob?.cancel()
        engine?.stop()
        liveSessionId = sessionId
        storedSessionId = storedId ?: storedSessionId
        _activeTitle.value = title ?: _activeTitle.value
        _items.value = emptyList()
        _toolLog.value = emptyList()
        _activity.value = null
        _usage.value = null
        bindingJob = viewModelScope.launch {
            // Keep the engine and every UI collector under the same cancellable
            // binding job. Reconnects replace this job; no old engine can keep
            // publishing rows into the newly attached cockpit.
            kotlinx.coroutines.coroutineScope {
                engine = transcriptRepository.attach(client, sessionId, this@coroutineScope)
                val attached = engine ?: return@coroutineScope
                // Start listening before activation. The gateway may emit the
                // live snapshot immediately; activating first left a narrow
                // gap where those frames were dropped by SharedFlow.
                val activationError = runCatching { client.sessionActivate(sessionId) }.exceptionOrNull()
                if (activationError != null) {
                    attached.stop()
                    engine = null
                    _userMessage.emit("Couldn't attach to live session: ${activationError.message}")
                    return@coroutineScope
                }
                if (liveSessionId != sessionId || connectionManager.clientFlow.value !== client) {
                    attached.stop()
                    engine = null
                    return@coroutineScope
                }
                launch { attached.items.collect { _items.value = it } }
                launch { attached.turnPhase.collect { _turnPhase.value = it } }
                launch { attached.activity.collect { _activity.value = it } }
                launch { attached.toolLog.collect { _toolLog.value = it } }
                launch { attached.contextPercent.collect { _contextPercent.value = it } }
                launch { attached.model.collect { if (it != null) _model.value = it } }
            }
        }
    }

    /** New chat. */
    fun createAndOpen() {
        viewModelScope.launch {
            try {
                val sid = sessionRepository.createSession()
                storedSessionId = null
                openLiveSession(sid, "New session")
            } catch (e: Exception) {
                _userMessage.emit("Couldn't create session: ${e.message}")
            }
        }
    }

    /** Resume a stored session (from the sessions list). */
    fun resumeAndOpen(storedId: String, title: String) {
        viewModelScope.launch {
            try {
                val resumed = sessionRepository.resumeSession(storedId)
                openLiveSession(resumed.liveId, title.ifBlank { "Session" }, storedId = resumed.storedId)
            } catch (e: Exception) {
                _userMessage.emit("Couldn't resume: ${e.message}")
            }
        }
    }

    /** Pull the authoritative transcript again — used by pull-to-refresh. */
    fun reloadHistory() {
        viewModelScope.launch {
            engine?.hydrateFromHistory()
                ?: _userMessage.emit("No session open")
        }
    }

    /**
     * Bind to whatever the desktop already has open, if anything.
     *
     * Opening the app mid-turn and being shown an empty cockpit beside a
     * running session is the wrong default — the session you want is almost
     * always the one already live.
     */
    private suspend fun adoptMostRecent() {
        val client = connectionManager.clientFlow.value ?: return
        val active = runCatching { client.sessionActiveList() }.getOrNull().obj() ?: return
        val rows = active.objects("sessions")
        val live = rows.mapNotNull { row ->
            val sid = row.firstStr("session_id", "id") ?: return@mapNotNull null
            sid to (row.firstDbl("last_active", "started_at") ?: 0.0)
        }.maxByOrNull { it.second } ?: return
        val row = rows.firstOrNull { it.firstStr("session_id", "id") == live.first } ?: return
        openLiveSession(live.first, row.str("title") ?: "Live session", row.firstStr("session_key", "stored_session_id"))
    }

    // ------------------------------------------------------------- prompting

    fun send(text: String) {
        val attachments = _pendingAttachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        val sid = liveSessionId
        if (sid == null) {
            viewModelScope.launch {
                try {
                    val newSid = sessionRepository.createSession()
                    openLiveSession(newSid, "New session")
                    doSend(newSid, text, attachments)
                } catch (e: Exception) {
                    _userMessage.emit("Couldn't start a session: ${e.message}")
                }
            }
            return
        }
        viewModelScope.launch { doSend(sid, text, attachments) }
    }

    /**
     * Send one message, with whatever the user attached to it.
     *
     * The attachments are staged BEFORE `prompt.submit`, and the submit waits
     * for them. That ordering is the whole fix for "the image doesn't go with
     * the text": the gateway drains `session["attached_images"]` into the turn
     * that submit starts, so a file staged afterwards lands on the NEXT turn —
     * which looks, from the phone, exactly like the file being ignored.
     *
     * The composer is cleared optimistically the moment send is pressed (the
     * message is already echoed into the transcript), but the pending list is
     * only cleared after staging, so a failed upload can still name the file.
     */
    private suspend fun doSend(
        sid: String,
        text: String,
        attachments: List<ChatAttachment>,
    ) {
        val client = connectionManager.clientFlow.value
        if (client == null || connectionManager.state.value !is ConnState.Connected) {
            // Offline with files is not a send we can honestly queue: the bytes
            // live behind a content:// grant that may be gone by reconnect.
            if (attachments.isNotEmpty()) {
                _userMessage.emit("Offline — connect to your PC to send files")
                return
            }
            outboxRepository.enqueue(sid, text)
            engine?.echoUser(text)
            _userMessage.emit("Offline — prompt queued, will send on reconnect")
            return
        }

        engine?.echoUser(text, attachments)
        val messageKey = engine?.lastUserKey()

        var payload = text
        if (attachments.isNotEmpty()) {
            _sending.value = true
            try {
                val staged = attachmentRepository.stageAll(client, sid, attachments) { updated ->
                    viewModelScope.launch {
                        messageKey?.let { engine?.updateUserAttachment(it, updated) }
                        _pendingAttachments.value = _pendingAttachments.value.map {
                            if (it.id == updated.id) updated else it
                        }
                    }
                }
                val failed = staged.filter { it.attachment.error != null }
                if (failed.isNotEmpty()) {
                    _userMessage.emit(
                        "Couldn't send ${failed.joinToString { it.attachment.name }}: " +
                            (failed.first().attachment.error ?: "unknown error"),
                    )
                }
                // Workspace-staged files are only reachable through their ref,
                // so the refs join the prompt. Images/PDFs need no suffix —
                // the server already queued them into this turn.
                val suffixes = staged.mapNotNull { it.promptSuffix.takeIf(String::isNotBlank) }
                if (suffixes.isNotEmpty()) {
                    payload = listOf(text.trim(), suffixes.joinToString("\n"))
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                }
                // Everything failed and there was no text: nothing to submit.
                if (payload.isBlank() && failed.size == attachments.size) {
                    _pendingAttachments.value = emptyList()
                    return
                }
                if (payload.isBlank()) {
                    payload = "[see the attached file(s)]"
                }
            } finally {
                _sending.value = false
                _pendingAttachments.value = emptyList()
            }
        }

        try {
            client.promptSubmit(sid, payload)
        } catch (e: Exception) {
            if (attachments.isEmpty()) outboxRepository.enqueue(sid, payload)
            _userMessage.emit("Send failed: ${e.message}")
        }
    }

    // ------------------------------------------------------- slash commands

    /** Cheap and cached; safe to call every time the composer sees a leading "/". */
    fun loadCommands() {
        if (_commands.value.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { commandRepository.catalog() }
                .onSuccess { _commands.value = it }
                .onFailure { _userMessage.emit("Couldn't load commands: ${it.message}") }
        }
    }

    /**
     * Run a slash command against the open session and render its pager output
     * inline. Some commands (/model, /compress) change server state, so the
     * transcript is left to the event stream to update itself.
     */
    fun runSlashCommand(command: String) {
        val sid = liveSessionId
        if (sid == null) {
            _userMessage.tryEmit("Open or start a session first")
            return
        }
        viewModelScope.launch {
            engine?.echoUser(command)
            runCatching { commandRepository.exec(sid, command) }
                .onSuccess { engine?.addCommandOutput(command, it) }
                .onFailure { _userMessage.emit("$command failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ live turn

    fun interrupt() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { connectionManager.clientFlow.value?.sessionInterrupt(sid) }
                .onFailure { _userMessage.emit("Interrupt failed: ${it.message}") }
        }
    }

    fun steer(text: String) {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { connectionManager.clientFlow.value?.sessionSteer(sid, text) }
                .onSuccess { _userMessage.emit("Steer queued") }
                .onFailure { _userMessage.emit("Steer failed: ${it.message}") }
        }
    }

    /**
     * [alsoRemember] maps to the server's `all` flag — "apply this answer to
     * every matching request", which is what the card's checkbox offers.
     */
    fun respondApproval(card: TranscriptItem.ApprovalCard, choice: String, alsoRemember: Boolean) {
        viewModelScope.launch {
            runCatching {
                connectionManager.clientFlow.value?.approvalRespond(
                    card.sessionId, choice, alsoRemember,
                )
            }
                .onSuccess {
                    engine?.markApprovalResolved(card.key, choice)
                    notifier.dismissForSession(card.sessionId)
                }
                .onFailure { _userMessage.emit("Approval response failed: ${it.message}") }
        }
    }

    // --------------------------------------------------------------- autonomy

    /**
     * How much the PC is allowed to do without asking.
     *
     * `manual` = every risky command waits for a tap; `smart` = the server's
     * own risk heuristic decides; `off` = nothing is gated. This mirrors the
     * desktop exactly (`approvals.mode`), so a change here is a change there —
     * which is the point: driving the PC from the phone means the phone has to
     * be able to say "stop asking me, just work".
     */
    private val _approvalMode = MutableStateFlow<String?>(null)
    val approvalMode: StateFlow<String?> = _approvalMode.asStateFlow()

    /** Session-scoped autonomy, independent of the global mode. */
    private val _sessionAutonomous = MutableStateFlow(false)
    val sessionAutonomous: StateFlow<Boolean> = _sessionAutonomous.asStateFlow()

    private suspend fun loadAutonomy() {
        val client = connectionManager.clientFlow.value ?: return
        runCatching { client.approvalModeGet() }
            .getOrNull().obj().str("value")
            ?.let { _approvalMode.value = it }
    }

    fun refreshAutonomy() {
        viewModelScope.launch { loadAutonomy() }
    }

    /** [mode] is one of manual / smart / off. Persists on the PC. */
    fun setApprovalMode(mode: String) {
        viewModelScope.launch {
            val client = connectionManager.clientFlow.value ?: run {
                _userMessage.emit("Not connected to your PC")
                return@launch
            }
            runCatching { client.approvalModeSet(mode) }
                .onSuccess {
                    _approvalMode.value = mode
                    _userMessage.emit(
                        when (mode) {
                            "off" -> "Full autonomy — your PC will not ask before acting"
                            "smart" -> "Smart approvals — only risky actions will ask"
                            else -> "Manual approvals — every risky action will ask"
                        },
                    )
                }
                .onFailure { _userMessage.emit("Couldn't change approvals: ${it.message}") }
        }
    }

    /** Toggle autonomy for THIS session only, leaving the PC's default alone. */
    fun setSessionAutonomous(enabled: Boolean) {
        val sid = liveSessionId ?: run {
            _userMessage.tryEmit("Open a session first")
            return
        }
        viewModelScope.launch {
            val client = connectionManager.clientFlow.value ?: return@launch
            runCatching { client.sessionYolo(sid, enabled) }
                .onSuccess {
                    _sessionAutonomous.value = enabled
                    _userMessage.emit(
                        if (enabled) "This session now runs unattended"
                        else "This session will ask before risky actions",
                    )
                }
                .onFailure { _userMessage.emit("Couldn't change this session: ${it.message}") }
        }
    }

    /**
     * Hand the PC a task and let go.
     *
     * A background prompt survives the phone locking, the app being swiped
     * away, and the Wi-Fi dropping — the desktop keeps working and the result
     * is waiting in the session. This is the difference between "remote
     * control" and "delegation", and it is the reason to run long work from a
     * phone at all.
     */
    fun sendDetached(text: String) {
        if (text.isBlank()) return
        val sid = liveSessionId ?: run {
            _userMessage.tryEmit("Open a session first")
            return
        }
        viewModelScope.launch {
            val client = connectionManager.clientFlow.value ?: run {
                _userMessage.emit("Not connected to your PC")
                return@launch
            }
            engine?.echoUser(text)
            runCatching { client.promptBackground(sid, text) }
                .onSuccess {
                    _userMessage.emit("Running on your PC — you can close the app")
                }
                .onFailure { _userMessage.emit("Couldn't start it: ${it.message}") }
        }
    }

    // -------------------------------------------------------- session admin

    fun rename(title: String) {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.rename(sid, title) }
                .onSuccess { _activeTitle.value = title }
                .onFailure { _userMessage.emit("Rename failed: ${it.message}") }
        }
    }

    fun compress() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            _userMessage.emit("Compressing history — this can take a minute")
            runCatching { sessionRepository.compress(sid) }
                .onSuccess {
                    _userMessage.emit("History compressed")
                    engine?.hydrateFromHistory()
                }
                .onFailure { _userMessage.emit("Compress failed: ${it.message}") }
        }
    }

    fun undo() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.undo(sid) }
                .onSuccess {
                    _userMessage.emit(if (it > 0) "Undid the last exchange" else "Nothing to undo")
                    engine?.hydrateFromHistory()
                }
                .onFailure { _userMessage.emit("Undo failed: ${it.message}") }
        }
    }

    fun branch() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.branch(sid) }
                .onSuccess { newSid ->
                    if (newSid == null) {
                        _userMessage.emit("Branch returned no session")
                    } else {
                        openLiveSession(newSid, "${_activeTitle.value} (branch)")
                        _userMessage.emit("Branched — you're now on the copy")
                    }
                }
                .onFailure { _userMessage.emit("Branch failed: ${it.message}") }
        }
    }

    fun refreshUsage() {
        val sid = liveSessionId ?: return
        viewModelScope.launch {
            runCatching { sessionRepository.usage(sid) }
                .onSuccess {
                    _usage.value = it
                    it.contextPercent?.let { p -> _contextPercent.value = p }
                }
                .onFailure { _userMessage.emit("Usage unavailable: ${it.message}") }
        }
    }

    override fun onCleared() {
        engine?.stop()
    }
    // ---------------------------------------------------------------- artifacts

    /**
     * Recompile an artifact after a failure.
     *
     * Kept explicit rather than automatic: a compile error is usually a real
     * error in the component, and silently retrying it would spin against the
     * PC for no benefit. The user asks, once.
     */
    fun recompileArtifact(artifact: com.hermes.mobile.domain.model.ArtifactState) {
        viewModelScope.launch {
            _artifactRequests.emit(artifact.copy(compiling = true, error = null))
        }
    }

    private val _artifactRequests =
        MutableSharedFlow<com.hermes.mobile.domain.model.ArtifactState>(extraBufferCapacity = 8)
    val artifactRequests: SharedFlow<com.hermes.mobile.domain.model.ArtifactState> =
        _artifactRequests.asSharedFlow()

    // -------------------------------------------------------------- attachments

    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** Queue a picked file for the next send. */
    fun attach(att: ChatAttachment) {
        _pendingAttachments.value = _pendingAttachments.value + att
    }

    /**
     * Resolve a picked content:// URI into a real attachment.
     *
     * The display name and size come from the content resolver, not from the
     * URI string: a Drive or Photos URI carries an opaque document id, so
     * guessing a filename from the path produces junk like "msf:1042".
     *
     * A persistable read grant is taken where the provider allows it, because
     * an ordinary grant can expire before a queued upload runs and the failure
     * surfaces much later as an unexplained SecurityException.
     */
    fun attachUri(uri: android.net.Uri) {
        val resolver = appContext.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        .takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
        }
        // A resolver that reports no MIME (common for file:// and some
        // providers) would otherwise route a PNG down the generic file path
        // and the model would never see it — fall back to the extension.
        val mime = resolver.getType(uri)
            ?.takeIf { it != "application/octet-stream" }
            ?: com.hermes.mobile.domain.model.mimeForName(name)
        attach(
            ChatAttachment(
                id = "att-" + System.nanoTime(),
                name = name,
                mimeType = mime,
                sizeBytes = size,
                localUri = uri.toString(),
            ),
        )
    }

    fun removeAttachment(att: ChatAttachment) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == att.id }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    /**
     * Make a PC-side file viewable on the phone.
     *
     * Files the agent produced exist only on the desktop, so an image card
     * pointing at `D:/out/chart.png` renders an empty box until the bytes are
     * here. This pulls them into app cache and rewrites the attachment with a
     * local URI, which is what turns "the agent says it made a chart" into
     * "the chart is on screen".
     *
     * Only images and video are fetched automatically (they are the ones that
     * are useless as a filename); documents stay a chip until the user asks,
     * because silently pulling a 40 MB PDF over a phone connection is rude.
     */
    fun materialize(att: ChatAttachment) {
        val remote = att.remotePath ?: return
        if (att.cachedUri != null || att.downloading) return
        viewModelScope.launch {
            engine?.replaceAttachment(att.copy(downloading = true))
            runCatching { transferRepository.cacheRemote(remote, att.name) }
                .onSuccess { local ->
                    engine?.replaceAttachment(
                        att.copy(
                            downloading = false,
                            cachedUri = local.uri,
                            sizeBytes = local.size,
                        ),
                    )
                }
                .onFailure {
                    engine?.replaceAttachment(
                        att.copy(
                            downloading = false,
                            error = it.message?.take(140) ?: "couldn't fetch this file",
                        ),
                    )
                }
        }
    }

    /** Open an attachment with whatever app the phone has for that MIME type. */
    fun openAttachment(att: ChatAttachment) {
        viewModelScope.launch {
            // A remote-only file has nothing to open yet; fetch first, then open.
            if (att.displayUri() == null && att.remotePath != null) {
                _userMessage.emit("Fetching ${att.name}…")
                runCatching { transferRepository.cacheRemote(att.remotePath, att.name) }
                    .onSuccess { local ->
                        val ready = att.copy(cachedUri = local.uri, sizeBytes = local.size)
                        engine?.replaceAttachment(ready)
                        _attachmentOpens.emit(ready)
                    }
                    .onFailure { _userMessage.emit("Couldn't open ${att.name}: ${it.message}") }
                return@launch
            }
            _attachmentOpens.emit(att)
        }
    }

    /** Save a remote attachment into the phone's Downloads. */
    fun saveAttachment(att: ChatAttachment) {
        viewModelScope.launch { _attachmentSaves.emit(att) }
    }

    /**
     * Save a remote attachment to the phone's Downloads folder.
     * Called when the user taps Save on an attachment card, or when a MEDIA:
     * marker triggers an auto-download.
     */
    fun saveAttachmentToPhone(att: ChatAttachment) {
        val remote = att.remotePath ?: return
        if (att.downloading || att.downloadedToPhone) return
        viewModelScope.launch {
            engine?.replaceAttachment(att.copy(downloading = true))
            runCatching { transferRepository.saveToPhone(remote) { _, _, _ -> } }
                .onSuccess { saved ->
                    engine?.replaceAttachment(
                        att.copy(downloading = false, error = null, downloadedToPhone = true),
                    )
                    _userMessage.emit("Saved ${saved.name} to Downloads")
                }
                .onFailure {
                    engine?.replaceAttachment(
                        att.copy(
                            downloading = false,
                            error = it.message?.take(140) ?: "couldn't save this file",
                        ),
                    )
                }
        }
    }

    private val _attachmentOpens =
        MutableSharedFlow<ChatAttachment>(extraBufferCapacity = 4)
    val attachmentOpens: SharedFlow<ChatAttachment> = _attachmentOpens.asSharedFlow()

    private val _attachmentSaves =
        MutableSharedFlow<ChatAttachment>(extraBufferCapacity = 4)
    val attachmentSaves: SharedFlow<ChatAttachment> = _attachmentSaves.asSharedFlow()
}
