package com.margelo.nitro.locationsession.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.margelo.nitro.locationsession.core.SessionConfig

class LocationTrackingService : Service() {
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(PLACEHOLDER_CHANNEL_NAME)
        val placeholder = notification(PLACEHOLDER_TITLE, null)
        promoted = promote(placeholder)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SessionEngine.serviceStartDelivered()
        if (intent == null) {
            ElsLog.info("service restarted by the system")
            SessionEngine.resumeIfNeeded(this)
        }
        val c = SessionEngine.activeConfig
        if (c == null || !promoted) {
            if (c == null) ElsLog.info("service has no active session; stopping")
            if (promoted && promote(notification(PLACEHOLDER_TITLE, null))) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        showConfigured(c)
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showConfigured(c: SessionConfig) {
        ensureChannel(c.notificationChannelName)
        promote(notification(c.notificationTitle, c.notificationText))
    }

    private fun promote(n: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        true
    } catch (e: Exception) {
        ElsLog.info("startForeground failed: $e")
        false
    }

    private fun ensureChannel(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, name.ifBlank { PLACEHOLDER_CHANNEL_NAME }, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun notification(title: String, text: String?): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "expo_location_session"
        private const val NOTIFICATION_ID = 0x454c53
        private const val PLACEHOLDER_CHANNEL_NAME = "Location"
        private const val PLACEHOLDER_TITLE = "Location sharing"
    }
}
