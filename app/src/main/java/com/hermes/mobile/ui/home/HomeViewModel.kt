package com.hermes.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.core.connection.ConnectionProfile
import com.hermes.mobile.core.transport.ChannelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
    val channelState: StateFlow<ChannelState> = connectionManager.channelState
    val profiles: StateFlow<List<ConnectionProfile>> = connectionManager.profiles

    private val _systemStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** profileId -> reachable, refreshed while the Systems list is on screen. */
    val systemStates: StateFlow<Map<String, Boolean>> = _systemStates.asStateFlow()

    /** Probe every paired system so the list shows who is reachable right now. */
    suspend fun refreshSystemStates() {
        _systemStates.value = connectionManager.probeAll()
    }

    /** Bind to another paired PC without unpairing the current one. */
    fun switchTo(profile: ConnectionProfile) {
        viewModelScope.launch { connectionManager.connectTo(profile) }
    }

    fun forget(profile: ConnectionProfile) {
        connectionManager.forgetProfile(profile.id)
    }
}
