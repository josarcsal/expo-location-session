package com.margelo.nitro.locationsession.engine

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

enum class Grant(val wire: String) { GRANTED("granted"), DENIED("denied"), UNDETERMINED("undetermined") }

data class PermissionSnapshot(val foreground: Grant, val background: Grant, val precise: Boolean, val notifications: Grant) {
    fun toMap(): Map<String, Any?> =
        mapOf("foreground" to foreground.wire, "background" to background.wire, "precise" to precise, "notifications" to notifications.wire)
}

object Permissions {
    private const val REQUESTED_FOREGROUND = "requestedForeground"
    private const val REQUESTED_BACKGROUND = "requestedBackground"
    private const val REQUESTED_NOTIFICATIONS = "requestedNotifications"
    private const val REQUEST_CODE = 0x454c

    private class Pending(val always: Boolean, val completion: (PermissionSnapshot) -> Unit)

    private val queue = ArrayDeque<Pending>()
    private var inFlight: Pending? = null
    private var requestActivity: Activity? = null
    private var requestToken = 0
    private var currentActivity: () -> Activity? = { null }
    private var observing = false

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun hasForeground(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(ctx: Context): Boolean {
        if (!hasForeground(ctx)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun notificationsGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun declares(ctx: Context, permission: String): Boolean = try {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    } catch (e: Exception) {
        false
    }

    fun status(ctx: Context, activity: Activity?): PermissionSnapshot {
        val store = SessionStore(ctx)
        fun grant(isGranted: Boolean, flag: String, permission: String): Grant = when {
            isGranted -> Grant.GRANTED
            !store.flag(flag) -> Grant.UNDETERMINED
            activity != null && activity.shouldShowRequestPermissionRationale(permission) -> Grant.UNDETERMINED
            else -> Grant.DENIED
        }
        val foreground = grant(hasForeground(ctx), REQUESTED_FOREGROUND, Manifest.permission.ACCESS_FINE_LOCATION)
        val background = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> foreground
            !declares(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) && !hasBackground(ctx) -> Grant.DENIED
            else -> grant(hasBackground(ctx), REQUESTED_BACKGROUND, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val notifications = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Grant.GRANTED
        } else {
            grant(notificationsGranted(ctx), REQUESTED_NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS)
        }
        val snapshot = PermissionSnapshot(
            foreground = foreground,
            background = background,
            precise = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            notifications = notifications
        )
        ElsLog.debug("permission", "status", snapshot.toMap())
        return snapshot
    }

    fun capabilities(ctx: Context): Map<String, Any?> {
        val power = ctx.getSystemService(PowerManager::class.java)
        val activity = ctx.getSystemService(ActivityManager::class.java)
        val data = mutableMapOf<String, Any?>(
            "fineLocation" to granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            "coarseLocation" to granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION),
            "backgroundLocation" to hasBackground(ctx),
            "postNotifications" to notificationsGranted(ctx),
            "notificationsEnabled" to NotificationManagerCompat.from(ctx).areNotificationsEnabled(),
            "exactAlarms" to (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true),
            "ignoringBatteryOptimizations" to power?.isIgnoringBatteryOptimizations(ctx.packageName),
            "powerSaveMode" to power?.isPowerSaveMode,
            "deviceIdle" to power?.isDeviceIdleMode
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            data["backgroundRestricted"] = activity?.isBackgroundRestricted
            data["standbyBucket"] = try {
                ctx.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            } catch (e: Exception) {
                null
            }
        }
        return data
    }

    fun request(ctx: Context, activity: () -> Activity?, always: Boolean, completion: (PermissionSnapshot) -> Unit) {
        assertMain()
        currentActivity = activity
        observe(ctx)
        ElsLog.debug("permission", "request", mapOf("always" to always, "queued" to (inFlight != null)), level = "info")
        queue.addLast(Pending(always, completion))
        runNext(ctx)
    }

    private fun runNext(ctx: Context) {
        if (inFlight != null) return
        val next = queue.removeFirstOrNull() ?: return
        val activity = currentActivity()
        inFlight = next
        requestActivity = activity
        val token = ++requestToken
        perform(ctx, activity, next.always) { snapshot ->
            if (token != requestToken || inFlight !== next) return@perform
            inFlight = null
            requestActivity = null
            ElsLog.debug("permission", "request result", snapshot.toMap(), level = "info")
            next.completion(snapshot)
            runNext(ctx)
        }
    }

    private fun observe(ctx: Context) {
        if (observing) return
        val app = ctx.applicationContext as? Application ?: return
        observing = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                val current = inFlight ?: return
                if (requestActivity !== activity) return
                ElsLog.info("permission", "activity destroyed during a permission request; resolving pending requests", level = "warn")
                inFlight = null
                requestActivity = null
                requestToken++
                val snapshot = status(ctx, null)
                val pending = listOf(current) + queue
                queue.clear()
                pending.forEach { it.completion(snapshot) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    private fun perform(ctx: Context, activity: Activity?, always: Boolean, completion: (PermissionSnapshot) -> Unit) {
        val aware = activity as? PermissionAwareActivity
        val finish = { completion(status(ctx, activity)) }
        if (aware == null) {
            ElsLog.info("permission", "requestPermission without a PermissionAwareActivity; returning current status", level = "warn")
            finish()
            return
        }
        val store = SessionStore(ctx)
        val askBackground = {
            val wantsBackground = always &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                hasForeground(ctx) &&
                !hasBackground(ctx) &&
                declares(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (wantsBackground) {
                ask(aware, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    store.setFlag(REQUESTED_BACKGROUND)
                    finish()
                }
            } else {
                finish()
            }
        }
        val first = mutableListOf<String>()
        val flags = mutableListOf<String>()
        if (!hasForeground(ctx)) {
            first += Manifest.permission.ACCESS_FINE_LOCATION
            first += Manifest.permission.ACCESS_COARSE_LOCATION
            flags += REQUESTED_FOREGROUND
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted(ctx)) {
            first += Manifest.permission.POST_NOTIFICATIONS
            flags += REQUESTED_NOTIFICATIONS
        }
        if (first.isEmpty()) {
            askBackground()
        } else {
            ask(aware, first.toTypedArray()) {
                flags.forEach(store::setFlag)
                askBackground()
            }
        }
    }

    private fun ask(activity: PermissionAwareActivity, permissions: Array<String>, done: () -> Unit) {
        try {
            activity.requestPermissions(permissions, REQUEST_CODE, PermissionListener { code, _, _ ->
                if (code != REQUEST_CODE) return@PermissionListener false
                done()
                true
            })
        } catch (e: Exception) {
            ElsLog.info("permission", "requestPermissions failed: ${e.javaClass.simpleName}", level = "warn")
            done()
        }
    }
}
