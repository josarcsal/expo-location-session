package com.margelo.nitro.locationsession.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationResult

class LocationUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationResult.hasResult(intent)) return
        val fixes = LocationResult.extractResult(intent)?.locations.orEmpty().mapNotNull(::fixFrom)
        if (fixes.isEmpty()) return
        val pending = goAsync()
        finishOnce(pending) { finish -> SessionEngine.onFixes(context, fixes, finish) }
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
