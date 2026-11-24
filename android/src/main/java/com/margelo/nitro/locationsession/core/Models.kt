package com.margelo.nitro.locationsession.core

import org.json.JSONObject

data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double? = null,
    val speed: Double? = null,
    val heading: Double? = null,
    val timestampMs: Long
)

fun sanitize(f: Fix): Fix? {
    if (!f.latitude.isFinite() || !f.longitude.isFinite() || !f.accuracy.isFinite()) return null
    if (f.accuracy < 0 || kotlin.math.abs(f.latitude) > 90 || kotlin.math.abs(f.longitude) > 180) return null
    return f.copy(
        altitude = f.altitude?.takeIf { it.isFinite() },
        speed = f.speed?.takeIf { it.isFinite() && it >= 0 },
        heading = f.heading?.takeIf { it.isFinite() && it >= 0 }
    )
}

data class Battery(val level: Double, val charging: Boolean)

data class Destination(val latitude: Double, val longitude: Double, val radiusMeters: Double)

data class SessionConfig(
    val id: String,
    val expiresAtMs: Long,
    val destination: Destination?,
    val accuracy: String,
    val distanceFilterMeters: Double,
    val uploadUrl: String,
    val uploadHeaders: Map<String, String>,
    val uploadExtrasJson: String,
    val uploadMinIntervalMs: Long,
    val notificationTitle: String,
    val notificationText: String,
    val notificationChannelName: String,
    val sync: SyncConfig = SyncConfig()
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("expiresAtMs", expiresAtMs)
        if (destination != null) {
            o.put(
                "destination",
                JSONObject()
                    .put("latitude", destination.latitude)
                    .put("longitude", destination.longitude)
                    .put("radiusMeters", destination.radiusMeters)
            )
        }
        o.put("accuracy", accuracy)
        o.put("distanceFilterMeters", distanceFilterMeters)
        o.put("uploadUrl", uploadUrl)
        val headers = JSONObject()
        uploadHeaders.forEach { (k, v) -> headers.put(k, v) }
        o.put("uploadHeaders", headers)
        o.put("uploadExtrasJson", uploadExtrasJson)
        o.put("uploadMinIntervalMs", uploadMinIntervalMs)
        o.put("notificationTitle", notificationTitle)
        o.put("notificationText", notificationText)
        o.put("notificationChannelName", notificationChannelName)
        o.put("sync", sync.toJsonObject())
        return o.toString()
    }

    companion object {
        fun fromJson(json: String): SessionConfig {
            val o = JSONObject(json)
            val d = o.optJSONObject("destination")
            val h = o.getJSONObject("uploadHeaders")
            val headers = HashMap<String, String>()
            h.keys().forEach { headers[it] = h.getString(it) }
            return SessionConfig(
                id = o.getString("id"),
                expiresAtMs = o.getLong("expiresAtMs"),
                destination = d?.let {
                    Destination(it.getDouble("latitude"), it.getDouble("longitude"), it.getDouble("radiusMeters"))
                },
                accuracy = o.getString("accuracy"),
                distanceFilterMeters = o.getDouble("distanceFilterMeters"),
                uploadUrl = o.getString("uploadUrl"),
                uploadHeaders = headers,
                uploadExtrasJson = o.getString("uploadExtrasJson"),
                uploadMinIntervalMs = o.getLong("uploadMinIntervalMs"),
                notificationTitle = o.getString("notificationTitle"),
                notificationText = o.getString("notificationText"),
                notificationChannelName = o.getString("notificationChannelName"),
                sync = o.optJSONObject("sync")?.let { SyncConfig.fromJsonObject(it) } ?: SyncConfig()
            )
        }
    }
}

enum class StopReason(val wire: String) {
    STOPPED("stopped"),
    EXPIRED("expired"),
    ARRIVED("arrived"),
    UNAUTHORIZED("unauthorized"),
    REJECTED("rejected"),
    PERMISSION_REVOKED("permissionRevoked");

    companion object {
        fun fromWire(value: String): StopReason? = values().firstOrNull { it.wire == value }
    }
}
