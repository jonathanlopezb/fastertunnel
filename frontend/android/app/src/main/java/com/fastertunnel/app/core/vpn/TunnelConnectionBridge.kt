package com.fastertunnel.app.core.vpn

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
import javax.net.ssl.SSLSocket

class TunnelConnectionBridge(
    private val vpnInterface: ParcelFileDescriptor,
    private val host: String,
    private val port: Int,
    private val transport: String,
    private val sni: String?,
    private val payloadTemplate: String?
) {

    private var activeSocket: Socket? = null
    private var isRunning = false

    suspend fun execute() = withContext(Dispatchers.IO) {
        isRunning = true
        val effectiveSni = sni?.ifBlank { "web.whatsapp.net" } ?: "web.whatsapp.net"

        try {
            TunnelLogManager.log("[DNS] Resolviendo gateway: $host...", isHighlight = true)

            val socket: Socket = when (transport) {
                "SSL", "TLS", "WSS" -> {
                    TunnelLogManager.log("[SSL] Negociando TLS con SNI Spoof: $effectiveSni...")
                    val sslSocket = SslSniSocketFactory.createSniSslSocket(
                        host = host,
                        port = port,
                        sniHost = effectiveSni,
                        timeoutMs = 12000
                    )
                    TunnelLogManager.log("[SSL] Apretón de manos TLS completado exitosamente", isHighlight = true)
                    sslSocket
                }
                else -> {
                    TunnelLogManager.log("[TCP] Conectando a $host:$port...")
                    val tcpSocket = Socket()
                    tcpSocket.connect(InetSocketAddress(host, port), 10000)
                    tcpSocket
                }
            }

            activeSocket = socket
            val outStream = socket.getOutputStream()
            val inStream = socket.getInputStream()

            // Inyección de Payload HTTP si aplica
            if (!payloadTemplate.isNullOrBlank() || transport == "WSS") {
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
        activeSocket = null
    }
}
