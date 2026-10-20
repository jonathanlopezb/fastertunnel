package com.fastertunnel.app.core.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fastertunnel.app.MainActivity
import com.fastertunnel.app.R

object TunnelNotificationManager {

    const val NOTIFICATION_ID = 1001
    // IMPORTANTE: cambiamos el ID del canal para forzar recreación con IMPORTANCE_HIGH.
    // Android no permite cambiar la importancia de un canal existente — solo recreándolo
    // con un ID nuevo se aplica la nueva configuración.
    const val CHANNEL_ID = "ft_vpn_v3"
    private const val CHANNEL_NAME = "FasterTunnel VPN"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Eliminar canales viejos si existen
            try { manager.deleteNotificationChannel("fastertunnel_vpn_channel") } catch (_: Exception) {}
            try { manager.deleteNotificationChannel("ft_vpn_v2") } catch (_: Exception) {}

            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW  // LOW = sin sonido pero siempre visible en VPN
                ).apply {
                    description = "Estado activo del túnel FasterTunnel"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * Muestra la notificación en estado DESCONECTADO permanente en la barra de estado,
     * con botón directo para [CONECTAR].
     */
    fun showDisconnected(context: Context) {
        ensureChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val connectIntent = Intent(context, VpnActionReceiver::class.java).apply {
            action = VpnActionReceiver.ACTION_NOTIFICATION_CONNECT
        }
        val connectPending = PendingIntent.getBroadcast(
            context,
            1,
            connectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("FasterTunnel")
            .setContentText("Desconectado • Toca para conectar")
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.logo, "⚡ CONECTAR", connectPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Construye la notificación en estado CONECTADO para startForeground y actualizaciones cada segundo.
     */
    fun buildConnectedNotification(
        context: Context,
        serverName: String,
        durationFormatted: String
    ): Notification {
        ensureChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(context, VpnActionReceiver::class.java).apply {
            action = VpnActionReceiver.ACTION_NOTIFICATION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getBroadcast(
            context,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("FasterTunnel  $durationFormatted")
            .setContentText("Conectado a $serverName")
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.logo, "✕ DESCONECTAR", disconnectPending)
            .build()
    }

    fun updateConnected(context: Context, serverName: String, durationFormatted: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildConnectedNotification(context, serverName, durationFormatted))
    }
}
