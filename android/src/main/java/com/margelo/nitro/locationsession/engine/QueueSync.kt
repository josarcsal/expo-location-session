package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.margelo.nitro.locationsession.core.QUEUE_SYNC_WORK
import com.margelo.nitro.locationsession.core.shouldScheduleSync
import java.util.concurrent.TimeUnit

object QueueSync {
    private const val META_DATA_KEY = "expo-location-session.backgroundSync"

    @Volatile
    private var enabled: Boolean? = null

    fun isEnabled(ctx: Context): Boolean = enabled ?: readEnabled(ctx).also { enabled = it }

    fun scheduleIfNeeded(ctx: Context, pending: Int, sessionRunning: Boolean) {
        if (!shouldScheduleSync(isEnabled(ctx), pending, sessionRunning)) return
        val request = OneTimeWorkRequestBuilder<QueueSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        try {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(QUEUE_SYNC_WORK, ExistingWorkPolicy.KEEP, request)
            ElsLog.debug("sync", "background sync scheduled", mapOf("pending" to pending))
        } catch (e: Exception) {
            ElsLog.debug("sync", "background sync schedule failed", mapOf("pending" to pending, "error" to e.toString()), level = "warn")
        }
    }

    private fun readEnabled(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(ctx.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData?.getBoolean(META_DATA_KEY, false) ?: false
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
