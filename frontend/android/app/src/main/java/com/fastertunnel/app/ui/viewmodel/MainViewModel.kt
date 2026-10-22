package com.fastertunnel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastertunnel.app.core.data.PreferenceManager
import com.fastertunnel.app.core.vpn.TunnelLogManager
import com.fastertunnel.app.core.vpn.TunnelStatus
import com.fastertunnel.app.core.vpn.TunnelTrafficStats
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferenceManager(application)

    val tunnelStatus = TunnelLogManager.status
    val trafficStats = TunnelLogManager.stats
    val logs = TunnelLogManager.logs

    private val _wireGuardProfileUri = MutableStateFlow(prefs.wireGuardProfileUri)
    val wireGuardProfileUri = _wireGuardProfileUri.asStateFlow()

    private val wireGuardBackend by lazy { GoBackend(application) }
    private var connectionJob: Job? = null
    private var trafficMonitorJob: Job? = null
    private var connectedAtMs = 0L

    private val wireGuardTunnel = object : Tunnel {
        override fun getName(): String = "fastertunnel"

        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN &&
                TunnelLogManager.status.value != TunnelStatus.ERROR
            ) {
                TunnelLogManager.updateStatus(TunnelStatus.DISCONNECTED)
            }
        }
    }

    init {
        prefs.clearAuth()
    }

    fun setWireGuardProfile(uri: Uri) {
        val value = uri.toString()
        prefs.wireGuardProfileUri = value
        _wireGuardProfileUri.value = value
        TunnelLogManager.log("Perfil WireGuard seleccionado. La clave privada no se copia a la app.")
    }

    fun toggleTunnel(context: Context) {
        when (tunnelStatus.value) {
            TunnelStatus.CONNECTED -> stopTunnel()
            TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING, TunnelStatus.DISCONNECTING -> Unit
            else -> startTunnel(context)
        }
    }

    private fun startTunnel(context: Context) {
        val profileUri = _wireGuardProfileUri.value?.let(Uri::parse)
        if (profileUri == null) {
            TunnelLogManager.log("Importa un perfil WireGuard antes de conectar.", isError = true)
            TunnelLogManager.updateStatus(TunnelStatus.ERROR)
            return
        }

        TunnelLogManager.updateStatus(TunnelStatus.CONNECTING)
        TunnelLogManager.log("Leyendo el perfil WireGuard local...", isHighlight = true)
        connectionJob = viewModelScope.launch {
            var backendAttempted = false
            try {
                val profileBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(profileUri)?.use { it.readBytes() }
                        ?: throw IOException("No se pudo abrir el perfil seleccionado.")
                }
                if (profileBytes.isEmpty() || profileBytes.size > MAX_PROFILE_SIZE_BYTES) {
                    throw IllegalArgumentException("El perfil está vacío o supera el tamaño permitido.")
                }
                if (!profileHasDefaultRoute(profileBytes.toString(Charsets.UTF_8))) {
                    throw IllegalArgumentException(
                        "El perfil debe incluir AllowedIPs = 0.0.0.0/0 para enrutar todo el tráfico."
                    )
                }

                val config = try {
                    Config.parse(ByteArrayInputStream(profileBytes))
                } catch (e: BadConfigException) {
                    TunnelLogManager.log("El archivo seleccionado no tiene un formato WireGuard válido.", isError = true)
                    throw InvalidWireGuardProfileException()
                }

                TunnelLogManager.updateStatus(TunnelStatus.AUTHENTICATING)
                TunnelLogManager.log("Activando túnel cifrado WireGuard...")
                backendAttempted = true
                withContext(Dispatchers.IO) {
                    wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.UP, config)
                }

                TunnelLogManager.log("Interfaz WireGuard activa; verificando salida a internet...")
                if (!withContext(Dispatchers.IO) { verifyInternetThroughTunnel() }) {
                    throw IOException("El túnel se activó, pero la prueba de internet no respondió.")
                }

                connectedAtMs = System.currentTimeMillis()
                TunnelLogManager.updateStats(TunnelTrafficStats())
                TunnelLogManager.updateStatus(TunnelStatus.CONNECTED)
                TunnelLogManager.log("Conexión WireGuard e internet verificadas.", isHighlight = true)
                startTrafficMonitor()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (backendAttempted) {
                    try {
                        withContext(Dispatchers.IO) {
                            wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null)
                        }
                    } catch (stopError: Exception) {
                        TunnelLogManager.logException("No se pudo detener el túnel tras el error", stopError)
                    }
                }
                if (e !is InvalidWireGuardProfileException) {
                    TunnelLogManager.logException("Falló la conexión WireGuard", e)
                }
                TunnelLogManager.updateStatus(TunnelStatus.ERROR)
            }
        }
    }

    private fun stopTunnel() {
        connectionJob?.cancel()
        trafficMonitorJob?.cancel()
        TunnelLogManager.updateStatus(TunnelStatus.DISCONNECTING)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    wireGuardBackend.setState(wireGuardTunnel, Tunnel.State.DOWN, null)
                }
                TunnelLogManager.updateStats(TunnelTrafficStats())
                TunnelLogManager.updateStatus(TunnelStatus.DISCONNECTED)
                TunnelLogManager.log("Túnel WireGuard detenido.", isHighlight = true)
            } catch (e: Exception) {
                TunnelLogManager.logException("No se pudo detener WireGuard", e)
                TunnelLogManager.updateStatus(TunnelStatus.ERROR)
            }
        }
    }

    private fun profileHasDefaultRoute(profile: String): Boolean =
        profile.lineSequence().any { line ->
            val parts = line.split("=", limit = 2)
            parts.size == 2 &&
                parts[0].trim().equals("AllowedIPs", ignoreCase = true) &&
                parts[1].split(",").any { it.trim() == "0.0.0.0/0" }
        }

    private fun verifyInternetThroughTunnel(): Boolean {
        val connection = URL(CONNECTIVITY_CHECK_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = PROBE_TIMEOUT_MS
        connection.readTimeout = PROBE_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        return try {
            connection.responseCode == HTTP_NO_CONTENT
        } finally {
            connection.disconnect()
        }
    }

    private fun startTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = viewModelScope.launch {
            var previousIn = 0L
            var previousOut = 0L
            while (tunnelStatus.value == TunnelStatus.CONNECTED) {
                delay(1000)
                try {
                    val statistics = withContext(Dispatchers.IO) {
                        wireGuardBackend.getStatistics(wireGuardTunnel)
                    }
                    val bytesIn = statistics.totalRx()
                    val bytesOut = statistics.totalTx()
                    val duration = (System.currentTimeMillis() - connectedAtMs) / 1000
                    TunnelLogManager.updateStats(
                        TunnelTrafficStats(
                            bytesIn = bytesIn,
                            bytesOut = bytesOut,
                            speedInKbps = (bytesIn - previousIn).coerceAtLeast(0).toFloat() / 1024f,
                            speedOutKbps = (bytesOut - previousOut).coerceAtLeast(0).toFloat() / 1024f,
                            durationSeconds = duration
                        )
                    )
                    previousIn = bytesIn
                    previousOut = bytesOut
                } catch (e: Exception) {
                    TunnelLogManager.logException("No se pudieron leer las estadísticas WireGuard", e)
                    break
                }
            }
        }
    }

    private class InvalidWireGuardProfileException : Exception()

    private companion object {
        const val CONNECTIVITY_CHECK_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val HTTP_NO_CONTENT = 204
        const val MAX_PROFILE_SIZE_BYTES = 64 * 1024
        const val PROBE_TIMEOUT_MS = 8000
    }
}
