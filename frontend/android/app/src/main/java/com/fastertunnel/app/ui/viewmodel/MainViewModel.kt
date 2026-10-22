package com.fastertunnel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastertunnel.app.core.api.ApiResponse
import com.fastertunnel.app.core.data.PreferenceManager
import com.fastertunnel.app.core.api.CreateWireGuardProfileRequest
import com.fastertunnel.app.core.api.RetrofitClient
import com.fastertunnel.app.core.api.WireGuardProfileData
import com.fastertunnel.app.core.vpn.TunnelLogManager
import com.fastertunnel.app.core.vpn.TunnelStatus
import com.fastertunnel.app.core.vpn.TunnelTrafficStats
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyFormatException
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferenceManager(application)

    val tunnelStatus = TunnelLogManager.status
    val trafficStats = TunnelLogManager.stats
    val logs = TunnelLogManager.logs

    private val generatedProfileFile = File(application.noBackupFilesDir, "wireguard/wg0.conf")
    private val _wireGuardProfileUri = MutableStateFlow(
        prefs.wireGuardProfileUri
            ?: generatedProfileFile.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
    )
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
        TunnelLogManager.updateStatus(TunnelStatus.CONNECTING)
        TunnelLogManager.log("Preparando un perfil WireGuard seguro...", isHighlight = true)
        connectionJob = viewModelScope.launch {
            var backendAttempted = false
            try {
                val selectedProfile = _wireGuardProfileUri.value?.let(Uri::parse)
                val profileUri = if (
                    selectedProfile == null || selectedProfile == Uri.fromFile(generatedProfileFile)
                ) {
                    TunnelLogManager.log("Actualizando el perfil WireGuard remoto...")
                    withContext(Dispatchers.IO) { provisionWireGuardProfile() }
                } else {
                    selectedProfile
                }
                val profileBytes = withContext(Dispatchers.IO) {
                    if (profileUri.scheme == "file") {
                        File(requireNotNull(profileUri.path)).readBytes()
                    } else {
                        context.contentResolver.openInputStream(profileUri)?.use { it.readBytes() }
                            ?: throw IOException("No se pudo abrir el perfil seleccionado.")
                    }
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

    private suspend fun provisionWireGuardProfile(): Uri {
        val privateKeyFile = File(generatedProfileFile.parentFile, "private.key")
        val privateKey = if (privateKeyFile.isFile) {
            Key.fromBase64(privateKeyFile.readText(Charsets.US_ASCII).trim())
        } else {
            val key = KeyPair().privateKey
            privateKeyFile.parentFile?.mkdirs()
            val temporaryKeyFile = File(privateKeyFile.parentFile, "private.key.tmp")
            temporaryKeyFile.writeText(key.toBase64(), Charsets.US_ASCII)
            temporaryKeyFile.setReadable(false, false)
            temporaryKeyFile.setReadable(true, true)
            temporaryKeyFile.setWritable(false, false)
            temporaryKeyFile.setWritable(true, true)
            if (!temporaryKeyFile.renameTo(privateKeyFile)) {
                temporaryKeyFile.delete()
                throw IOException("No se pudo guardar la clave privada WireGuard.")
            }
            privateKeyFile.setReadable(false, false)
            privateKeyFile.setReadable(true, true)
            privateKeyFile.setWritable(false, false)
            privateKeyFile.setWritable(true, true)
            key
        }

        val response = requestWireGuardProfileWithRetry(
            CreateWireGuardProfileRequest(KeyPair(privateKey).publicKey.toBase64())
        )
        if (!response.isSuccessful) {
            throw IOException("El servidor rechazó la solicitud de perfil (HTTP ${response.code()}).")
        }
        val profile = response.body()?.data
            ?: throw IOException("El servidor respondió sin los datos del perfil WireGuard.")
        val endpointPort = profile.endpoint.substringAfterLast(':', "").toIntOrNull()
        val serverPublicKey = try {
            Key.fromBase64(profile.serverPublicKey).toBase64()
        } catch (e: KeyFormatException) {
            throw IOException("El servidor entregó una clave pública WireGuard inválida.", e)
        }
        if (profile.allowedIPs != listOf("0.0.0.0/0") ||
            !profile.address.matches(
                Regex("^10\\.66\\.0\\.(?:1[0-9]|[2-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-4])/32$")
            ) ||
            !isValidIpv4Address(profile.dns) ||
            !profile.endpoint.matches(Regex("^(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+]):[0-9]{1,5}$")) ||
            endpointPort == null ||
            endpointPort !in 1..65535 ||
            profile.persistentKeepalive !in 0..120
        ) {
            throw IOException("El perfil WireGuard recibido está incompleto o no enruta todo el tráfico.")
        }

        val config = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${privateKey.toBase64()}")
            appendLine("Address = ${profile.address}")
            appendLine("DNS = ${profile.dns}")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $serverPublicKey")
            appendLine("Endpoint = ${profile.endpoint}")
            appendLine("AllowedIPs = ${profile.allowedIPs.joinToString(", ")}")
            appendLine("PersistentKeepalive = ${profile.persistentKeepalive}")
        }

        generatedProfileFile.parentFile?.mkdirs()
        val temporaryFile = File(generatedProfileFile.parentFile, "wg0.conf.tmp")
        temporaryFile.writeText(config, Charsets.UTF_8)
        temporaryFile.setReadable(false, false)
        temporaryFile.setReadable(true, true)
        temporaryFile.setWritable(false, false)
        temporaryFile.setWritable(true, true)
        if (!temporaryFile.renameTo(generatedProfileFile)) {
            temporaryFile.delete()
            throw IOException("No se pudo guardar el perfil WireGuard en el almacenamiento privado.")
        }
        val uri = Uri.fromFile(generatedProfileFile)
        prefs.wireGuardProfileUri = uri.toString()
        _wireGuardProfileUri.value = uri.toString()
        TunnelLogManager.log(
            "Perfil WireGuard recibido; la clave privada permanece en este dispositivo.",
            isHighlight = true
        )
        return uri
    }

    private suspend fun requestWireGuardProfileWithRetry(
        request: CreateWireGuardProfileRequest
    ): Response<ApiResponse<WireGuardProfileData>> {
        for (attempt in 1..PROFILE_REQUEST_ATTEMPTS) {
            try {
                val response = RetrofitClient.api.createWireGuardProfile(request)
                if (response.isSuccessful ||
                    response.code() in 400..499 ||
                    attempt == PROFILE_REQUEST_ATTEMPTS
                ) {
                    return response
                }
                TunnelLogManager.log(
                    "La API respondió HTTP ${response.code()}; reintentando la solicitud de perfil."
                )
                response.errorBody()?.close()
            } catch (e: IOException) {
                if (attempt == PROFILE_REQUEST_ATTEMPTS) {
                    throw e
                }
                TunnelLogManager.log(
                    "Se interrumpió la conexión con la API; reintento $attempt de " +
                        "${PROFILE_REQUEST_ATTEMPTS - 1} en ${PROFILE_RETRY_DELAYS_SECONDS[attempt - 1]} s."
                )
            }
            delay(PROFILE_RETRY_DELAYS_SECONDS[attempt - 1] * 1000L)
        }
        throw IOException("No se pudo obtener el perfil WireGuard.")
    }

    private fun isValidIpv4Address(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { octet ->
            octet.toIntOrNull()?.let { it in 0..255 } == true
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
        const val PROFILE_REQUEST_ATTEMPTS = 4
        val PROFILE_RETRY_DELAYS_SECONDS = longArrayOf(5, 15, 30)
    }
}
