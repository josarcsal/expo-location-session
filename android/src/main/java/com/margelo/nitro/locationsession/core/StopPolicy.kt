package com.margelo.nitro.locationsession.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

fun acceptFix(f: Fix, startedAtMs: Long): Boolean = f.timestampMs >= startedAtMs

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = lat1 * PI / 180
    val p2 = lat2 * PI / 180
    val dp = (lat2 - lat1) * PI / 180
    val dl = (lon2 - lon1) * PI / 180
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(min(1.0, sqrt(a)))
}

fun isArrival(d: Destination, f: Fix): Boolean {
    val distance = distanceMeters(d.latitude, d.longitude, f.latitude, f.longitude)
    return distance <= d.radiusMeters && f.accuracy <= max(d.radiusMeters, 100.0)
}

fun stopDecision(c: SessionConfig, fix: Fix?, nowMs: Long): StopReason? {
    if (nowMs >= c.expiresAtMs) return StopReason.EXPIRED
    val d = c.destination
    if (d != null && fix != null && isArrival(d, fix)) return StopReason.ARRIVED
    return null
}
