package com.hermes.mobile.data.repo

import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.rpcParamsOf
import com.hermes.mobile.data.local.SessionDao
import com.hermes.mobile.data.local.SessionEntity
import com.hermes.mobile.domain.model.SessionSummary
import com.hermes.mobile.domain.model.SessionUsage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Session lifecycle against the live server, with Room as a read-through cache.
 * Cache is derived state — the server's state.db is authoritative (spec §C.6),
 * which is why every mutation here goes to the server first and the cache is
 * only refreshed from a subsequent list.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sessionDao: SessionDao,
) {
    /** Last-known list, available before the socket is up so the list opens instantly. */
    val cachedSessions = sessionDao.observeAll().map { rows ->
        rows.map {
            SessionSummary(it.id, it.title, it.preview, it.startedAt, it.messageCount, it.source)
        }
    }

    private suspend fun client(): HermesClient =
        connectionManager.clientFlow.first { it != null }!!

    data class ResumedSession(val liveId: String, val storedId: String)

    /** session.list → typed summaries; refreshes the Room cache. */
    suspend fun listSessions(): List<SessionSummary> {
        val result = client().sessionList() ?: return emptyList()
        val parsed = result.obj().objects("sessions").mapNotNull { o ->
            SessionSummary(
                id = o.str("id") ?: return@mapNotNull null,
                title = o.str("title").orEmpty(),
                preview = o.str("preview").orEmpty(),
                startedAt = o.dbl("started_at") ?: 0.0,
                messageCount = o.int("message_count") ?: 0,
                source = o.str("source").orEmpty(),
            )
        }
        sessionDao.upsertAll(parsed.map {
            SessionEntity(it.id, it.title, it.preview, it.startedAt, it.messageCount, it.source)
        })
        return parsed
    }

    /** session.create → the LIVE handle (not the stored id — Phase 0 finding). */
    suspend fun createSession(title: String = ""): String {
        val result = client().sessionCreate(title)
        return result.obj().str("session_id")
            ?: error("session.create returned no session_id")
    }

    /** session.resume (stored id) → live handle. Server returns the new handle. */
    suspend fun resumeSession(storedId: String): ResumedSession {
        val result = client().rpc.call(
            "session.resume",
            rpcParamsOf(
                "session_id" to storedId,
                "omit_messages" to true, // we hydrate via session.history ourselves
            ),
        )
        val o = result.obj()
        val liveId = o.str("session_id")
            ?: o.objAt("info").str("session_id")
            ?: error("session.resume returned no live session_id for $storedId")
        return ResumedSession(
            liveId = liveId,
            storedId = o.str("session_key") ?: o.str("resumed") ?: storedId,
        )
    }

    // ------------------------------------------------------------ mutations

    /** Rename. The server persists to state.db, so the desktop sees it too. */
    suspend fun rename(sessionId: String, title: String) {
        client().rpc.call("session.title", rpcParamsOf("session_id" to sessionId, "title" to title))
    }

    /**
     * Delete a STORED session. The server refuses to delete one that is live in
     * the gateway, so the caller must not pass the currently-open handle.
     */
    suspend fun delete(storedId: String) {
        client().rpc.call("session.delete", rpcParamsOf("session_id" to storedId))
    }

    /** Fork the open session; returns the new live handle. */
    suspend fun branch(sessionId: String): String? =
        client().rpc.call("session.branch", rpcParamsOf("session_id" to sessionId))
            .obj().str("session_id")

    /** Summarise history to reclaim context. Long-running — allow a wide timeout. */
    suspend fun compress(sessionId: String, focusTopic: String = "") {
        client().rpc.call(
            "session.compress",
            rpcParamsOf("session_id" to sessionId, "focus_topic" to focusTopic.ifBlank { null }),
            timeout = 180.seconds,
        )
    }

    /** Drop the last exchange. Refused while a turn is running — surface that. */
    suspend fun undo(sessionId: String): Int =
        client().rpc.call("session.undo", rpcParamsOf("session_id" to sessionId))
            .obj().int("removed") ?: 0

    suspend fun usage(sessionId: String): SessionUsage {
        val o = client().rpc.call("session.usage", rpcParamsOf("session_id" to sessionId)).obj()
        return SessionUsage(
            model = o.str("model"),
            calls = o.int("calls") ?: 0,
            input = o.long("input") ?: 0,
            output = o.long("output") ?: 0,
            total = o.long("total") ?: 0,
            contextPercent = o.int("context_percent"),
            contextUsed = o.long("context_used"),
            contextMax = o.long("context_max"),
            creditLines = o.strings("credits_lines"),
        )
    }
}
