package com.fastertunnel.app.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.fastertunnel.app.MainActivity
import com.fastertunnel.app.core.data.PreferenceManager

class VpnActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOTIFICATION_CONNECT = "com.fastertunnel.app.ACTION_NOTIFICATION_CONNECT"
        const val ACTION_NOTIFICATION_DISCONNECT = "com.fastertunnel.app.ACTION_NOTIFICATION_DISCONNECT"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_NOTIFICATION_CONNECT -> {
                // Si el permiso VPN ya fue concedido previamente, iniciar de inmediato
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent == null) {
                    val prefs = PreferenceManager(context)
                    val serverHost = prefs.selectedServerHost ?: "3.142.187.253"
                    val serverPort = prefs.selectedServerPort
                    val serverName = prefs.selectedServerName ?: "AWS Ohio #1 (FastDNS)"
                    val transport = prefs.selectedTransport ?: "FAST_DNS"
                    val sni = prefs.customSni.ifBlank { "portalrecargas.claro.com.co" }

                    FasterVpnService.start(
                        context = context,
                        host = serverHost,
                        port = if (serverPort > 0) serverPort else 53,
                        serverName = serverName,
                        transport = transport,
                        sni = sni,
                        payload = ""
                    )
                } else {
                    // Abrir la actividad para solicitar permiso
                    val openAppIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("auto_connect", true)
                    }
                    context.startActivity(openAppIntent)
                }
            }

            ACTION_NOTIFICATION_DISCONNECT -> {
                FasterVpnService.stop(context)
            }
        }
    }
}
