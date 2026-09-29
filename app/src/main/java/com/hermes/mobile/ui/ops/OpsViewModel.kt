package com.hermes.mobile.ui.ops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.transport.ChannelState
import com.hermes.mobile.data.repo.OpsRepository
import com.hermes.mobile.domain.model.CronJob
import com.hermes.mobile.domain.model.GitStatus
import com.hermes.mobile.domain.model.ModelCatalog
import com.hermes.mobile.domain.model.SkillInfo
import com.hermes.mobile.domain.model.SystemStats
import com.hermes.mobile.domain.model.UsageReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ViewModel for the whole Ops surface.
 *
 * Each panel loads on demand rather than everything up front: `/api/model/options`
 * probes provider credentials and `/api/analytics/usage` runs SQL over the whole
 * session history, so eagerly fetching both to render a menu would make opening
 * the tab feel broken on a cold server.
 */
@HiltViewModel
class OpsViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val opsRepository: OpsRepository,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val channelState: StateFlow<ChannelState> = connectionManager.channelState

    private val _stats = MutableStateFlow<SystemStats?>(null)
    val stats: StateFlow<SystemStats?> = _stats.asStateFlow()

    private val _systems = MutableStateFlow<List<ConnectionManager.SystemView>>(emptyList())

    /** Every paired system with its live resources — the Ops systems list. */
    val systems: StateFlow<List<ConnectionManager.SystemView>> = _systems.asStateFlow()

    private val _models = MutableStateFlow<ModelCatalog?>(null)
    val models: StateFlow<ModelCatalog?> = _models.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val skills: StateFlow<List<SkillInfo>> = _skills.asStateFlow()

    private val _cron = MutableStateFlow<List<CronJob>>(emptyList())
    val cron: StateFlow<List<CronJob>> = _cron.asStateFlow()

    private val _git = MutableStateFlow<GitStatus?>(null)
    val git: StateFlow<GitStatus?> = _git.asStateFlow()

    private val _gitPath = MutableStateFlow<String?>(null)
    val gitPath: StateFlow<String?> = _gitPath.asStateFlow()

    private val _usage = MutableStateFlow<UsageReport?>(null)
    val usage: StateFlow<UsageReport?> = _usage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** A model the server flagged as expensive, awaiting the user's confirmation. */
    private val _pendingModel = MutableStateFlow<PendingModel?>(null)
    val pendingModel: StateFlow<PendingModel?> = _pendingModel.asStateFlow()

    // ------------------------------------------------------------- overview

    fun refreshOverview() {
        load("stats") { _stats.value = opsRepository.systemStats() }
        load(null) { _systems.value = connectionManager.systemViews() }
    }

    // -------------------------------------------------------------- models

    fun loadModels(force: Boolean = false) {
        if (_models.value != null && !force) return
        load("models") { _models.value = opsRepository.modelCatalog() }
    }

    fun switchModel(provider: String, model: String, confirmExpensive: Boolean = false) {
        load("model-set") {
            val result = opsRepository.setModel(model, provider, confirmExpensive)
            when {
                result.confirmRequired -> _pendingModel.value = PendingModel(
                    provider = provider,
                    model = model,
                    message = result.message ?: "This model is expensive. Use it anyway?",
                )
                result.ok -> {
                    _pendingModel.value = null
                    _models.value = _models.value?.copy(
                        currentModel = model,
                        currentProvider = provider,
                    )
                    _userMessage.emit("New sessions will use $model")
                }
                else -> _userMessage.emit(result.message ?: "The server refused the switch")
            }
        }
    }

    fun dismissPendingModel() {
        _pendingModel.value = null
    }

    // -------------------------------------------------------------- skills

    fun loadSkills(force: Boolean = false) {
        if (_skills.value.isNotEmpty() && !force) return
        load("skills") { _skills.value = opsRepository.skills().sortedBy { it.name } }
    }

    fun toggleSkill(skill: SkillInfo) {
        // Optimistic: the switch has to answer the finger immediately, and the
        // reload right after is what makes a failed write visible.
        _skills.value = _skills.value.map {
            if (it.name == skill.name) it.copy(enabled = !it.enabled) else it
        }
        load(null) {
            runCatching { opsRepository.toggleSkill(skill.name, !skill.enabled) }
                .onFailure {
                    _userMessage.emit("Couldn't change ${skill.name}: ${it.message}")
                    _skills.value = opsRepository.skills().sortedBy { s -> s.name }
                }
        }
    }

    // ---------------------------------------------------------------- cron

    fun loadCron(force: Boolean = false) {
        if (_cron.value.isNotEmpty() && !force) return
        load("cron") { _cron.value = opsRepository.cronJobs() }
    }

    fun cronAction(job: CronJob, action: CronAction) {
        load("cron-$action") {
            when (action) {
                CronAction.PAUSE -> opsRepository.cronPause(job.id)
                CronAction.RESUME -> opsRepository.cronResume(job.id)
                CronAction.RUN -> opsRepository.cronTrigger(job.id)
            }
            _userMessage.emit(
                when (action) {
                    CronAction.PAUSE -> "Paused ${job.name}"
                    CronAction.RESUME -> "Resumed ${job.name}"
                    CronAction.RUN -> "Triggered ${job.name}"
                },
            )
            _cron.value = opsRepository.cronJobs()
        }
    }

    // ----------------------------------------------------------------- git

    fun loadGit(force: Boolean = false) {
        if (_git.value != null && !force) return
        load("git") {
            val path = _gitPath.value ?: opsRepository.defaultCwd()
            if (path == null) {
                _userMessage.emit("The server didn't report a working directory")
                return@load
            }
            _gitPath.value = path
            _git.value = opsRepository.gitStatus(path)
            if (_git.value == null) _userMessage.emit("$path isn't a git repository")
        }
    }

    // --------------------------------------------------------------- usage

    fun loadUsage(days: Int = 30) = load("usage") { _usage.value = opsRepository.usage(days) }

    // ---------------------------------------------------------------- logs

    fun loadLogs() = load("logs") { _logs.value = opsRepository.logsTail() }

    // ------------------------------------------------------------- gateway

    fun gateway(action: String) {
        load("gateway") {
            opsRepository.gatewayAction(action)
            _userMessage.emit("Gateway $action sent")
        }
    }

    /**
     * Runs [block], surfacing failure to the user instead of swallowing it, and
     * marking [tag] busy so the panel that asked can show it. A null tag means
     * "do not show a spinner for this" — used by optimistic writes.
     */
    private fun load(tag: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (tag != null) _busy.value = tag
            try {
                block()
            } catch (e: Exception) {
                _userMessage.emit(e.message ?: e.javaClass.simpleName)
            } finally {
                if (tag != null && _busy.value == tag) _busy.value = null
            }
        }
    }
}

enum class CronAction { PAUSE, RESUME, RUN }

data class PendingModel(val provider: String, val model: String, val message: String)
