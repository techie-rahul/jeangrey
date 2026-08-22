package com.example.meshtest.service

import com.example.meshtest.model.SosPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object MeshRepository {
    const val DEFAULT_SERVER_URL = "https://jeangrey.onrender.com"

    private val _isMeshActive = MutableStateFlow(false)
    val isMeshActive: StateFlow<Boolean> = _isMeshActive.asStateFlow()

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isGatewayModeEnabled = MutableStateFlow(true)
    val isGatewayModeEnabled: StateFlow<Boolean> = _isGatewayModeEnabled.asStateFlow()

    private val _connectedEndpoints = MutableStateFlow<Map<String, String>>(emptyMap())
    val connectedEndpoints: StateFlow<Map<String, String>> = _connectedEndpoints.asStateFlow()

    private val _connectingEndpoints = MutableStateFlow<Set<String>>(emptySet())
    val connectingEndpoints: StateFlow<Set<String>> = _connectingEndpoints.asStateFlow()

    private val _relayQueueItems = MutableStateFlow<List<SosPacket>>(emptyList())
    val relayQueueItems: StateFlow<List<SosPacket>> = _relayQueueItems.asStateFlow()

    private val _terminalLogs = MutableStateFlow<List<String>>(emptyList())
    val terminalLogs: StateFlow<List<String>> = _terminalLogs.asStateFlow()

    private val _lastReceivedSos = MutableStateFlow<SosPacket?>(null)
    val lastReceivedSos: StateFlow<SosPacket?> = _lastReceivedSos.asStateFlow()

    fun setMeshActive(active: Boolean) { _isMeshActive.value = active }
    fun setServerUrl(url: String) { _serverUrl.value = url }
    fun setGatewayMode(enabled: Boolean) { _isGatewayModeEnabled.value = enabled }
    fun updateConnectedEndpoints(endpoints: Map<String, String>) { _connectedEndpoints.value = endpoints }
    fun updateConnectingEndpoints(endpoints: Set<String>) { _connectingEndpoints.value = endpoints }
    fun updateRelayQueue(items: List<SosPacket>) { _relayQueueItems.value = items }
    fun setLastReceivedSos(packet: SosPacket) { _lastReceivedSos.value = packet }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val current = _terminalLogs.value.toMutableList()
        current.add("[$time] $msg")
        if (current.size > 200) current.removeAt(0)
        _terminalLogs.value = current
    }

    fun clearLogs() {
        _terminalLogs.value = emptyList()
        addLog("🧹 Log cleared.")
    }
}