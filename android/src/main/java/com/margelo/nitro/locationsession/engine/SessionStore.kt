package com.margelo.nitro.locationsession.engine

import android.content.Context
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import org.json.JSONObject

data class LastStop(val reason: StopReason, val atMs: Long, val sessionId: String)

sealed class StoredSession {
    object None : StoredSession()
    object Corrupt : StoredSession()
    data class Session(val config: SessionConfig, val startedAtMs: Long) : StoredSession()
}

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("expo_location_session", Context.MODE_PRIVATE)

    private companion object {
        const val CONFIG = "config"
        const val STARTED_AT = "startedAtMs"
        const val PENDING_FIX = "pendingFix"
        const val LAST_UPLOAD_AT = "lastUploadAtMs"
        const val LAST_STOP = "lastStop"
        const val FIRST_PROMOTION_FAILURE_AT = "firstPromotionFailureAtMs"
    }

    fun save(config: SessionConfig, startedAtMs: Long): Boolean {
        val json = try {
            config.toJson()
        } catch (e: Exception) {
            return false
        }
        return prefs.edit()
            .putString(CONFIG, json)
            .putLong(STARTED_AT, startedAtMs)
            .remove(PENDING_FIX)
            .remove(LAST_UPLOAD_AT)
            .remove(FIRST_PROMOTION_FAILURE_AT)
            .commit()
    }

    fun load(): StoredSession {
        val json = prefs.getString(CONFIG, null)
        val hasStarted = prefs.contains(STARTED_AT)
        if (json == null) return if (hasStarted) StoredSession.Corrupt else StoredSession.None
        if (!hasStarted) return StoredSession.Corrupt
        return try {
            StoredSession.Session(SessionConfig.fromJson(json), prefs.getLong(STARTED_AT, 0))
        } catch (e: Exception) {
            StoredSession.Corrupt
        }
    }

    var pendingFix: Fix?
        get() = prefs.getString(PENDING_FIX, null)?.let(::decodeFix)
        set(value) {
            val json = value?.let(::encodeFix)
            if (json != null) prefs.edit().putString(PENDING_FIX, json).apply()
            else prefs.edit().remove(PENDING_FIX).apply()
        }

    var lastUploadAtMs: Long?
        get() = if (prefs.contains(LAST_UPLOAD_AT)) prefs.getLong(LAST_UPLOAD_AT, 0) else null
        set(value) {
            if (value != null) prefs.edit().putLong(LAST_UPLOAD_AT, value).apply()
            else prefs.edit().remove(LAST_UPLOAD_AT).apply()
        }

    var firstPromotionFailureAtMs: Long?
        get() = if (prefs.contains(FIRST_PROMOTION_FAILURE_AT)) prefs.getLong(FIRST_PROMOTION_FAILURE_AT, 0) else null
        set(value) {
            if (value != null) prefs.edit().putLong(FIRST_PROMOTION_FAILURE_AT, value).commit()
            else prefs.edit().remove(FIRST_PROMOTION_FAILURE_AT).commit()
        }

    var lastStop: LastStop?
        get() = try {
            prefs.getString(LAST_STOP, null)?.let {
                val o = JSONObject(it)
                val reason = StopReason.fromWire(o.getString("reason")) ?: return null
                LastStop(reason, o.getLong("atMs"), o.getString("sessionId"))
            }
        } catch (e: Exception) {
            null
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(LAST_STOP).apply()
                return
            }
            val json = JSONObject()
                .put("reason", value.reason.wire)
                .put("atMs", value.atMs)
                .put("sessionId", value.sessionId)
                .toString()
            prefs.edit().putString(LAST_STOP, json).apply()
        }

    fun flag(name: String): Boolean = prefs.getBoolean(name, false)

    fun setFlag(name: String) {
        prefs.edit().putBoolean(name, true).apply()
    }

    fun wipe() {
        prefs.edit()
            .remove(CONFIG)
            .remove(STARTED_AT)
            .remove(PENDING_FIX)
            .remove(LAST_UPLOAD_AT)
            .remove(FIRST_PROMOTION_FAILURE_AT)
            .commit()
    }
}

private fun encodeFix(f: Fix): String? = try {
    val o = JSONObject()
        .put("latitude", f.latitude)
        .put("longitude", f.longitude)
        .put("accuracy", f.accuracy)
        .put("timestampMs", f.timestampMs)
    f.altitude?.let { o.put("altitude", it) }
    f.speed?.let { o.put("speed", it) }
    f.heading?.let { o.put("heading", it) }
    o.toString()
} catch (e: Exception) {
    null
}

private fun decodeFix(json: String): Fix? = try {
    val o = JSONObject(json)
    sanitized(
        Fix(
            latitude = o.getDouble("latitude"),
            longitude = o.getDouble("longitude"),
            accuracy = o.getDouble("accuracy"),
            altitude = if (o.has("altitude")) o.getDouble("altitude") else null,
            speed = if (o.has("speed")) o.getDouble("speed") else null,
            heading = if (o.has("heading")) o.getDouble("heading") else null,
            timestampMs = o.getLong("timestampMs")
        )
    )
} catch (e: Exception) {
    null
}
