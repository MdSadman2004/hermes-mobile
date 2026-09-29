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

    private val _systemViews = MutableStateFlow<List<ConnectionManager.SystemView>>(emptyList())

    /** Live per-system resource views, refreshed while the Systems list is on screen. */
    val systemViews: StateFlow<List<ConnectionManager.SystemView>> = _systemViews.asStateFlow()

    /** Probe every paired system so the cards show reachability and usage. */
    suspend fun refreshSystemStates() {
        _systemViews.value = connectionManager.systemViews()
    }

    /** Bind to another paired PC without unpairing the current one. */
    fun switchTo(profile: ConnectionProfile) {
        viewModelScope.launch { connectionManager.connectTo(profile) }
    }

    fun forget(profile: ConnectionProfile) {
        connectionManager.forgetProfile(profile.id)
    }
}
