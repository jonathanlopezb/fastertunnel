package com.fastertunnel.app.core.vpn

object PayloadInjector {

    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Reemplaza las etiquetas dinámicas comunes de HTTP Injector / Hamara Tunnel:
     * [host], [port], [host_port], [sni], [crlf], [protocol], [ua]
     */
    fun formatPayload(
        template: String,
        host: String,
        port: Int,
        sni: String?
    ): String {
        val effectiveSni = if (!sni.isNullOrBlank()) sni else host
        val hostPort = "$host:$port"

        var result = template
            .replace("[host_port]", hostPort, ignoreCase = true)
            .replace("[host]", host, ignoreCase = true)
            .replace("[port]", port.toString(), ignoreCase = true)
            .replace("[sni]", effectiveSni, ignoreCase = true)
            .replace("[protocol]", "HTTP/1.1", ignoreCase = true)
            .replace("[ua]", DEFAULT_UA, ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("[cr]", "\r", ignoreCase = true)
            .replace("[lf]", "\n", ignoreCase = true)

        // Asegurar que termine en doble CRLF si no los tiene
        if (!result.endsWith("\r\n\r\n")) {
            result = result.trimEnd() + "\r\n\r\n"
        }

        return result
    }

    /**
     * Payload por defecto optimizado para Movistar con WebSocket / Cloudflare / WhatsApp
     */
    fun buildMovistarWsPayload(host: String, port: Int, sni: String): String {
        return "GET / HTTP/1.1\r\n" +
                "Host: $sni\r\n" +
                "X-Online-Host: $sni\r\n" +
                "X-Forwarded-For: $host\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "User-Agent: $DEFAULT_UA\r\n\r\n"
    }

    /**
     * Payload CONNECT estándar para túneles SSL/SSH
     */
    fun buildConnectPayload(host: String, port: Int, sni: String): String {
        return "CONNECT $host:$port HTTP/1.1\r\n" +
                "Host: $sni\r\n" +
                "X-Online-Host: $sni\r\n" +
                "Proxy-Connection: Keep-Alive\r\n" +
                "Connection: Keep-Alive\r\n" +
                "User-Agent: $DEFAULT_UA\r\n\r\n"
    }
}
