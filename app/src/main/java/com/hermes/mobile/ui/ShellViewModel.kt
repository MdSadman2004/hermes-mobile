package com.hermes.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.mobile.core.connection.ConnState
import com.hermes.mobile.core.connection.ConnectionManager
import com.hermes.mobile.data.repo.TransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val transferRepository: TransferRepository,
    val deepLinkBus: DeepLinkBus,
    val shareBus: ShareBus,
) : ViewModel() {

    val connState: StateFlow<ConnState> = connectionManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.Probing)

    /** Connection-manager notices (failover hops, DHCP rebinds, ambiguity). */
    val notices: SharedFlow<String> = connectionManager.notices

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    init {
        // Cold start: race saved profiles, bind the first that answers.
        viewModelScope.launch { connectionManager.autoConnect() }
    }

    /**
     * Upload files that arrived from another app's share sheet.
     *
     * Sequential, not parallel: each file is fully base64'd in memory, so
     * uploading a multi-share concurrently multiplies peak RAM by the number of
     * files and is how a five-photo share becomes an OOM kill.
     */
    fun uploadShared(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (uri in uris) {
                try {
                    val result = transferRepository.uploadToPc(uri)
                    ok++
                    if (uris.size == 1) {
                        _userMessage.emit("Sent ${result.name} to ${result.remotePath}")
                    }
                } catch (e: Exception) {
                    _userMessage.emit(e.message ?: "Send failed")
                }
            }
            if (uris.size > 1 && ok > 0) {
                _userMessage.emit("Sent $ok of ${uris.size} files to ${TransferRepository.DEFAULT_INBOX}")
            }
            shareBus.clearFiles()
        }
    }
}
