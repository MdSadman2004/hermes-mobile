package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.data.local.OutboxDao
import com.hermes.mobile.data.local.OutboxEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline outbox (Phase 3): a prompt composed with no connection is queued in
 * Room and flushed, in order, exactly once, on the next Connected transition.
 * Exactly-once = the row is deleted only after prompt.submit returns a result.
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val outboxDao: OutboxDao,
    private val connectionManager: ConnectionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    val pendingCount: Flow<Int> = outboxDao.count()

    /** Emits a user-visible note after a flush attempt. */
    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: kotlinx.coroutines.flow.SharedFlow<String> = _notices

    suspend fun enqueue(sessionId: String, text: String) {
        outboxDao.enqueue(OutboxEntity(sessionId = sessionId, text = text))
    }

    /** Idempotent — start watching for reconnects and flush on each. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            connectionManager.state.collect { state ->
                if (state is ConnState.Connected) flush()
            }
        }
    }

    suspend fun flush() {
        val pending = outboxDao.pending()
        if (pending.isEmpty()) return
        val client = connectionManager.clientFlow.value ?: return
        var sent = 0
        var retargeted = 0
        for (item in pending) {
            val firstTry = runCatching {
                val sid = item.sessionId.ifBlank { createSession(client) }
                client.promptSubmit(sid, item.text)
            }
            if (firstTry.isSuccess) {
                outboxDao.delete(item.rowId) // exactly-once: delete only after success
                sent++
                continue
            }
            val problem = firstTry.exceptionOrNull()
            val sessionGone = problem?.message.orEmpty().let { m ->
                m.contains("4001") || m.contains("session not found", ignoreCase = true)
            }
            if (!sessionGone) {
                _notices.tryEmit("Outbox flush paused: ${problem?.message}")
                return // transient — keep for the next reconnect, keep order
            }
            // The target session does not exist on the system we are talking
            // to (it lived on the other paired machine, or was replaced).
            // Deliver to a fresh session HERE instead of erroring on every
            // reconnect forever — the user typed the prompt and expects it
            // sent somewhere reachable.
            val retry = runCatching {
                val sid = createSession(client)
                client.promptSubmit(sid, item.text)
            }
            if (retry.isSuccess) {
                outboxDao.delete(item.rowId)
                sent++
                retargeted++
            } else {
                _notices.tryEmit("Outbox flush paused: ${retry.exceptionOrNull()?.message}")
                return
            }
        }
        if (sent > 0) _notices.tryEmit("Sent $sent queued prompt${if (sent > 1) "s" else ""}")
        if (retargeted > 0) {
            _notices.tryEmit(
                "$retargeted queued prompt${if (retargeted > 1) "s" else ""} delivered to a new " +
                    "session on ${connectionManager.currentProfile?.label ?: "this system"}",
            )
        }
    }

    private suspend fun createSession(client: com.hermes.mobile.core.transport.HermesClient): String =
        client.sessionCreate()?.jsonObjectString("session_id")
            ?: error("session.create returned no id during outbox flush")
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectString(key: String): String? =
    runCatching {
        this@jsonObjectString.jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()
