package com.margelo.nitro.locationsession.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

enum class Grant(val wire: String) { GRANTED("granted"), DENIED("denied"), UNDETERMINED("undetermined") }

data class PermissionSnapshot(val foreground: Grant, val background: Grant, val precise: Boolean, val notifications: Grant)

object Permissions {
    private const val REQUESTED_FOREGROUND = "requestedForeground"
    private const val REQUESTED_BACKGROUND = "requestedBackground"
    private const val REQUESTED_NOTIFICATIONS = "requestedNotifications"
    private const val REQUEST_CODE = 0x454c

    private val queue = ArrayDeque<Pair<Boolean, (PermissionSnapshot) -> Unit>>()
    private var requesting = false

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
        return PermissionSnapshot(
            foreground = foreground,
            background = background,
            precise = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION),
            notifications = notifications
        )
    }

    fun request(ctx: Context, activity: Activity?, always: Boolean, completion: (PermissionSnapshot) -> Unit) {
        assertMain()
        queue.addLast(always to completion)
        runNext(ctx, activity)
    }

    private fun runNext(ctx: Context, activity: Activity?) {
        if (requesting) return
        val next = queue.removeFirstOrNull() ?: return
        requesting = true
        perform(ctx, activity, next.first) { snapshot ->
            next.second(snapshot)
            requesting = false
            runNext(ctx, activity)
        }
    }

    private fun perform(ctx: Context, activity: Activity?, always: Boolean, completion: (PermissionSnapshot) -> Unit) {
        val aware = activity as? PermissionAwareActivity
        val finish = { completion(status(ctx, activity)) }
        if (aware == null) {
            ElsLog.info("requestPermission without a PermissionAwareActivity; returning current status")
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
                store.setFlag(REQUESTED_BACKGROUND)
                ask(aware, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { finish() }
            } else {
                finish()
            }
        }
        val first = mutableListOf<String>()
        if (!hasForeground(ctx)) {
            first += Manifest.permission.ACCESS_FINE_LOCATION
            first += Manifest.permission.ACCESS_COARSE_LOCATION
            store.setFlag(REQUESTED_FOREGROUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted(ctx)) {
            first += Manifest.permission.POST_NOTIFICATIONS
            store.setFlag(REQUESTED_NOTIFICATIONS)
        }
        if (first.isEmpty()) askBackground() else ask(aware, first.toTypedArray()) { askBackground() }
    }

    private fun ask(activity: PermissionAwareActivity, permissions: Array<String>, done: () -> Unit) {
        try {
            activity.requestPermissions(permissions, REQUEST_CODE, PermissionListener { code, _, _ ->
                if (code != REQUEST_CODE) return@PermissionListener false
                done()
                true
            })
        } catch (e: Exception) {
            ElsLog.info("requestPermissions failed: $e")
            done()
        }
    }
}
