package com.margelo.nitro.locationsession.engine

import android.content.Context
import org.json.JSONObject

data class Credentials(val headers: Map<String, String>, val refreshHeaders: Map<String, String>)

class TargetCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("expo_location_session_targets", Context.MODE_PRIVATE)

    fun save(sessionId: String, headers: Map<String, String>, refreshHeaders: Map<String, String>) {
        val json = JSONObject()
            .put("headers", JSONObject(headers))
            .put("refreshHeaders", JSONObject(refreshHeaders))
            .toString()
        if (!prefs.edit().putString(key(sessionId), json).commit()) {
            ElsLog.debug("auth", "credentials not persisted", sessionId = sessionId, level = "warn")
        }
    }

    fun load(sessionId: String): Credentials? {
        val json = prefs.getString(key(sessionId), null) ?: return null
        return try {
            val o = JSONObject(json)
            Credentials(stringMap(o.getJSONObject("headers")), stringMap(o.getJSONObject("refreshHeaders")))
        } catch (e: Exception) {
            ElsLog.debug("auth", "credentials unreadable", sessionId = sessionId, level = "warn")
            null
        }
    }

    fun remove(sessionId: String) {
        prefs.edit().remove(key(sessionId)).commit()
    }

    private fun key(sessionId: String) = "target.$sessionId"

    private fun stringMap(o: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in o.keys()) out[name] = o.getString(name)
        return out
    }
}
