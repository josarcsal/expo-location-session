package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.margelo.nitro.locationsession.core.Fix

object CurrentPosition {
    fun locate(ctx: Context, accuracy: String, timeoutMs: Double, maxAgeMs: Double, completion: (Result<Fix>) -> Unit) {
        assertMain()
        if (!Permissions.hasForeground(ctx)) {
            completion(Result.failure(LocationSessionException("PERMISSION_DENIED", "Location permission is not granted")))
            return
        }
        val manager = ctx.getSystemService(LocationManager::class.java)
        if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            completion(Result.failure(LocationSessionException("LOCATION_UNAVAILABLE", "Location services are disabled")))
            return
        }
        val timeout = if (timeoutMs.isFinite() && timeoutMs > 0) timeoutMs.toLong() else 15_000L
        val maxAge = if (maxAgeMs.isFinite() && maxAgeMs > 0) maxAgeMs.toLong() else 0L
        val priority = when (accuracy) {
            "high" -> Priority.PRIORITY_HIGH_ACCURACY
            "low" -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(maxAge)
            .setDurationMillis(timeout)
            .build()
        val cancellation = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val finish = { result: Result<Fix> ->
            if (!done) {
                done = true
                handler.removeCallbacksAndMessages(null)
                cancellation.cancel()
                completion(result)
            }
        }
        val timedOut = { finish(Result.failure(LocationSessionException("LOCATION_TIMEOUT", "No location within $timeout ms"))) }
        handler.postDelayed(timedOut, timeout + 1_000)
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(request, cancellation.token)
                .addOnSuccessListener { location ->
                    val fix = location?.let(::fixFrom)
                    if (fix != null) finish(Result.success(fix)) else timedOut()
                }
                .addOnFailureListener { e ->
                    val code = if (e is SecurityException) "PERMISSION_DENIED" else "LOCATION_UNAVAILABLE"
                    finish(Result.failure(LocationSessionException(code, e.message ?: e.toString())))
                }
        } catch (e: SecurityException) {
            finish(Result.failure(LocationSessionException("PERMISSION_DENIED", "Location permission is not granted")))
        }
    }
}
