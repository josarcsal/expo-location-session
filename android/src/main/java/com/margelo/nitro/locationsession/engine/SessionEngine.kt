package com.margelo.nitro.locationsession.engine

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import com.margelo.nitro.locationsession.core.acceptFix
import com.margelo.nitro.locationsession.core.stopDecision
import com.margelo.nitro.locationsession.core.validate
import org.json.JSONObject

class LocationSessionException(val code: String, message: String) : RuntimeException("$code: $message")

data class EngineState(
    val running: Boolean,
    val sessionId: String? = null,
    val startedAtMs: Long? = null,
    val expiresAtMs: Long? = null,
    val lastFix: Fix? = null,
    val lastUploadAtMs: Long? = null,
    val survivesProcessDeath: Boolean = false,
    val lastStop: LastStop? = null
)

object SessionEngine {
    val events = EventBus()

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private lateinit var store: SessionStore
    private lateinit var uploader: Uploader

    private var config: SessionConfig? = null
    private var startedAtMs = 0L
    private var lastFix: Fix? = null
    private var stopping = false
    private var expiryRunnable: Runnable? = null

    val activeConfig: SessionConfig? get() = config
    val isActive: Boolean get() = config != null
    val lastKnownFix: Fix? get() = lastFix

    private fun init(ctx: Context): Context {
        assertMain()
        app?.let { return it }
        val context = ctx.applicationContext
        app = context
        store = SessionStore(context)
        uploader = Uploader(context, store, events)
        (context as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumeIfNeeded(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return context
    }

    fun start(ctx: Context, c: SessionConfig) {
        val context = init(ctx)
        resumeIfNeeded(context)
        if (!isAppInForeground(context)) {
            throw LocationSessionException("NOT_FOREGROUND", "start must be called while the app is in the foreground")
        }
        if (!Permissions.hasForeground(context)) {
            throw LocationSessionException("PERMISSION_DENIED", "Location permission is not granted")
        }
        val now = nowMs()
        validate(c, now, config != null, Permissions.notificationsGranted(context))?.let {
            throw LocationSessionException(it, validationMessage(it))
        }
        if (!store.save(c, now)) {
            store.wipe()
            throw LocationSessionException("INTERNAL", "Could not persist the session")
        }
        try {
            activate(context, c, now, null, null)
        } catch (e: Exception) {
            teardown(context, c, null)
            throw LocationSessionException("INTERNAL", "Could not start the session: $e")
        }
        ElsLog.info("started session ${c.id}")
    }

    fun stop(ctx: Context, reason: StopReason) {
        val context = init(ctx)
        val c = config ?: (store.load() as? StoredSession.Session)?.config ?: return
        teardown(context, c, reason)
    }

    fun state(ctx: Context): EngineState {
        val context = init(ctx)
        resumeIfNeeded(context)
        val c = config ?: return EngineState(running = false, lastStop = store.lastStop)
        return EngineState(
            running = true,
            sessionId = c.id,
            startedAtMs = startedAtMs,
            expiresAtMs = c.expiresAtMs,
            lastFix = lastFix,
            lastUploadAtMs = uploader.lastSuccessMs,
            survivesProcessDeath = Permissions.hasBackground(context)
        )
    }

    fun onFixes(ctx: Context, fixes: List<Fix>) = onFixes(ctx, fixes) {}

    fun onFixes(ctx: Context, fixes: List<Fix>, settled: () -> Unit) {
        val context = init(ctx)
        resumeIfNeeded(context)
        if (config == null) {
            ElsLog.info("location delivery without an active session; removing updates")
            removeUpdates(context)
            settled()
            return
        }
        for (raw in fixes) {
            val c = config ?: break
            if (stopping) break
            val fix = sanitized(raw) ?: continue
            if (nowMs() >= c.expiresAtMs || fix.timestampMs >= c.expiresAtMs) {
                evaluate(context, null)
                break
            }
            if (!acceptFix(fix, startedAtMs)) continue
            uploader.put(fix)
            lastFix = fix
            events.emit("location", locationPayload(fix))
            evaluate(context, fix)
        }
        uploader.whenSettled(settled)
    }

    fun onExpiryAlarm(ctx: Context, settled: () -> Unit) {
        val context = init(ctx)
        resumeIfNeeded(context)
        val c = config
        if (c == null) {
            settled()
            return
        }
        if (nowMs() < c.expiresAtMs) scheduleExpiry(context, c)
        evaluate(context, null)
        uploader.whenSettled(settled)
    }

    fun resumeIfNeeded(ctx: Context) {
        val context = init(ctx)
        if (config != null) return
        when (val stored = store.load()) {
            StoredSession.None -> return
            StoredSession.Corrupt -> {
                ElsLog.info("discarding unreadable persisted session")
                store.wipe()
                removeUpdates(context)
                cancelAlarm(context)
                LaunchGate.clear(context)
                stopService(context)
            }
            is StoredSession.Session -> {
                val c = stored.config
                when {
                    nowMs() >= c.expiresAtMs -> teardown(context, c, StopReason.EXPIRED)
                    !Permissions.hasForeground(context) -> teardown(context, c, StopReason.PERMISSION_REVOKED)
                    !Permissions.hasBackground(context) && !isAppInForeground(context) -> {
                        removeUpdates(context)
                        stopService(context)
                        scheduleAlarm(context, c)
                        ElsLog.info("session ${c.id} paused until the app is opened (no background location)")
                    }
                    else -> {
                        activate(context, c, stored.startedAtMs, store.pendingFix, store.lastUploadAtMs)
                        ElsLog.info("resumed session ${c.id}")
                        uploader.pump()
                    }
                }
            }
        }
    }

    private fun activate(ctx: Context, c: SessionConfig, startedAt: Long, pending: Fix?, lastUploadAt: Long?) {
        config = c
        startedAtMs = startedAt
        lastFix = pending?.let(::sanitized)
        stopping = false
        uploader.onStop = { reason -> stop(ctx, reason) }
        uploader.begin(c, lastFix, lastUploadAt)
        startService(ctx)
        requestUpdates(ctx, c)
        scheduleExpiry(ctx, c)
    }

    private fun teardown(ctx: Context, c: SessionConfig, reason: StopReason?) {
        expiryRunnable?.let { main.removeCallbacks(it) }
        expiryRunnable = null
        cancelAlarm(ctx)
        LaunchGate.clear(ctx)
        removeUpdates(ctx)
        stopService(ctx)
        uploader.reset()
        store.wipe()
        config = null
        lastFix = null
        stopping = false
        if (reason == null) {
            ElsLog.info("discarded session ${c.id} after a failed start")
            return
        }
        store.lastStop = LastStop(reason, nowMs(), c.id)
        ElsLog.info("stopped session ${c.id} reason=${reason.wire}")
        events.emit("stop", JSONObject().put("reason", reason.wire).put("sessionId", c.id))
    }

    private fun evaluate(ctx: Context, fix: Fix?) {
        val c = config ?: return
        if (stopping) return
        val reason = stopDecision(c, fix, nowMs())
        if (reason == null) {
            if (fix != null) uploader.pump()
            return
        }
        stopping = true
        uploader.flush { stop(ctx, reason) }
    }

    private fun scheduleExpiry(ctx: Context, c: SessionConfig) {
        expiryRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            expiryRunnable = null
            evaluate(ctx, null)
        }
        expiryRunnable = r
        main.postDelayed(r, maxOf(0L, c.expiresAtMs - nowMs()))
        scheduleAlarm(ctx, c)
    }

    private fun alarmIntent(ctx: Context): PendingIntent = LaunchGate.pendingIntent(ctx, LaunchGate.Kind.EXPIRY)

    private fun scheduleAlarm(ctx: Context, c: SessionConfig) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.expiresAtMs, alarmIntent(ctx))
    }

    private fun cancelAlarm(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
    }

    private fun updatesIntent(ctx: Context): PendingIntent = LaunchGate.pendingIntent(ctx, LaunchGate.Kind.LOCATION)

    private fun requestUpdates(ctx: Context, c: SessionConfig) {
        val priority = when (c.accuracy) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = LocationRequest.Builder(priority, 10_000)
            .setMinUpdateDistanceMeters(c.distanceFilterMeters.toFloat())
            .build()
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .requestLocationUpdates(request, updatesIntent(ctx))
                .addOnFailureListener { ElsLog.info("requestLocationUpdates failed: $it") }
        } catch (e: SecurityException) {
            ElsLog.info("requestLocationUpdates denied: $e")
        }
    }

    private fun removeUpdates(ctx: Context) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(updatesIntent(ctx))
        } catch (e: Exception) {
            ElsLog.info("removeLocationUpdates failed: $e")
        }
    }

    private fun startService(ctx: Context) = LaunchGate.promote(ctx)

    private fun stopService(ctx: Context) {
        if (LaunchGate.startPending) {
            ElsLog.info("service start pending; it stops itself after promoting")
            return
        }
        ctx.stopService(Intent(ctx, LocationTrackingService::class.java))
    }

    fun isAppInForeground(ctx: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun validationMessage(code: String): String = when (code) {
        "ALREADY_RUNNING" -> "A session is already running; call stop() first"
        "EXPIRED" -> "expiresAt is in the past"
        "NOTIFICATION_PERMISSION_DENIED" -> "Notification permission is not granted"
        else -> "Invalid session config (https upload url, non-empty id, positive radius, valid accuracy)"
    }
}

fun sanitized(f: Fix): Fix? {
    if (!f.latitude.isFinite() || !f.longitude.isFinite() || !f.accuracy.isFinite()) return null
    if (f.accuracy < 0 || kotlin.math.abs(f.latitude) > 90 || kotlin.math.abs(f.longitude) > 180) return null
    return f.copy(
        altitude = f.altitude?.takeIf { it.isFinite() },
        speed = f.speed?.takeIf { it.isFinite() && it >= 0 },
        heading = f.heading?.takeIf { it.isFinite() && it >= 0 }
    )
}

fun fixFrom(location: Location): Fix? {
    if (!location.hasAccuracy()) return null
    return sanitized(
        Fix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.toDouble(),
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            heading = if (location.hasBearing()) location.bearing.toDouble() else null,
            timestampMs = location.time
        )
    )
}

fun locationPayload(f: Fix): JSONObject {
    val o = JSONObject()
        .put("latitude", f.latitude)
        .put("longitude", f.longitude)
        .put("accuracy", f.accuracy)
        .put("timestamp", f.timestampMs)
    f.altitude?.let { o.put("altitude", it) }
    f.speed?.let { o.put("speed", it) }
    f.heading?.let { o.put("heading", it) }
    return o
}
