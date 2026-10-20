package com.fastertunnel.app.core.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import javax.net.ssl.SSLSocket

class TunnelConnectionBridge(
    private val vpnInterface: ParcelFileDescriptor,
    private val host: String,
    private val port: Int,
    private val transport: String,
    private val sni: String?,
    private val payloadTemplate: String?,
    // VpnService reference para protect() — excluye el socket del túnel de ser enrutado por la VPN misma
    private val vpnService: VpnService? = null
) {

    private var activeSocket: Socket? = null
    private var activeDatagramChannel: DatagramChannel? = null
    private var isRunning = false

    suspend fun execute() = withContext(Dispatchers.IO) {
        isRunning = true
        val effectiveSni = sni?.ifBlank { "mi.movistar.com.co" } ?: "mi.movistar.com.co"

        try {
            // Manejo de protocolo FastDNS / UDP Custom (Bypass para Claro y Movistar sin saldo)
            if (transport == "FAST_DNS" || transport == "DNS" || transport == "UDP_CUSTOM" || transport == "UDP") {
                val targetPort = if (port in listOf(53, 7300, 80)) port else 53
                TunnelLogManager.log("[FastDNS] Conectando a $host:$targetPort vía UDP (Bypass Claro/Movistar)...", isHighlight = true)
                val channel = DatagramChannel.open()
                channel.configureBlocking(true)
                try { vpnService?.protect(channel.socket()) } catch (_: Exception) {}
                channel.connect(InetSocketAddress(host, targetPort))
                activeDatagramChannel = channel
                TunnelLogManager.log("[FastDNS] Canal UDP $targetPort conectado exitosamente", isHighlight = true)
                TunnelLogManager.updateStatus(TunnelStatus.CONNECTED)
                bridgeTunAndUdp(vpnInterface.fileDescriptor, channel)
                return@withContext
            }

            TunnelLogManager.log("[TCP] Conectando a $host:$port...", isHighlight = true)

            // CRÍTICO: WSS en puerto 443 → TLS real
            //          WS / WSS en puerto 80 → TCP plano + HTTP Upgrade (NO TLS)
            // El error "Unable to parse TLS packet header" ocurre cuando se intenta
            // TLS en un socket que habla HTTP plano (puerto 80 del relay).
            val useTls = (transport == "SSL" || transport == "TLS") ||
                         (transport == "WSS" && port == 443)

            val socket: Socket = if (useTls) {
                TunnelLogManager.log("[SSL] Negociando TLS con SNI Spoof: $effectiveSni...")
                val rawSocket = Socket()
                try { vpnService?.protect(rawSocket) } catch (_: Exception) {}
                val sslSocket = SslSniSocketFactory.createSniSslSocketFromRaw(
                    rawSocket = rawSocket,
                    host = host,
                    port = port,
                    sniHost = effectiveSni,
                    timeoutMs = 12000
                )
                TunnelLogManager.log("[SSL] Apretón de manos TLS completado exitosamente", isHighlight = true)
                sslSocket
            } else {
                // WS en puerto 80 o cualquier TCP plano — sin TLS
                TunnelLogManager.log("[WS] Conectando TCP plano a $host:$port (sin TLS)...")
                val tcpSocket = Socket()
                try { vpnService?.protect(tcpSocket) } catch (_: Exception) {}
                tcpSocket.connect(InetSocketAddress(host, port), 10000)
                TunnelLogManager.log("[WS] Socket TCP conectado", isHighlight = true)
                tcpSocket
            }

            activeSocket = socket
            val outStream = socket.getOutputStream()
            val inStream = socket.getInputStream()

            // Inyección de Payload HTTP si aplica (WS, WSS, o plantilla custom)
            if (!payloadTemplate.isNullOrBlank() || transport == "WSS" || transport == "WS") {
                val formattedPayload = if (!payloadTemplate.isNullOrBlank()) {
                    PayloadInjector.formatPayload(payloadTemplate, host, port, effectiveSni)
                } else {
                    PayloadInjector.buildMovistarWsPayload(host, port, effectiveSni)
                }

                TunnelLogManager.log("[INJECT] Inyectando cabeceras HTTP...")
                TunnelLogManager.log("Payload: ${formattedPayload.lines().firstOrNull() ?: ""}")

                outStream.write(formattedPayload.toByteArray(Charsets.UTF_8))
                outStream.flush()

                // Leer respuesta HTTP inicial del proxy/gateway
                val reader = BufferedReader(InputStreamReader(inStream))
                val statusLine = reader.readLine()
                if (statusLine != null) {
                    TunnelLogManager.log("[RESPUESTA] $statusLine", isHighlight = true)
                    // Leer headers completos hasta línea vacía (para no dejar datos en el buffer)
                    var line = reader.readLine()
                    while (!line.isNullOrBlank()) {
                        line = reader.readLine()
                    }
                }
            }

            TunnelLogManager.log("[TÚNEL] Conexión establecida. Redirigiendo tráfico TUN...", isHighlight = true)
            TunnelLogManager.updateStatus(TunnelStatus.CONNECTED)

            // Puente bidireccional entre TUN y Socket
            bridgeTunAndSocket(vpnInterface.fileDescriptor, inStream, outStream)

        } catch (e: Exception) {
            if (e !is CancellationException) {
                TunnelLogManager.log("[ERROR] Fallo en túnel: ${e.message}", isError = true)
                // Modo resiliente: si el host remoto falla, mantener interfaz local para diagnóstico
                runFallbackLoop(vpnInterface.fileDescriptor)
            }
        } finally {
            close()
        }
    }

    private fun bridgeTunAndUdp(tunFd: FileDescriptor, channel: DatagramChannel) {
        val tunIn = FileInputStream(tunFd)
        val tunOut = FileOutputStream(tunFd)

        val uplinkThread = Thread {
            val buffer = ByteBuffer.allocate(32768)
            val readBuf = ByteArray(32768)
            try {
                while (isRunning) {
                    val read = tunIn.read(readBuf)
                    if (read > 0) {
                        buffer.clear()
                        buffer.put(readBuf, 0, read)
                        buffer.flip()
                        channel.write(buffer)
                    }
                }
            } catch (_: Exception) {}
        }

        val downlinkThread = Thread {
            val buffer = ByteBuffer.allocate(32768)
            try {
                while (isRunning) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read > 0) {
                        tunOut.write(buffer.array(), 0, read)
                    }
                }
            } catch (_: Exception) {}
        }

        uplinkThread.isDaemon = true
        downlinkThread.isDaemon = true
        uplinkThread.start()
        downlinkThread.start()

        uplinkThread.join()
        downlinkThread.join()
    }

    private fun bridgeTunAndSocket(
        tunFd: FileDescriptor,
        socketIn: InputStream,
        socketOut: OutputStream
    ) {
        val tunIn = FileInputStream(tunFd)
        val tunOut = FileOutputStream(tunFd)

        val buffer = ByteArray(32768)

        // Hilo TUN -> Socket
        val uplinkThread = Thread {
            try {
                while (isRunning) {
                    val read = tunIn.read(buffer)
                    if (read > 0) {
                        socketOut.write(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {}
        }

        // Hilo Socket -> TUN
        val downlinkThread = Thread {
            try {
                while (isRunning) {
                    val read = socketIn.read(buffer)
                    if (read > 0) {
                        tunOut.write(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {}
        }

        uplinkThread.isDaemon = true
        downlinkThread.isDaemon = true
        uplinkThread.start()
        downlinkThread.start()

        uplinkThread.join()
        downlinkThread.join()
    }

    private fun runFallbackLoop(tunFd: FileDescriptor) {
        try {
            val inStream = FileInputStream(tunFd)
            val buffer = ByteArray(4096)
            while (isRunning) {
                inStream.read(buffer)
            }
        } catch (_: Exception) {}
    }

    fun close() {
        isRunning = false
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            activeDatagramChannel?.close()
        } catch (_: Exception) {}
        activeSocket = null
        activeDatagramChannel = null
    }
}
