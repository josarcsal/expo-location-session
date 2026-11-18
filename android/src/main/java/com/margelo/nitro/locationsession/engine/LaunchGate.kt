package com.margelo.nitro.locationsession.engine

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import com.margelo.nitro.locationsession.core.StopReason
import org.json.JSONObject

class LaunchGate : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = Kind.fromAction(intent.action) ?: return
        val pending = goAsync()
        finishOnce(pending) { finish -> onDelivery(context, kind, intent, finish) }
    }

    enum class Kind(val action: String, val route: String, val mutable: Boolean) {
        LOCATION("expo.locationsession.LOCATION", "location", true),
        RECOVERY_GEOFENCE("expo.locationsession.RECOVERY_GEOFENCE", "recoveryGeofence", true),
        EXACT_ALARM("expo.locationsession.EXACT_ALARM", "exactAlarm", false),
        EXPIRY("expo.locationsession.EXPIRY", "expiryAlarm", false);

        companion object {
            fun fromAction(action: String?): Kind? = values().firstOrNull { it.action == action }
        }
    }

    companion object {
        private const val PROMOTION_DEADLINE_MS = 10 * 60_000L

        private var deliveryRoute: String? = null
        private var startedRoute: String? = null
        private var deniedDuringDelivery = false
        var startPending = false
            private set

        fun pendingIntent(ctx: Context, kind: Kind): PendingIntent {
            val intent = Intent(ctx, LaunchGate::class.java).setAction(kind.action)
            val flags = when {
                !kind.mutable -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else -> PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(ctx, kind.ordinal, intent, flags)
        }

        fun onDelivery(ctx: Context, kind: Kind, intent: Intent, finish: () -> Unit) {
            assertMain()
            deliveryRoute = kind.route
            deniedDuringDelivery = false
            try {
                ElsLog.info("gate delivery ${kind.route}")
                if (kind == Kind.RECOVERY_GEOFENCE) {
                    GeofencingEvent.fromIntent(intent)?.takeIf { it.hasError() }?.let {
                        ElsLog.info("recovery geofence error ${it.errorCode}")
                    }
                    RecoveryGeofence.remove(ctx)
                }
                SessionEngine.resumeIfNeeded(ctx)
                val live = SessionEngine.activeConfig?.let { nowMs() < it.expiresAtMs } == true
                if (live) {
                    promote(ctx)
                    enforceDeadline(ctx)
                } else if (!SessionEngine.isActive && (kind == Kind.EXACT_ALARM || kind == Kind.RECOVERY_GEOFENCE)) {
                    clear(ctx)
                }
                when (kind) {
                    Kind.LOCATION -> {
                        val fixes = if (LocationResult.hasResult(intent)) {
                            LocationResult.extractResult(intent)?.locations.orEmpty().mapNotNull(::fixFrom)
                        } else {
                            emptyList()
                        }
                        SessionEngine.onFixes(ctx, fixes, finish)
                    }
                    Kind.EXPIRY -> SessionEngine.onExpiryAlarm(ctx, finish)
                    else -> finish()
                }
            } finally {
                deliveryRoute = null
                deniedDuringDelivery = false
            }
        }

        fun promote(ctx: Context) {
            assertMain()
            if (LocationTrackingService.isForeground || startPending || deniedDuringDelivery) return
            val appInForeground = SessionEngine.isAppInForeground(ctx)
            val route = if (appInForeground) "foregroundApp" else deliveryRoute ?: "background"
            if (!Permissions.hasForeground(ctx) || !(Permissions.hasBackground(ctx) || appInForeground)) {
                ElsLog.info("skipping foreground service start via $route: location FGS would not be allowed")
                return
            }
            if (LocationTrackingService.isAlive) {
                ElsLog.route("service alive but not foreground via $route")
                onDenied(ctx, route)
                return
            }
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocationTrackingService::class.java))
                startPending = true
                startedRoute = route
                ElsLog.route("foreground service start allowed via $route")
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    ElsLog.route("foreground service start denied via $route")
                    onDenied(ctx, route)
                } else {
                    ElsLog.route("foreground service start failed via $route: $e")
                    onDenied(ctx, route)
                }
            }
        }

        fun restart(ctx: Context, route: String) {
            assertMain()
            if (!SessionEngine.isActive || LocationTrackingService.isForeground) return
            deliveryRoute = route
            try {
                promote(ctx)
            } finally {
                deliveryRoute = null
            }
        }

        fun serviceStartDelivered() {
            assertMain()
            startPending = false
        }

        fun onPromoted(ctx: Context) {
            assertMain()
            val route = startedRoute ?: "systemRestart"
            startedRoute = null
            ElsLog.route("service promoted via $route")
            val store = SessionStore(ctx)
            if (store.firstPromotionFailureAtMs != null) {
                store.firstPromotionFailureAtMs = null
                cancelRetry(ctx)
                RecoveryGeofence.remove(ctx)
            }
        }

        fun clear(ctx: Context) {
            cancelRetry(ctx)
            RecoveryGeofence.remove(ctx)
        }

        private fun onDenied(ctx: Context, route: String) {
            if (deliveryRoute != null) deniedDuringDelivery = true
            val store = SessionStore(ctx)
            if (store.firstPromotionFailureAtMs == null) store.firstPromotionFailureAtMs = nowMs()
            when {
                route == Kind.EXACT_ALARM.route || route == Kind.RECOVERY_GEOFENCE.route ->
                    ElsLog.route("second consecutive denial; waiting for the next delivery")
                canScheduleExactAlarms(ctx) && scheduleRetry(ctx) ->
                    ElsLog.route("retry scheduled via exactAlarm")
                else -> {
                    ElsLog.route("retry scheduled via recoveryGeofence")
                    RecoveryGeofence.arm(ctx, SessionEngine.lastKnownFix) { armed ->
                        if (!armed) ElsLog.route("recovery geofence unavailable; waiting for the next delivery")
                    }
                }
            }
        }

        private fun enforceDeadline(ctx: Context) {
            if (!SessionEngine.isActive || LocationTrackingService.isForeground || startPending) return
            val first = SessionStore(ctx).firstPromotionFailureAtMs ?: return
            val elapsed = nowMs() - first
            if (elapsed < PROMOTION_DEADLINE_MS) return
            ElsLog.route("no promotion for ${elapsed / 1000}s; stopping the session")
            SessionEngine.events.emit(
                "error",
                JSONObject()
                    .put("code", "FGS_PROMOTION_FAILED")
                    .put("message", "Android did not allow the location foreground service to restart for 10 minutes")
            )
            SessionEngine.stop(ctx, StopReason.STOPPED)
        }

        private fun canScheduleExactAlarms(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }

        private fun scheduleRetry(ctx: Context): Boolean {
            val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return false
            return try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nowMs(), pendingIntent(ctx, Kind.EXACT_ALARM))
                true
            } catch (e: SecurityException) {
                ElsLog.info("exact alarm denied: $e")
                false
            }
        }

        private fun cancelRetry(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx, Kind.EXACT_ALARM))
        }
    }
}

private const val ASYNC_BUDGET_MS = 9_000L

internal fun finishOnce(pending: BroadcastReceiver.PendingResult, run: (() -> Unit) -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var done = false
    val finish = {
        if (!done) {
            done = true
            pending.finish()
        }
    }
    handler.postDelayed(finish, ASYNC_BUDGET_MS)
    try {
        run {
            handler.removeCallbacksAndMessages(null)
            finish()
        }
    } catch (e: Exception) {
        ElsLog.info("receiver failed: $e")
        finish()
    }
}
