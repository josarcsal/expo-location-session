package com.margelo.nitro.locationsession.core

import java.net.URI

fun validate(c: SessionConfig, nowMs: Long, running: Boolean, notificationsGranted: Boolean = true): String? {
    if (running) return "ALREADY_RUNNING"
    if (isInvalid(c)) return "INVALID_CONFIG"
    if (!notificationsGranted) return "NOTIFICATION_PERMISSION_DENIED"
    if (c.expiresAtMs <= nowMs) return "EXPIRED"
    return null
}

private fun isInvalid(c: SessionConfig): Boolean {
    if (c.id.isEmpty()) return true
    val uri = try { URI(c.uploadUrl) } catch (e: Exception) { return true }
    if (uri.scheme?.lowercase() != "https") return true
    if (uri.host.isNullOrEmpty()) return true
    val d = c.destination
    if (d != null && !(d.radiusMeters > 0)) return true
    if (!(c.distanceFilterMeters >= 0)) return true
    if (c.uploadMinIntervalMs < 0) return true
    return c.accuracy !in listOf("high", "balanced", "low")
}
