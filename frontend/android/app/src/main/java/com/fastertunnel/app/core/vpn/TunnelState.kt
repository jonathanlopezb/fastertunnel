package com.fastertunnel.app.core.vpn

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
    PREEMPTED // Sesión tomada por otro dispositivo
}

data class TunnelTrafficStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val speedInKbps: Float = 0f,
    val speedOutKbps: Float = 0f,
    val durationSeconds: Long = 0
)

data class TunnelLogEntry(
    val timestamp: String,
    val message: String,
    val isError: Boolean = false,
    val isHighlight: Boolean = false
)
