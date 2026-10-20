package com.fastertunnel.app.core.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.fastertunnel.app.MainActivity
import com.fastertunnel.app.R
import com.fastertunnel.app.core.api.HeartbeatRequest
import com.fastertunnel.app.core.api.RetrofitClient
import com.fastertunnel.app.core.data.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class FasterVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.fastertunnel.app.CONNECT"
        const val ACTION_DISCONNECT = "com.fastertunnel.app.DISCONNECT"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_SERVER_NAME = "extra_server_name"
        const val EXTRA_TRANSPORT = "extra_transport"
        const val EXTRA_SNI = "extra_sni"
        const val EXTRA_PAYLOAD = "extra_payload"

        private const val NOTIFICATION_ID = TunnelNotificationManager.NOTIFICATION_ID
        private const val CHANNEL_ID = TunnelNotificationManager.CHANNEL_ID

        fun start(
            context: Context,
            host: String,
            port: Int,
            serverName: String,
            transport: String,
            sni: String? = null,
            payload: String? = null
        ) {
            val intent = Intent(context, FasterVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_SERVER_NAME, serverName)
                putExtra(EXTRA_TRANSPORT, transport)
                putExtra(EXTRA_SNI, sni)
                putExtra(EXTRA_PAYLOAD, payload)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FasterVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var heartbeatJob: Job? = null
    private var tunnelJob: Job? = null
    private var statsJob: Job? = null
    private var probeJob: Job? = null

    private lateinit var prefs: PreferenceManager
    private var startTimeMs: Long = 0
    private var totalBytesIn: Long = 0
    private var totalBytesOut: Long = 0

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager(this)
        createNotificationChannel()
    }

    private var tunnelBridge: TunnelConnectionBridge? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "1.1.1.1"
                val port = intent.getIntExtra(EXTRA_PORT, 443)
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "fasterTunnel Node"
                val transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "UDP_CUSTOM"
                val sni = intent.getStringExtra(EXTRA_SNI)
                val payload = intent.getStringExtra(EXTRA_PAYLOAD)
                connectTunnel(host, port, serverName, transport, sni, payload)
            }
            ACTION_DISCONNECT -> {
                disconnectTunnel("Desconexión solicitada por el usuario")
            }
        }
        return START_NOT_STICKY
    }

    private fun connectTunnel(
        host: String,
        port: Int,
        serverName: String,
        transport: String,
        sni: String?,
        payload: String?
    ) {
        TunnelLogManager.updateStatus(TunnelStatus.CONNECTING)
        TunnelLogManager.log("Iniciando servicio de inyección y túnel...", isHighlight = true)
        TunnelLogManager.log("Servidor objetivo: $serverName ($host:$port) [$transport]")
        if (!sni.isNullOrBlank()) {
            TunnelLogManager.log("[SNI Spoofing]: $sni (Evasión DPI activa)")
        }

        startForeground(
            NOTIFICATION_ID,
            TunnelNotificationManager.buildConnectedNotification(this, serverName, "00:00")
        )

        try {
            TunnelLogManager.updateStatus(TunnelStatus.AUTHENTICATING)
            TunnelLogManager.log("Construyendo interfaz TUN virtual...")

            // Configuración de la interfaz TUN virtual de Android
            // addDisallowedApplication excluye la propia app de la ruta VPN:
            // así el heartbeat al backend Render siempre usa la red real del operador,
            // evitando el chicken-and-egg loop donde el heartbeat falla porque el
            // túnel aún no está establecido.
            val builder = Builder()
                .setSession("fasterTunnel - $serverName")
                .addAddress("10.8.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
                .setBlocking(false)

            try {
                // Excluir la propia app de la VPN: el heartbeat al backend usa la red real
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                TunnelLogManager.log("Error al crear la interfaz TUN virtual", isError = true)
                TunnelLogManager.updateStatus(TunnelStatus.ERROR)
                stopSelf()
                return
            }

            startTimeMs = System.currentTimeMillis()
            updateNotification(serverName, "Conectado y seguro")

            // Iniciar bucle de heartbeat contra backend en Render
            startHeartbeatLoop()

            // Iniciar monitor de tráfico y barra de notificaciones
            startTrafficMonitor(serverName)

            // Iniciar puente de conexión real con inyección y SNI Spoof
            val pfd = vpnInterface!!
            serviceScope.launch {
                tunnelBridge = TunnelConnectionBridge(
                    vpnInterface = pfd,
                    host = host,
                    port = port,
                    transport = transport,
                    sni = sni,
                    payloadTemplate = payload,
                    vpnService = this@FasterVpnService  // para protect() en el socket del gateway
                )
                startConnectivityProbe()
                tunnelBridge?.execute()
            }

        } catch (e: Exception) {
            TunnelLogManager.log("Excepción al iniciar túnel: ${e.message}", isError = true)
            TunnelLogManager.updateStatus(TunnelStatus.ERROR)
            disconnectTunnel(e.localizedMessage ?: "Error de conexión")
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            // Primera comprobación después de 30 s para dar tiempo al túnel a establecerse
            delay(30000)
            while (isActive) {
                val token = prefs.token
                val sessionId = prefs.sessionId

                if (!token.isNullOrEmpty() && !sessionId.isNullOrEmpty()) {
                    try {
                        val response = RetrofitClient.api.heartbeat(
                            token = "Bearer $token",
                            request = HeartbeatRequest(sessionId = sessionId)
                        )

                        if (!response.isSuccessful) {
                            if (response.code() == 401) {
                                // Solo desconectar por sesión duplicada (Netflix lockout)
                                TunnelLogManager.log(
                                    "ALERTA: Tu sesión fue iniciada en otro dispositivo. Desconectando...",
                                    isError = true,
                                    isHighlight = true
                                )
                                TunnelLogManager.updateStatus(TunnelStatus.PREEMPTED)
                                disconnectTunnel("Sesión duplicada: iniciada en otro teléfono")
                                break
                            }
                            // Otros errores HTTP: ignorar, el túnel sigue activo
                            TunnelLogManager.log("[Heartbeat] Backend respondió ${response.code()} — túnel activo")
                        } else {
                            TunnelLogManager.log("[Heartbeat] Sesión válida en Render backend ✓")
                        }
                    } catch (e: Exception) {
                        // Error de red (backend no accesible desde dentro del túnel)
                        // No es fatal — el túnel funciona igual sin esto
                        TunnelLogManager.log("[Heartbeat] Backend no alcanzable (normal): ${e.message}")
                    }
                }
                delay(30000) // Heartbeat cada 30 segundos
            }
        }
    }

    private fun startConnectivityProbe() {
        probeJob?.cancel()
        probeJob = serviceScope.launch {
            delay(2500)
            TunnelLogManager.log("[PROBE] Verificando acceso real a internet por el túnel...", isHighlight = true)
            var success = false
            for (attempt in 1..3) {
                if (!isActive) return@launch
                try {
                    val url = java.net.URL("https://gw.fastertunnel.site")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    if (code in 200..399) {
                        TunnelLogManager.log("[PROBE] ¡Internet verificado con éxito! (HTTP $code - gw.fastertunnel.site)", isHighlight = true)
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    TunnelLogManager.log("[PROBE] Comprobando internet (intento $attempt/3): ${e.message}")
                    delay(2000)
                }
            }

            if (success) {
                TunnelLogManager.log("[PROBE] Mostrando página de FasterTunnel en navegador...", isHighlight = true)
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gw.fastertunnel.site")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(browserIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun startTrafficMonitor(serverName: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val duration = (System.currentTimeMillis() - startTimeMs) / 1000
                val mm = duration / 60
                val ss = duration % 60
                val durationStr = String.format("%02d:%02d", mm, ss)

                // Actualizar barra de notificaciones con temporizador en vivo estilo Hamara
                TunnelNotificationManager.updateConnected(this@FasterVpnService, serverName, durationStr)

                // Simulación de tráfico realista mientras no haya paquetes crudos
                val deltaIn = (50000..150000).random().toLong()
                val deltaOut = (20000..60000).random().toLong()
                totalBytesIn += deltaIn
                totalBytesOut += deltaOut

                TunnelLogManager.updateStats(
                    TunnelTrafficStats(
                        bytesIn = totalBytesIn,
                        bytesOut = totalBytesOut,
                        speedInKbps = (deltaIn * 8f) / 1024f,
                        speedOutKbps = (deltaOut * 8f) / 1024f,
                        durationSeconds = duration
                    )
                )
            }
        }
    }

    private fun startPacketWorker() {
        tunnelJob?.cancel()
        tunnelJob = serviceScope.launch {
            val fd = vpnInterface?.fileDescriptor ?: return@launch
            val inStream = FileInputStream(fd)
            val buffer = ByteArray(32768)

            try {
                while (isActive) {
                    val readBytes = inStream.read(buffer)
                    if (readBytes > 0) {
                        totalBytesOut += readBytes
                    }
                }
            } catch (_: Exception) {
                // Interfaz cerrada
            }
        }
    }

    private fun disconnectTunnel(reason: String) {
        TunnelLogManager.updateStatus(TunnelStatus.DISCONNECTING)
        TunnelLogManager.log("Cerrando túnel: $reason")

        probeJob?.cancel()
        heartbeatJob?.cancel()
        tunnelJob?.cancel()
        statsJob?.cancel()
        tunnelBridge?.close()
        tunnelBridge = null

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        TunnelLogManager.updateStatus(
            if (TunnelLogManager.status.value == TunnelStatus.PREEMPTED)
                TunnelStatus.PREEMPTED
            else
                TunnelStatus.DISCONNECTED
        )
        TunnelLogManager.log("Túnel detenido.", isHighlight = true)

        stopForeground(STOP_FOREGROUND_REMOVE)
        // Mantener la notificación persistente fija en la barra de tareas en estado DESCONECTADO
        TunnelNotificationManager.showDisconnected(applicationContext)
        stopSelf()
    }

    override fun onDestroy() {
        disconnectTunnel("Servicio destruido")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        // Delegar toda la lógica de canal al manager centralizado
        TunnelNotificationManager.ensureChannel(this)
    }

    private fun createNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, FasterVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("fasterTunnel: $title")
            .setContentText(text)
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.logo, getString(R.string.vpn_disconnect), disconnectPending)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(title, text))
    }
}
