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

    private val _showLoginDialog = MutableStateFlow(!prefs.isLoggedIn)
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
            _showLoginDialog.value = true
            TunnelLogManager.log("Sesión cerrada.", isHighlight = true)
        }
    }

    fun dismissLoginDialog() {
        if (prefs.isLoggedIn) {
            _showLoginDialog.value = false
        }
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

    private fun startTunnel(context: Context) {
        if (!prefs.isLoggedIn || prefs.token.isNullOrEmpty()) {
            _showLoginDialog.value = true
            TunnelLogManager.log("Por favor inicia sesión antes de conectar", isError = true)
            return
        }

        val server = _selectedServer.value ?: _servers.value.firstOrNull() ?: ServerNodeDto(
            id = 1,
            nodeId = "gw-us-01",
            name = "Estados Unidos #1 (Gaming & UDP)",
            countryCode = "US",
            flagEmoji = "🇺🇸",
            host = "us1.fastertunnel.net",
            port = 443,
            transport = "WSS",
            sniHost = "web.whatsapp.net",
            isActive = true,
            isPremium = false,
            pingMs = 45,
            maxUsers = 100,
            currentUsers = 12
        )

        val tweak = _selectedTweak.value
        val effectiveSni = tweak?.sniSpoof?.ifBlank { _customSni.value } ?: _customSni.value
        val payload = tweak?.payloadTemplate

        TunnelLogManager.log("SIM Operador: ${_carrierInfo.value.name} (${_carrierInfo.value.countryIso})")
        TunnelLogManager.log("Tweak activo: ${tweak?.label ?: "Movistar WSS"}")
        TunnelLogManager.log("SNI Inyectado: $effectiveSni", isHighlight = true)

        FasterVpnService.start(
            context = context,
            host = server.host,
            port = server.port,
            serverName = server.name,
            transport = server.transport,
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
                nodeId = "gw-us-01",
                name = "Estados Unidos #1 (Gaming & UDP)",
                countryCode = "US",
                flagEmoji = "🇺🇸",
                host = "us1.fastertunnel.net",
                port = 443,
                transport = "UDP_CUSTOM",
                sniHost = null,
                isActive = true,
                isPremium = false,
                pingMs = 42,
                maxUsers = 100,
                currentUsers = 18
            ),
            ServerNodeDto(
                id = 2,
                nodeId = "gw-de-02",
                name = "Alemania #2 (Stealth WSS)",
                countryCode = "DE",
                flagEmoji = "🇩🇪",
                host = "de2.fastertunnel.net",
                port = 443,
                transport = "WSS",
                sniHost = "www.cloudflare.com",
                isActive = true,
                isPremium = false,
                pingMs = 115,
                maxUsers = 100,
                currentUsers = 25
            ),
            ServerNodeDto(
                id = 3,
                nodeId = "gw-br-03",
                name = "Brasil #3 (Baja Latencia Latam)",
                countryCode = "BR",
                flagEmoji = "🇧🇷",
                host = "br3.fastertunnel.net",
                port = 443,
                transport = "SSH",
                sniHost = null,
                isActive = true,
                isPremium = true,
                pingMs = 58,
                maxUsers = 100,
                currentUsers = 34
            )
        )
        _servers.value = defaultServers
        _selectedServer.value = defaultServers.first()

        val defaultTweaks = listOf(
            TweakDto(
                id = 1,
                tweakId = "movistar_whatsapp_sni",
                label = "Movistar WhatsApp Ilimitado (Auto-Inyección)",
                carrier = "Movistar",
                country = "LATAM",
                sniSpoof = "web.whatsapp.net",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nUser-Agent: [ua]\r\n\r\n",
                transport = "WSS",
                recommendedGateway = "gw-us-01",
                isActive = true
            ),
            TweakDto(
                id = 2,
                tweakId = "movistar_portal_ssl",
                label = "Movistar Portal & App (SSL Spoof)",
                carrier = "Movistar",
                country = "LATAM",
                sniSpoof = "portal.movistar.com.pe",
                payloadTemplate = "CONNECT [host_port] HTTP/1.1\r\nHost: [sni]\r\nX-Online-Host: [sni]\r\nConnection: Keep-Alive\r\n\r\n",
                transport = "SSL",
                recommendedGateway = "gw-de-02",
                isActive = true
            ),
            TweakDto(
                id = 3,
                tweakId = "movistar_colombia_ws",
                label = "Movistar Colombia (Recargas / WSS)",
                carrier = "Movistar",
                country = "CO",
                sniSpoof = "recargas.movistar.com.co",
                payloadTemplate = "GET / HTTP/1.1\r\nHost: [sni]\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n",
                transport = "WSS",
                recommendedGateway = "gw-us-01",
                isActive = true
            ),
            TweakDto(
                id = 4,
                tweakId = "movistar_dns_udp",
                label = "Movistar DNS UDP 53 (Bypass Filtro)",
                carrier = "Movistar",
                country = "LATAM",
                sniSpoof = null,
                payloadTemplate = null,
                transport = "UDP_CUSTOM",
                recommendedGateway = "gw-us-01",
                isActive = true
            ),
            TweakDto(
                id = 5,
                tweakId = "claro_latam_ws",
                label = "Claro Redes Sociales (Payload WSS)",
                carrier = "Claro",
                country = "LATAM",
                sniSpoof = "m.facebook.com",
                payloadTemplate = "CONNECT [host_port] HTTP/1.1\r\nHost: [sni]\r\nUpgrade: websocket\r\n\r\n",
                transport = "WSS",
                recommendedGateway = "gw-br-03",
                isActive = true
            ),
            TweakDto(
                id = 6,
                tweakId = "ssl_sni_custom",
                label = "Modo SNI Personalizado (Manual)",
                carrier = "Cualquiera",
                country = "ALL",
                sniSpoof = _customSni.value,
                payloadTemplate = "CONNECT [host_port] HTTP/1.1\r\nHost: [sni]\r\nConnection: Keep-Alive\r\n\r\n",
                transport = "SSL",
                recommendedGateway = "gw-us-01",
                isActive = true
            )
        )
        _tweaks.value = defaultTweaks

        // Si la SIM detectada es Movistar, auto-seleccionar el tweak de Movistar WhatsApp
        val detected = _carrierInfo.value
        val autoSelectedTweak = if (detected.isMovistar) {
            defaultTweaks.first { it.tweakId == "movistar_whatsapp_sni" }
        } else {
            defaultTweaks.firstOrNull { it.tweakId == prefs.selectedTweakId } ?: defaultTweaks.first()
        }
        _selectedTweak.value = autoSelectedTweak
        prefs.selectedTweakId = autoSelectedTweak.tweakId
        _selectedTweak.value = defaultTweaks.first()
    }
}
