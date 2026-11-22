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
        isAlive = true
        ensureChannel(PLACEHOLDER_CHANNEL_NAME)
        val placeholder = notification(PLACEHOLDER_TITLE, null)
        promoted = promote(placeholder)
        isForeground = promoted
        ElsLog.debug("service", "service created", mapOf("promoted" to promoted), level = "info")
        if (promoted) LaunchGate.onPromoted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLogger.launched(if (intent == null) "stickyRestart" else "serviceStart")
        LaunchGate.serviceStartDelivered()
        ElsLog.debug(
            "service",
            "service start command",
            mapOf("intent" to (intent != null), "flags" to flags, "startId" to startId, "promoted" to promoted),
            level = "info"
        )
        if (intent == null) {
            ElsLog.info("service", "service restarted by the system")
            SessionEngine.resumeIfNeeded(this)
        }
        val c = SessionEngine.activeConfig
        if (!promoted) {
            promoted = promote(notification(PLACEHOLDER_TITLE, null))
            isForeground = promoted
            if (promoted) LaunchGate.onPromoted(this)
        }
        if (c == null || !promoted) {
            if (c == null) ElsLog.info("service", "service has no active session; stopping") else ElsLog.info("service", "service not promoted; stopping", level = "warn")
            if (promoted && promote(notification(PLACEHOLDER_TITLE, null))) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            isForeground = false
            stopSelf()
            return START_NOT_STICKY
        }
        showConfigured(c)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ElsLog.debug(
            "service",
            "task removed",
            mapOf("sessionActive" to SessionEngine.isActive, "foreground" to isForeground),
            level = "info"
        )
        if (SessionEngine.isActive && !isForeground) {
            ElsLog.info("service", "task removed while the service is not foreground; asking the gate to restart")
            LaunchGate.restart(this, "taskRemoved")
        }
    }

    override fun onDestroy() {
        ElsLog.debug(
            "service",
            "service destroyed",
            mapOf("sessionActive" to SessionEngine.isActive, "foreground" to isForeground),
            level = if (SessionEngine.isActive) "warn" else "info"
        )
        isForeground = false
        isAlive = false
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
        ElsLog.info("service", "startForeground failed: ${e.javaClass.simpleName}", mapOf("error" to e.toString()), level = "warn")
        false
    }

    private fun ensureChannel(name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, name.ifBlank { PLACEHOLDER_CHANNEL_NAME }, NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun smallIcon(): Int =
        resources.getIdentifier(ICON_NAME, "drawable", packageName).takeIf { it != 0 }
            ?: android.R.drawable.ic_menu_mylocation

    private fun notification(title: String, text: String?): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon())
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
        @Volatile
        var isAlive = false
            private set
        @Volatile
        var isForeground = false
            private set
        const val CHANNEL_ID = "expo_location_session"
        private const val NOTIFICATION_ID = 0x454c53
        private const val ICON_NAME = "expo_location_session_notification_icon"
        private const val PLACEHOLDER_CHANNEL_NAME = "Location"
        private const val PLACEHOLDER_TITLE = "Location sharing"
    }
}
