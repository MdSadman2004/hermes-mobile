package com.hermes.mobile.core.connection

import com.hermes.mobile.core.net.DiscoveredPc
import com.hermes.mobile.core.net.LanDiscovery
import com.hermes.mobile.core.net.NetworkMonitor
import com.hermes.mobile.core.net.PrivateHosts
import com.hermes.mobile.core.transport.ChannelState
import com.hermes.mobile.core.transport.HermesClient
import com.hermes.mobile.core.transport.HermesEvent
import com.hermes.mobile.core.transport.ReconnectPolicy
import com.hermes.mobile.core.transport.ServerStatus
import com.hermes.mobile.core.vault.SecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI banner renders. */
sealed interface ConnState {
    data object NoProfile : ConnState
    data object Probing : ConnState
    data class Connecting(val profile: ConnectionProfile) : ConnState
    data class Connected(val profile: ConnectionProfile, val status: ServerStatus?) : ConnState
    data class Reconnecting(val profile: ConnectionProfile, val attempt: Int) : ConnState
    data class Failed(val profile: ConnectionProfile?, val message: String) : ConnState
}

/**
 * Owns the active [HermesClient], profile selection, reachability racing and
 * the reconnect loop.
 *
 * Auth mode is DISCOVERED, not configured: `GET /api/status` is a public path,
 * so we read `auth_required` off it before choosing a [CredentialStrategy].
 *
 * Three robustness properties this class is responsible for, all of which were
 * missing and all of which show up as "the app just says reconnecting":
 *
 *  1. **DHCP drift self-heal.** The PC's LAN address is a lease and it moves.
 *     When the saved address stops answering, the /24 is swept and the profile
 *     is re-bound to the Hermes that answers — automatically when exactly one
 *     does, and surfaced as a choice when several do.
 *  2. **Link-aware retry.** A Wi-Fi drop used to leave the socket in timed
 *     backoff; now a link transition wakes the loop immediately and resets it.
 *  3. **Liveness.** A phone that sleeps through a TCP half-open sees a socket
 *     that is "connected" and silent. A heartbeat `ping` proves otherwise.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val vault: SecureVault,
    private val clientFactory: HermesClientFactory,
    private val networkMonitor: NetworkMonitor,
    private val lanDiscovery: LanDiscovery,
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnState>(ConnState.NoProfile)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    var client: HermesClient? = null
        private set

    private val _clientFlow = MutableStateFlow<HermesClient?>(null)
    val clientFlow: StateFlow<HermesClient?> = _clientFlow.asStateFlow()

    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Disconnected)
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()

    private val _globalEvents = MutableSharedFlow<HermesEvent>(extraBufferCapacity = 256)
    val globalEvents: SharedFlow<HermesEvent> = _globalEvents.asSharedFlow()

    /** Non-fatal notices worth showing once (rebinds, ambiguous discovery). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Result of the last LAN sweep, for the pairing screen's picker. */
    private val _discovered = MutableStateFlow<List<DiscoveredPc>>(emptyList())
    val discovered: StateFlow<List<DiscoveredPc>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Every paired PC, for the profile switcher. Secrets stay in the vault. */
    val profiles: StateFlow<List<ConnectionProfile>> = vault.profiles

    /** The profile currently bound, or null when disconnected. */
    val currentProfile: ConnectionProfile? get() = activeProfile

    private var activeProfile: ConnectionProfile? = null
    private var activeConnection: HermesConnection? = null
    private var activeStrategy: CredentialStrategy? = null
    private var watchJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val backoff = ReconnectPolicy()

    /** Serialises connect attempts so a manual retry cannot race the loop. */
    private val connectLock = Mutex()

    /** Set while a reconnect loop is driving; keeps connectTo from killing it. */
    @Volatile
    private var reconnecting = false

    init {
        networkMonitor.start()
        managerScope.launch {
            networkMonitor.changes.collect { onLinkChanged() }
        }
    }

    // ------------------------------------------------------------------ pair

    /** Entry point from the UI layer: pair → save → connect. */
    suspend fun pairAndConnect(payload: QrPairingPayload) {
        if (!payload.isUsable()) {
            _state.value = ConnState.Failed(null, "That pairing code is missing its credential")
            return
        }
        val profile = payload.toProfile()
        PrivateHosts.rejectionReason(profile.host, profile.secure)?.let {
            _state.value = ConnState.Failed(null, it)
            return
        }
        vault.saveProfile(profile, payload.vaultSecret())
        connectTo(profile)
    }

    /**
     * Manual entry (same as QR, minus the camera). [secret] is either a bare
     * dashboard session token (loopback) or `user:password` (gated) — the
     * server tells us which applies, so the user does not have to.
     */
    suspend fun addManualProfile(
        label: String,
        host: String,
        port: Int,
        secret: String,
        secure: Boolean = false,
    ) {
        PrivateHosts.rejectionReason(host, secure)?.let {
            _state.value = ConnState.Failed(null, it)
            return
        }
        val profile = ConnectionProfile(
            id = profileIdFor(host, port),
            label = label.ifBlank { host },
            host = host,
            port = port,
            auth = if (splitBasicSecret(secret) != null) AuthKind.GATED else AuthKind.TOKEN,
            secure = secure,
            isDefault = true,
        )
        vault.saveProfile(profile, secret)
        connectTo(profile)
    }

    // -------------------------------------------------------------- discovery

    /**
     * Sweep the Wi-Fi for dashboards and publish the result. Used by the
     * pairing screen's "Find my PC" and by the self-heal path.
     */
    suspend fun scanLan(port: Int = 9119): List<DiscoveredPc> {
        _scanning.value = true
        return try {
            lanDiscovery.sweep(port).also { _discovered.value = it }
        } finally {
            _scanning.value = false
        }
    }

    /**
     * Attach to a PC found by the sweep, reusing a stored credential when this
     * host was paired before. Returns false when a credential is still needed.
     */
    suspend fun connectToDiscovered(pc: DiscoveredPc): Boolean {
        val id = profileIdFor(pc.host, pc.port)
        val existing = vault.profiles.value.firstOrNull { it.id == id }
        if (existing != null && vault.secretFor(id) != null) {
            connectTo(existing)
            return true
        }
        // A profile paired to a different address, whose credential still applies.
        val reusable = vault.profiles.value.firstOrNull { vault.secretFor(it.id) != null }
        if (reusable != null) {
            rebindProfile(reusable, pc.host, pc.port)
            return true
        }
        return false
    }

    /**
     * Move an existing profile (and its secret) to a new address, then connect.
     * The profile id is derived from host:port, so the secret is re-keyed here
     * — dropping that step is how a rebind silently loses the credential.
     */
    private suspend fun rebindProfile(profile: ConnectionProfile, host: String, port: Int) {
        val secret = vault.secretFor(profile.id) ?: run {
            _state.value = ConnState.Failed(profile, "credential missing for ${profile.label}")
            return
        }
        val moved = profile.copy(id = profileIdFor(host, port), host = host, port = port)
        vault.saveProfile(moved, secret)
        if (moved.id != profile.id) vault.removeProfile(profile.id)
        _notices.tryEmit("${moved.label} moved to $host — reconnected")
        connectTo(moved)
    }

    /**
     * The saved address is dead. Sweep, and re-bind when the answer is
     * unambiguous. Returns true when a rebind happened.
     */
    private suspend fun rediscoverAndRebind(profile: ConnectionProfile): Boolean {
        if (!networkMonitor.onLocalNetwork) return false
        // Never steal another paired system's address: a sweep that finds
        // foundry's dashboard on the LAN must not rebind the PC profile onto it.
        val found = scanLan(profile.port).filter { pc ->
            vault.profiles.value.none { other ->
                other.id != profile.id && other.host == pc.host && other.port == pc.port
            }
        }
        return when {
            found.isEmpty() -> false
            found.size == 1 -> {
                rebindProfile(profile, found.first().host, found.first().port)
                true
            }
            else -> {
                _notices.tryEmit(
                    "Found ${found.size} Hermes PCs on this Wi-Fi — pick one in Connect.",
                )
                false
            }
        }
    }

    // --------------------------------------------------------------- connect

    suspend fun autoConnect() {
        val profiles = vault.profiles.value
        if (profiles.isEmpty()) {
            _state.value = ConnState.NoProfile
            return
        }
        _state.value = ConnState.Probing
        // The most recently used system wins when several are up.
        val winner = raceProfiles(profiles.sortedByDescending { it.lastSeenAt ?: 0L })
        if (winner != null) {
            connectTo(winner)
            return
        }
        // Nothing at the saved addresses — the lease probably moved.
        val healed = rediscoverAndRebind(profiles.first())
        if (!healed) {
            _state.value = ConnState.Failed(
                profiles.firstOrNull(),
                if (networkMonitor.onLocalNetwork) {
                    "None of your systems answered — " +
                        profiles.joinToString(", ") { it.label } +
                        ". Is a dashboard running?"
                } else {
                    "Can't reach " + profiles.joinToString(", ") { it.label } +
                        " from this network."
                },
            )
        }
    }

    /**
     * Live resource view of every paired system — what the Systems cards
     * render. Two layers per system:
     *
     *  1. `GET /api/status` (public): reachability, version, gateway state and
     *     the memory/disk block — enough for a complete card, no credentials.
     *  2. `GET /api/system/stats` (authenticated): the real detail — CPU %,
     *     memory %, disk %, uptime. Rides one cached auxiliary connection per
     *     profile so the gated cookie session is reused instead of re-logging
     *     in on every refresh.
     */
    data class SystemView(
        val id: String,
        val label: String,
        val address: String,
        val online: Boolean,
        val isCurrent: Boolean = false,
        val version: String? = null,
        val gatewayState: String? = null,
        val overall: String? = null,
        val activeSessions: Int = 0,
        val cpuPercent: Int? = null,
        val memPercent: Int? = null,
        val diskPercent: Int? = null,
        val uptimeSeconds: Long? = null,
    )

    private val auxConnections = mutableMapOf<String, HermesConnection>()
    private val auxLock = Mutex()

    suspend fun systemViews(): List<SystemView> = coroutineScope {
        val currentId = activeProfile?.id
        vault.profiles.value
            .map { profile -> async { viewFor(profile, profile.id == currentId) } }
            .awaitAll()
    }

    private suspend fun viewFor(profile: ConnectionProfile, isCurrent: Boolean): SystemView {
        val status = runCatching {
            clientFactory.newProbeClient(managerScope).rest.getJson(profile.httpBase, "/api/status")
        }.getOrNull()?.let { it as? JsonObject }
        if (status == null) {
            return SystemView(
                id = profile.id, label = profile.label, address = profile.displayAddress,
                online = false, isCurrent = isCurrent,
            )
        }
        val detail = runCatching { fetchSystemStats(profile) }.getOrNull()
        val mem = status.objAt("memory")
        val disk = status.objAt("disk")
        val dMem = detail.objAt("memory")
        val dDisk = detail.objAt("disk")
        return SystemView(
            id = profile.id,
            label = profile.label,
            address = profile.displayAddress,
            online = true,
            isCurrent = isCurrent,
            version = status.stringAt("version"),
            gatewayState = status.stringAt("gateway_state"),
            overall = status.stringAt("overall"),
            activeSessions = status.intAt("active_sessions") ?: 0,
            cpuPercent = detail.firstDoubleAt("cpu_percent", "cpu")?.roundToInt(),
            memPercent = dMem.doubleAt("percent")?.roundToInt()
                ?: usedPercent(mem.doubleAt("system_total_mb"), mem.doubleAt("system_available_mb")),
            diskPercent = dDisk.doubleAt("percent")?.roundToInt()
                ?: disk?.doubleAt("used_percent")?.roundToInt(),
            uptimeSeconds = detail.doubleAt("uptime_seconds")?.toLong(),
        )
    }

    private fun usedPercent(total: Double?, available: Double?): Int? {
        if (total == null || total <= 0.0 || available == null) return null
        return (((total - available) / total) * 100).roundToInt()
    }

    /**
     * Authenticated `/api/system/stats` for one profile on a cached auxiliary
     * connection. On failure the cache entry is dropped so the next call
     * re-logs-in from scratch (stale-cookie self-heal).
     */
    private suspend fun fetchSystemStats(profile: ConnectionProfile): JsonObject {
        val secret = vault.secretFor(profile.id) ?: error("no credential for ${profile.label}")
        val conn = auxLock.withLock {
            auxConnections.getOrPut(profile.id) { clientFactory.newConnection(managerScope) }
        }
        try {
            val creds = splitBasicSecret(secret)
            val strategy = if (creds == null) {
                CredentialStrategy.Token(secret)
            } else {
                CredentialStrategy.Gated(
                    httpBase = profile.httpBase,
                    username = creds.first,
                    password = creds.second,
                    rest = conn.client.rest,
                    cookies = conn.cookies,
                    host = profile.host,
                )
            }
            strategy.ensureAuthenticated()
            return withTimeoutOrNull(5_000) {
                conn.client.rest.getJson(profile.httpBase, "/api/system/stats")
            } as? JsonObject ?: error("stats timed out")
        } catch (e: Exception) {
            auxLock.withLock { auxConnections.remove(profile.id) }
            throw e
        }
    }

    private fun JsonObject?.objAt(key: String): JsonObject? = (this?.get(key)) as? JsonObject

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.doubleAt(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject?.intAt(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.firstDoubleAt(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { doubleAt(it) }

    /** First profile whose /api/status answers within the probe timeout wins. */
    suspend fun raceProfiles(profiles: List<ConnectionProfile>): ConnectionProfile? =
        coroutineScope {
            profiles
                .map { profile -> async { profile to probeProfile(profile) } }
                .awaitAll()
                .firstOrNull { it.second }
                ?.first
        }

    private suspend fun probeProfile(profile: ConnectionProfile): Boolean =
        clientFactory.newProbeClient(managerScope).rest.probe(profile.httpBase)

    suspend fun connectTo(profile: ConnectionProfile) = connectLock.withLock {
        connectLocked(profile)
    }

    /**
     * Record a connect failure AND make sure something will try again.
     *
     * This is the hole that survived on-device testing: a Wi-Fi off/on cycle
     * fires a link event, the immediate connect attempt loses the race with
     * DHCP, `connectTo` sets Failed — and then nothing ever retried, because
     * the retry loop only ran off ChannelState.Degraded and the socket had
     * never opened. The app sat on "Couldn't connect" next to a discovery card
     * listing the very PC it could not reach. Every failure path now re-arms
     * the loop, so "stuck" is not reachable while a profile is bound.
     */
    private fun failAndRetry(profile: ConnectionProfile?, message: String) {
        _state.value = ConnState.Failed(profile, message)
        if (profile != null) reconnectLoop(profile)
    }

    private suspend fun connectLocked(profile: ConnectionProfile) {
        val secret = vault.secretFor(profile.id)
        if (secret == null) {
            // A missing credential is NOT retryable — re-pairing is the fix.
            _state.value = ConnState.Failed(profile, "credential missing for ${profile.label}")
            return
        }
        // Only a caller from OUTSIDE the reconnect loop may cancel it. The old
        // code cancelled unconditionally, so the loop's own connectTo killed
        // the coroutine it was running in, mid-connect.
        if (!reconnecting) reconnectJob?.cancel()
        teardown()
        _state.value = ConnState.Connecting(profile)

        val connection = clientFactory.newConnection(managerScope)
        val newClient = connection.client
        activeConnection = connection
        client = newClient
        _clientFlow.value = newClient
        activeProfile = profile

        // /api/status is public on every bind, so this works before we hold any
        // credential — and it is what tells us WHICH credential to present.
        val status = runCatching { newClient.rest.getStatus(profile.httpBase) }.getOrNull()
        if (status == null) {
            failAndRetry(
                profile,
                "Can't reach ${profile.host}:${profile.port}. Is the dashboard running?",
            )
            return
        }

        val strategy = buildStrategy(profile, secret, status, connection)
        if (strategy == null) return // state already set to Failed with a reason
        activeStrategy = strategy

        val url = try {
            strategy.wsUrl(profile.wsBase, "/api/ws")
        } catch (e: AuthException) {
            // Wrong password is terminal; a transport blip during the ticket
            // mint is not. Only the latter should keep retrying.
            _state.value = ConnState.Failed(profile, e.message ?: "Authentication failed")
            return
        } catch (e: Exception) {
            failAndRetry(
                profile,
                "Authentication failed: ${e.message ?: e.javaClass.simpleName}",
            )
            return
        }

        newClient.rpc.onReady = {
            backoff.reset()
            reconnecting = false
            vault.markSeen(profile.id)
            _state.value = ConnState.Connected(profile, status)
        }
        newClient.rpc.connect(url)
        startWatching(profile, newClient)
        startHeartbeat(newClient)
    }

    /**
     * Pick the credential the server will actually accept.
     *
     * `auth_required == true` ⇒ every non-public path (and the WS upgrade)
     * needs a cookie session + a single-use ticket; `?token=` is refused.
     */
    private fun buildStrategy(
        profile: ConnectionProfile,
        secret: String,
        status: ServerStatus,
        connection: HermesConnection,
    ): CredentialStrategy? {
        if (!status.authRequired) {
            return CredentialStrategy.Token(secret)
        }
        val creds = splitBasicSecret(secret)
        if (creds == null) {
            _state.value = ConnState.Failed(
                profile,
                "${profile.label} requires a dashboard login. Re-pair with the QR, " +
                    "or enter the credential as user:password.",
            )
            return null
        }
        val (username, password) = creds
        return CredentialStrategy.Gated(
            httpBase = profile.httpBase,
            username = username,
            password = password,
            rest = connection.client.rest,
            cookies = connection.cookies,
            host = profile.host,
        )
    }

    /**
     * A credentialled ws:// URL for an ancillary socket (`/api/pty`,
     * `/api/events`). Mints a FRESH ticket in gated mode — tickets are single
     * use, so every socket needs its own.
     */
    suspend fun authorizedWsUrl(path: String): String? {
        val profile = activeProfile ?: return null
        val strategy = activeStrategy ?: return null
        return strategy.wsUrl(profile.wsBase, path)
    }

    // --------------------------------------------------------------- watching

    private fun startWatching(profile: ConnectionProfile, watched: HermesClient) {
        watchJob?.cancel()
        watchJob = managerScope.launch {
            launch {
                watched.rpc.events.collect { _globalEvents.tryEmit(it) }
            }
            watched.rpc.state.collect { channelState ->
                _channelState.value = channelState
                if (channelState is ChannelState.Degraded) reconnectLoop(profile)
            }
        }
    }

    /**
     * A silent socket is not the same as a healthy one. Android suspends the
     * radio; NAT tables drop idle flows; the far side can vanish without a FIN.
     * OkHttp's 20 s ping catches most of it, but a wedged server does not answer
     * RPC while still answering pings — so probe the application layer too.
     */
    private fun startHeartbeat(watched: HermesClient) {
        heartbeatJob?.cancel()
        heartbeatJob = managerScope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                if (_state.value !is ConnState.Connected) continue
                val alive = runCatching { watched.ping() }.isSuccess
                if (!alive) {
                    watched.rpc.markDegraded("heartbeat timeout")
                    return@launch
                }
            }
        }
    }

    private fun reconnectLoop(profile: ConnectionProfile) {
        if (reconnectJob?.isActive == true) return
        reconnecting = true
        reconnectJob = managerScope.launch {
            var sweeps = 0
            while (true) {
                val attempt = backoff.attempt + 1
                _state.value = ConnState.Reconnecting(currentProfile ?: profile, attempt)
                delay(backoff.nextDelay())

                val target = currentProfile ?: profile
                if (probeProfile(target)) {
                    connectLock.withLock { connectLocked(target) }
                    if (_state.value is ConnState.Connected ||
                        _state.value is ConnState.Connecting
                    ) {
                        reconnecting = false
                        return@launch
                    }
                    continue
                }

                // Address is not answering. After a few failed attempts assume
                // the lease moved rather than retrying a dead IP forever.
                if (attempt >= SWEEP_AFTER_ATTEMPTS) {
                    if (sweeps < MAX_SWEEPS) {
                        sweeps++
                        if (rediscoverAndRebind(target)) {
                            reconnecting = false
                            return@launch
                        }
                    }
                    // Still nothing on this machine: fail over to another
                    // paired system — the point of pairing more than one is
                    // that losing a machine doesn't lose the phone. "If not
                    // one, then the other."
                    val alt = raceProfiles(
                        vault.profiles.value.filter { it.id != target.id },
                    )
                    if (alt != null) {
                        _notices.tryEmit("${target.label} unreachable — switched to ${alt.label}")
                        connectLock.withLock { connectLocked(alt) }
                        if (_state.value is ConnState.Connected ||
                            _state.value is ConnState.Connecting
                        ) {
                            reconnecting = false
                            return@launch
                        }
                        continue
                    }
                }
            }
        }
    }

    /**
     * A link transition is better evidence than any timer.
     *
     * The connect is deliberately NOT immediate: an Android WIFI callback lands
     * before DHCP and routing have settled, so connecting on the same tick
     * reliably lost the race. Reset the backoff (so the retry is fast, not
     * 15 s deep) and hand the work to the reconnect loop, which owns retries.
     */
    private fun onLinkChanged() {
        val profile = activeProfile ?: return
        if (!networkMonitor.onLocalNetwork) return
        if (_state.value is ConnState.Connected) return
        backoff.reset()
        reconnectLoop(profile)
    }

    /** User-visible "try now", bypassing whatever backoff is pending. */
    fun retryNow() {
        managerScope.launch {
            reconnectJob?.cancel()
            reconnecting = false
            backoff.reset()
            val profile = activeProfile
            if (profile != null) connectTo(profile) else autoConnect()
        }
    }

    private fun teardown() {
        watchJob?.cancel()
        heartbeatJob?.cancel()
        client?.rpc?.disconnect()
        activeConnection?.cookies?.clear()
        activeConnection = null
        activeStrategy = null
        client = null
        _clientFlow.value = null
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnecting = false
        teardown()
    }

    /** Secret lookup for ancillary channels. Never log the result. */
    fun vaultSecret(profileId: String): String? = vault.secretFor(profileId)

    fun forgetProfile(id: String) {
        if (activeProfile?.id == id) {
            disconnect()
            activeProfile = null
            _state.value = ConnState.NoProfile
        }
        vault.removeProfile(id)
    }

    private companion object {
        const val HEARTBEAT_MS = 25_000L
        const val SWEEP_AFTER_ATTEMPTS = 3
        const val MAX_SWEEPS = 3
    }
}
