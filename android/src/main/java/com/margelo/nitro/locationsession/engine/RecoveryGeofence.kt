package com.margelo.nitro.locationsession.engine

import android.content.Context
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.margelo.nitro.locationsession.core.Fix

object RecoveryGeofence {
    private const val REQUEST_ID = "els.recovery"
    private const val EXTRA_RADIUS_METERS = 200f
    private const val EXPIRATION_MS = 120_000L

    fun arm(ctx: Context, center: Fix?, done: (Boolean) -> Unit) {
        if (center != null) {
            register(ctx, center, done)
            return
        }
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { location ->
                    val fix = location?.let(::fixFrom)
                    if (fix == null) done(false) else register(ctx, fix, done)
                }
                .addOnFailureListener {
                    ElsLog.info("recovery geofence: no last location: $it")
                    done(false)
                }
        } catch (e: SecurityException) {
            ElsLog.info("recovery geofence: last location denied: $e")
            done(false)
        }
    }

    private fun register(ctx: Context, center: Fix, done: (Boolean) -> Unit) {
        val radius = center.accuracy.toFloat() + EXTRA_RADIUS_METERS
        val geofence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(center.latitude, center.longitude, radius)
            .setExpirationDuration(EXPIRATION_MS)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()
        try {
            LocationServices.getGeofencingClient(ctx)
                .addGeofences(request, LaunchGate.pendingIntent(ctx, LaunchGate.Kind.RECOVERY_GEOFENCE))
                .addOnSuccessListener {
                    ElsLog.info("recovery geofence armed at ${center.latitude},${center.longitude} radius=${radius}m")
                    done(true)
                }
                .addOnFailureListener {
                    ElsLog.info("recovery geofence failed: $it")
                    done(false)
                }
        } catch (e: SecurityException) {
            ElsLog.info("recovery geofence denied: $e")
            done(false)
        }
    }

    fun remove(ctx: Context) {
        try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(REQUEST_ID))
        } catch (e: Exception) {
            ElsLog.info("removeGeofences failed: $e")
        }
    }
}
