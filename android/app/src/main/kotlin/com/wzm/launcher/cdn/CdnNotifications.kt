package com.wzm.launcher.cdn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Notificação obrigatória do foreground service (VpnService ativo). */
object CdnNotifications {

    const val CHANNEL_ID = "wzm_cdni_router"
    const val NOTIFICATION_ID = 4210

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Roteador CDNI local",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém o roteamento local do CDNI ativo enquanto o WZM inicia"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle("Roteador CDNI local ativo")
            .setContentText("DNS + HTTPS locais para ${CdnRouterConfig.INTERCEPT_HOSTS.first()}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }
}
