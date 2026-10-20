package com.fastertunnel.app.core.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SslSniSocketFactory {

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    })

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }

    /**
     * Abre un socket TCP y realiza el apretón de manos SSL/TLS inyectando el SNI personalizado
     * (e.g. web.whatsapp.net o portal.movistar.com.pe) para burlar la inspección DPI de Movistar.
     */
    fun createSniSslSocket(
        host: String,
        port: Int,
        sniHost: String,
        timeoutMs: Int = 10000
    ): SSLSocket {
        val rawSocket = Socket()
        rawSocket.connect(InetSocketAddress(host, port), timeoutMs)

        val sslSocket = sslContext.socketFactory.createSocket(
            rawSocket,
            sniHost,
            port,
            true
        ) as SSLSocket

        // Inyectar SNI en los parámetros SSL
        val params = sslSocket.sslParameters
        try {
            params.serverNames = listOf(SNIHostName(sniHost))
            sslSocket.sslParameters = params
        } catch (_: Exception) {}

        sslSocket.useClientMode = true
        sslSocket.startHandshake()

        return sslSocket
    }
}
