package com.fastertunnel.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fastertunnel.app.core.api.LoginRequest
import com.fastertunnel.app.core.api.RetrofitClient
import com.fastertunnel.app.core.api.ServerNodeDto
import com.fastertunnel.app.core.api.TweakDto
import com.fastertunnel.app.core.data.CarrierDetector
import com.fastertunnel.app.core.data.CarrierInfo
import com.fastertunnel.app.core.data.DeviceUtils
import com.fastertunnel.app.core.data.PreferenceManager
import com.fastertunnel.app.core.vpn.FasterVpnService
import com.fastertunnel.app.core.vpn.TunnelLogManager
import com.fastertunnel.app.core.vpn.TunnelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = PreferenceManager(application)
    val tunnelStatus = TunnelLogManager.status
    val trafficStats = TunnelLogManager.stats
    val logs = TunnelLogManager.logs

    private val _carrierInfo = MutableStateFlow(CarrierDetector.detectCarrier(application))
    val carrierInfo: StateFlow<CarrierInfo> = _carrierInfo.asStateFlow()

    private val _customSni = MutableStateFlow(prefs.customSni)
    val customSni: StateFlow<String> = _customSni.asStateFlow()

    private val _isBackendOnline = MutableStateFlow(false)
    val isBackendOnline: StateFlow<Boolean> = _isBackendOnline.asStateFlow()

    private val _backendLatency = MutableStateFlow<Long?>(null)
    val backendLatency: StateFlow<Long?> = _backendLatency.asStateFlow()

    private val _servers = MutableStateFlow<List<ServerNodeDto>>(emptyList())
    val servers: StateFlow<List<ServerNodeDto>> = _servers.asStateFlow()

    private val _selectedServer = MutableStateFlow<ServerNodeDto?>(null)
    val selectedServer: StateFlow<ServerNodeDto?> = _selectedServer.asStateFlow()

    private val _tweaks = MutableStateFlow<List<TweakDto>>(emptyList())
    val tweaks: StateFlow<List<TweakDto>> = _tweaks.asStateFlow()

    private val _selectedTweak = MutableStateFlow<TweakDto?>(null)
    val selectedTweak: StateFlow<TweakDto?> = _selectedTweak.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // Omitido para modo debug: No mostrar diálogo de inicio de sesión automáticamente
    private val _showLoginDialog = MutableStateFlow(false)
    val showLoginDialog: StateFlow<Boolean> = _showLoginDialog.asStateFlow()

    init {
        checkBackendHealth()
        initializeDefaultServersAndTweaks()
        if (prefs.isLoggedIn && !prefs.token.isNullOrEmpty()) {
            loadServersAndTweaks()
        }
    }

    fun checkBackendHealth() {
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getHealth()
                }
                val rtt = System.currentTimeMillis() - start
                if (response.isSuccessful && response.body()?.success == true) {
                    _isBackendOnline.value = true
                    _backendLatency.value = rtt
                    TunnelLogManager.log("[API] Conectado a Render backend (${rtt}ms) - 'faster bienvenido'")
                } else {
                    _isBackendOnline.value = false
                }
            } catch (e: Exception) {
                _isBackendOnline.value = false
                _backendLatency.value = null
                TunnelLogManager.log("[API] Backend en espera/iniciando: ${e.message}")
            }
        }
    }

    private val _isDeviceLimitReached = MutableStateFlow(false)
    val isDeviceLimitReached: StateFlow<Boolean> = _isDeviceLimitReached.asStateFlow()

    fun login(user: String, pass: String, remember: Boolean, forceDisconnect: Boolean = false) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            _isDeviceLimitReached.value = false

            val context = getApplication<Application>()
            val hwid = DeviceUtils.getDeviceHardwareId(context)

            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.api.login(
                        LoginRequest(
                            username = user.trim(),
                            password = pass.trim(),
                            deviceId = hwid,
                            platform = "android",
                            forceDisconnect = forceDisconnect
                        )
                    )
                }

                if (response.isSuccessful && response.body()?.success == true) {
                    val loginData = response.body()!!.data!!
                    prefs.token = loginData.token
                    prefs.sessionId = loginData.sessionId
                    prefs.username = user
                    prefs.isLoggedIn = true
                    _isDeviceLimitReached.value = false
                    if (remember) {
                        prefs.password = pass
                        prefs.rememberCredentials = true
                    } else {
                        prefs.password = ""
                        prefs.rememberCredentials = false
                    }

                    _showLoginDialog.value = false
                    TunnelLogManager.log("Sesión iniciada exitosamente: $user", isHighlight = true)
                    TunnelLogManager.log("ID de arrendamiento asignado: ${loginData.sessionId.take(8)}...")

                    loadServersAndTweaks()
                } else {
                    if (response.code() == 409) {
                        _isDeviceLimitReached.value = true
                        _loginError.value = "Ya tienes una sesión abierta en otro dispositivo. Cierra la sesión en tu otro teléfono para poder ingresar."
                        TunnelLogManager.log("Acceso denegado: cuenta activa en otro dispositivo.", isError = true)
                    } else {
                        val err = response.body()?.message ?: "Credenciales inválidas"
                        _loginError.value = err
                        TunnelLogManager.log("Error de autenticación: $err", isError = true)
                    }
                }
            } catch (e: Exception) {
                _loginError.value = "Error al conectar con Render backend: ${e.localizedMessage}"
                TunnelLogManager.log("Fallo de red en login: ${e.message}", isError = true)
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            val token = prefs.token
            if (!token.isNullOrEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        RetrofitClient.api.logout("Bearer $token")
                    } catch (_: Exception) {}
                }
            }
            prefs.clearAuth()
            stopTunnel()
            // Modo Debug: No mostrar diálogo de login al cerrar sesión
            // _showLoginDialog.value = true
            TunnelLogManager.log("Sesión cerrada.", isHighlight = true)
        }
    }

    fun dismissLoginDialog() {
        _showLoginDialog.value = false
    }

    fun openLoginDialog() {
        _showLoginDialog.value = true
    }

    fun selectServer(server: ServerNodeDto) {
        _selectedServer.value = server
        prefs.selectedServerId = server.nodeId
        TunnelLogManager.log("Servidor seleccionado: ${server.name} [${server.transport}]")
    }

    fun selectTweak(tweak: TweakDto) {
        _selectedTweak.value = tweak
        prefs.selectedTweakId = tweak.tweakId
        TunnelLogManager.log("Tweak seleccionado: ${tweak.label} (${tweak.carrier})")
    }

    fun toggleTunnel(context: Context) {
        when (tunnelStatus.value) {
            TunnelStatus.CONNECTED, TunnelStatus.CONNECTING, TunnelStatus.AUTHENTICATING -> {
                stopTunnel()
            }
            TunnelStatus.DISCONNECTED, TunnelStatus.ERROR, TunnelStatus.PREEMPTED -> {
                startTunnel(context)
            }
            else -> {}
        }
    }

    fun updateCustomSni(newSni: String) {
        val trimmed = newSni.trim()
        _customSni.value = trimmed
        prefs.customSni = trimmed
        TunnelLogManager.log("SNI personalizado actualizado: $trimmed")
    }

    fun setCarrierManual(isMovistar: Boolean) {
        val newCarrier = if (isMovistar) {
            CarrierInfo(name = "Movistar", countryIso = "CO", isMovistar = true, isClaro = false, isTigo = false)
        } else {
            CarrierInfo(name = "Claro", countryIso = "CO", isMovistar = false, isClaro = true, isTigo = false)
        }
        _carrierInfo.value = newCarrier
        val defaultTweaks = _tweaks.value
        val targetTweak = if (isMovistar) {
            defaultTweaks.firstOrNull { it.tweakId == "movistar_ws_portal" }
        } else {
            defaultTweaks.firstOrNull { it.tweakId == "claro_fastdns_udp" }
        }
        targetTweak?.let {
            _selectedTweak.value = it
            prefs.selectedTweakId = it.tweakId
        }
        TunnelLogManager.log("SIM fijada manualmente a: ${newCarrier.name}", isHighlight = true)
    }

    fun onModeSelected(mode: String) {
        prefs.selectedMode = mode
        TunnelLogManager.log("Modo seleccionado: $mode", isHighlight = true)
        val defaultTweaks = _tweaks.value
        val detected = _carrierInfo.value

        when (mode) {
            "FastDNS", "SuperDNS", "DNSLite" -> {
                val targetTweak = defaultTweaks.firstOrNull {
                    if (detected.isClaro) it.tweakId == "claro_fastdns_udp"
                    else it.tweakId == "movistar_fastdns_udp"
                } ?: defaultTweaks.firstOrNull { it.transport == "FAST_DNS" }
                targetTweak?.let {
                    _selectedTweak.value = it
                    prefs.selectedTweakId = it.tweakId
                    prefs.selectedTransport = "FAST_DNS"
                }
            }
            "UDP" -> {
                // UDP mode: para Movistar usar WSS portal, para Claro FastDNS UDP
                val targetTweak = if (detected.isMovistar) {
                    defaultTweaks.firstOrNull { it.tweakId == "movistar_wss_portal" }
                        ?: defaultTweaks.firstOrNull { it.transport == "WSS" }
                } else {
                    defaultTweaks.firstOrNull { it.tweakId == "claro_fastdns_udp" }
                        ?: defaultTweaks.firstOrNull { it.transport == "FAST_DNS" }
                }
                targetTweak?.let {
                    _selectedTweak.value = it
                    prefs.selectedTweakId = it.tweakId
                    prefs.selectedTransport = it.transport
                }
            }
        }
    }

    private fun startTunnel(context: Context) {
        // Modo Debug: Inicio de sesión temporalmente deshabilitado para pruebas inmediatas
        // Cuando se reactive la autenticación comercial, se descomentará el bloque inferior:
        /*
        if (!prefs.isLoggedIn || prefs.token.isNullOrEmpty()) {
            _showLoginDialog.value = true
            TunnelLogManager.log("Por favor inicia sesión antes de conectar", isError = true)
            return
        }
        */

        val server = _selectedServer.value ?: _servers.value.firstOrNull() ?: ServerNodeDto(
            id = 1,
            nodeId = "gw-aws-ohio-01",
            name = "🔥 AWS Ohio #1 (FastDNS / WS)",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            host = "3.142.187.253",
            port = 53,
            transport = "FAST_DNS",
            sniHost = "portalrecargas.claro.com.co",
            isActive = true,
            isPremium = false,
            pingMs = 38,
            maxUsers = 500,
            currentUsers = 1
        )

        val tweak = _selectedTweak.value
        val effectiveTransport = tweak?.transport ?: server.transport

        // Ajustar servidor y puerto según el tweak seleccionado
        val effectiveServer = if (tweak != null && tweak.recommendedGateway != null) {
            _servers.value.firstOrNull { it.nodeId == tweak.recommendedGateway } ?: server
        } else {
            server
        }

        val effectivePort = when {
            effectiveTransport == "FAST_DNS" -> 53
            effectiveTransport == "WS"  -> 80   // WebSocket plano → ws-relay en EC2 port 80
            effectiveTransport == "WSS" && effectiveServer.port == 443 -> 443  // WSS real solo port 443
            effectiveTransport == "WSS" -> 80   // WSS en port 80 = WS plano (relay)
            else -> effectiveServer.port
        }
        val effectiveSni = tweak?.sniSpoof?.ifBlank { _customSni.value } ?: _customSni.value
        val payload = tweak?.payloadTemplate

        // Persistir en preferencias para el botón rápido de la notificación
        prefs.selectedServerHost = effectiveServer.host
        prefs.selectedServerPort = effectivePort
        prefs.selectedServerName = effectiveServer.name
        prefs.selectedTransport = effectiveTransport

        TunnelLogManager.log("SIM Operador: ${_carrierInfo.value.name} (${_carrierInfo.value.countryIso})")
        TunnelLogManager.log("Modo / Tweak: ${tweak?.label ?: effectiveTransport}")
        TunnelLogManager.log("Transporte: $effectiveTransport en puerto $effectivePort")
        if (!effectiveSni.isNullOrBlank()) {
            TunnelLogManager.log("SNI Inyectado: $effectiveSni", isHighlight = true)
        }

        FasterVpnService.start(
            context = context,
            host = effectiveServer.host,
            port = effectivePort,
            serverName = effectiveServer.name,
            transport = effectiveTransport,
            sni = effectiveSni,
            payload = payload
        )
    }

    private fun stopTunnel() {
        val context = getApplication<Application>()
        FasterVpnService.stop(context)
    }

    private fun loadServersAndTweaks() {
        viewModelScope.launch {
            val token = prefs.token ?: return@launch
            try {
                val serversRes = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getServers("Bearer $token")
                }
                if (serversRes.isSuccessful && serversRes.body()?.success == true) {
                    val serverList = serversRes.body()!!.data!!.servers
                    _servers.value = serverList
                    _selectedServer.value = serverList.firstOrNull { it.nodeId == prefs.selectedServerId }
                        ?: serverList.firstOrNull()
                    TunnelLogManager.log("Lista de ${serverList.size} servidores sincronizada y verificada.")
                }

                val tweaksRes = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getTweaks("Bearer $token")
                }
                if (tweaksRes.isSuccessful && tweaksRes.body()?.success == true) {
                    val tweakList = tweaksRes.body()!!.data!!.tweaks
                    _tweaks.value = tweakList
                    _selectedTweak.value = tweakList.firstOrNull { it.tweakId == prefs.selectedTweakId }
                        ?: tweakList.firstOrNull()
                    TunnelLogManager.log("Configuración de ${tweakList.size} tweaks cargada con firma Ed25519.")
                }
            } catch (e: Exception) {
                TunnelLogManager.log("Usando configuración local en caché: ${e.message}")
            }
        }
    }

    private fun initializeDefaultServersAndTweaks() {
        val defaultServers = listOf(
            ServerNodeDto(
                id = 1,
                nodeId = "gw-aws-ohio-01",
                name = "🔥 AWS Ohio #1 (FastDNS / UDP 53)",
                countryCode = "US",
                flagEmoji = "🇺🇸",
                host = "3.142.187.253",
                port = 53,
                transport = "FAST_DNS",
                sniHost = "portalrecargas.claro.com.co",
                isActive = true,
                isPremium = false,
                pingMs = 38,
                maxUsers = 500,
                currentUsers = 1
            ),
            ServerNodeDto(
                id = 2,
                nodeId = "gw-aws-ohio-02",
                name = "AWS Ohio #2 (WSS / HTTP 80)",
                countryCode = "US",
                flagEmoji = "🇺🇸",
                host = "3.142.187.253",
                port = 80,
                transport = "WSS",
                sniHost = "web.whatsapp.net",
                isActive = true,
                isPremium = false,
                pingMs = 42,
                maxUsers = 500,
                currentUsers = 1
            ),
            ServerNodeDto(
                id = 3,
                nodeId = "gw-aws-ohio-03",
                name = "AWS Ohio #3 (Dropbear SSH 2222)",
                countryCode = "US",
                flagEmoji = "🇺🇸",
                host = "3.142.187.253",
                port = 2222,
                transport = "SSH",
                sniHost = null,
                isActive = true,
                isPremium = true,
                pingMs = 45,
                maxUsers = 500,
                currentUsers = 1
            ),
            ServerNodeDto(
                id = 4,
                nodeId = "gw-cf-latam-01",
                name = "⚡ Cloudflare CDN (Ultra Rápido Latam 15ms)",
                countryCode = "CO",
                flagEmoji = "🇨🇴",
                host = "gw.fastertunnel.site",
                port = 443,
                transport = "WSS",
                sniHost = "web.whatsapp.net",
                isActive = true,
                isPremium = false,
                pingMs = 15,
                maxUsers = 1000,
                currentUsers = 1
            )
        )
        _servers.value = defaultServers
        _selectedServer.value = defaultServers.first()

        val defaultTweaks = listOf(
            // ── MOVISTAR Colombia ──
            // El relay escucha en puerto 80 con HTTP plano (no TLS).
            // Transporte "WS" = TCP plano + HTTP Upgrade (sin SSL).
            // Transporte "WSS" = TLS + HTTP Upgrade (solo port 443).
            TweakDto(
                id = 1,
                tweakId = "movistar_ws_portal",
                label = "Movistar Colombia – Portal mi.movistar (WS/80)",
                carrier = "Movistar",
                country = "CO",
                sniSpoof = "mi.movistar.com.co",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nUser-Agent: [ua]\r\n\r\n",
                transport = "WS",       // ← WS = TCP plano, NO TLS
                recommendedGateway = "gw-aws-ohio-02",
                isActive = true
            ),
            TweakDto(
                id = 2,
                tweakId = "movistar_ws_recargas",
                label = "Movistar Colombia – Recargas SNI (WS/80)",
                carrier = "Movistar",
                country = "CO",
                sniSpoof = "recargas.movistar.com.co",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nUser-Agent: [ua]\r\n\r\n",
                transport = "WS",
                recommendedGateway = "gw-aws-ohio-02",
                isActive = true
            ),
            TweakDto(
                id = 3,
                tweakId = "movistar_ws_whatsapp",
                label = "Movistar – WhatsApp SNI (WS/80)",
                carrier = "Movistar",
                country = "LATAM",
                sniSpoof = "web.whatsapp.net",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nUser-Agent: [ua]\r\n\r\n",
                transport = "WS",
                recommendedGateway = "gw-aws-ohio-02",
                isActive = true
            ),
            TweakDto(
                id = 4,
                tweakId = "movistar_fastdns_udp",
                label = "Movistar FastDNS (UDP 53 – Sin Saldo)",
                carrier = "Movistar",
                country = "CO",
                sniSpoof = "recargas.movistar.com.co",
                payloadTemplate = null,
                transport = "FAST_DNS",
                recommendedGateway = "gw-aws-ohio-01",
                isActive = true
            ),
            // ── CLARO Colombia ──
            TweakDto(
                id = 5,
                tweakId = "claro_fastdns_udp",
                label = "Claro Colombia FastDNS (UDP 53 – Sin Saldo)",
                carrier = "Claro",
                country = "CO",
                sniSpoof = "portalrecargas.claro.com.co",
                payloadTemplate = null,
                transport = "FAST_DNS",
                recommendedGateway = "gw-aws-ohio-01",
                isActive = true
            ),
            TweakDto(
                id = 6,
                tweakId = "claro_ws_portal",
                label = "Claro Colombia – Portal Recargas (WS/80)",
                carrier = "Claro",
                country = "CO",
                sniSpoof = "portalrecargas.claro.com.co",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nUser-Agent: [ua]\r\n\r\n",
                transport = "WS",
                recommendedGateway = "gw-aws-ohio-02",
                isActive = true
            ),
            TweakDto(
                id = 7,
                tweakId = "ssl_sni_custom",
                label = "Modo SNI Personalizado (Manual)",
                carrier = "Cualquiera",
                country = "ALL",
                sniSpoof = _customSni.value,
                payloadTemplate = "CONNECT [host_port] HTTP/1.1\r\nHost: [sni]\r\nConnection: Keep-Alive\r\n\r\n",
                transport = "SSL",
                recommendedGateway = "gw-aws-ohio-01",
                isActive = true
            )
        )
        _tweaks.value = defaultTweaks

        // Detección automática por operador SIM
        val detected = _carrierInfo.value
        val autoSelectedTweak = when {
            detected.isMovistar -> defaultTweaks.first { it.tweakId == "movistar_ws_portal" }
            detected.isClaro    -> defaultTweaks.first { it.tweakId == "claro_fastdns_udp" }
            else -> defaultTweaks.firstOrNull { it.tweakId == prefs.selectedTweakId } ?: defaultTweaks.first()
        }
        _selectedTweak.value = autoSelectedTweak
        prefs.selectedTweakId = autoSelectedTweak.tweakId

        // Log de diagnóstico
        TunnelLogManager.log("SIM detectada: ${detected.name} | Tweak auto: ${autoSelectedTweak.label}")
    }
}
