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
                    ElsLog.info("region", "recovery geofence: no last location: ${it.javaClass.simpleName}", level = "warn")
                    done(false)
                }
        } catch (e: SecurityException) {
            ElsLog.info("region", "recovery geofence: last location denied", level = "warn")
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
                    ElsLog.info(
                        "region",
                        "recovery geofence armed radius=${radius}m",
                        mapOf("latitude" to center.latitude, "longitude" to center.longitude, "radius" to radius, "expirationMs" to EXPIRATION_MS)
                    )
                    done(true)
                }
                .addOnFailureListener {
                    ElsLog.info("region", "recovery geofence failed: ${it.javaClass.simpleName}", mapOf("error" to it.toString()), level = "warn")
                    done(false)
                }
        } catch (e: SecurityException) {
            ElsLog.info("region", "recovery geofence denied", level = "warn")
            done(false)
        }
    }

    fun remove(ctx: Context) {
        try {
            LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(REQUEST_ID))
            ElsLog.debug("region", "recovery geofence removed")
        } catch (e: Exception) {
            ElsLog.info("region", "removeGeofences failed: ${e.javaClass.simpleName}", level = "warn")
        }
    }
}
