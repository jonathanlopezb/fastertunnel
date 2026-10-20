package com.fastertunnel.app.core.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelLogManager {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<TunnelLogEntry>>(emptyList())
    val logs: StateFlow<List<TunnelLogEntry>> = _logs.asStateFlow()

    private val _status = MutableStateFlow(TunnelStatus.DISCONNECTED)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    private val _stats = MutableStateFlow(TunnelTrafficStats())
    val stats: StateFlow<TunnelTrafficStats> = _stats.asStateFlow()

    fun log(message: String, isError: Boolean = false, isHighlight: Boolean = false) {
        val entry = TunnelLogEntry(
            timestamp = timeFormat.format(Date()),
            message = message,
            isError = isError,
            isHighlight = isHighlight
        )
        _logs.value = (_logs.value + entry).takeLast(200)
    }

    fun updateStatus(newStatus: TunnelStatus) {
        _status.value = newStatus
    }

    fun updateStats(newStats: TunnelTrafficStats) {
        _stats.value = newStats
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
