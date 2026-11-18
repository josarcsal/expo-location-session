package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.json.JSONTokener

fun buildBody(sessionId: String, fix: Fix, battery: Battery?, extrasJson: String): String {
    val location = JSONObject()
        .put("latitude", fix.latitude)
        .put("longitude", fix.longitude)
        .put("accuracy", fix.accuracy)
        .put("timestamp", fix.timestampMs)
    fix.altitude?.let { location.put("altitude", it) }
    fix.speed?.let { location.put("speed", it) }
    fix.heading?.let { location.put("heading", it) }

    val body = JSONObject()
        .put("sessionId", sessionId)
        .put("location", location)
        .put("extras", parseExtras(extrasJson))
    battery?.let { body.put("battery", JSONObject().put("level", it.level).put("charging", it.charging)) }
    return body.toString()
}

private fun parseExtras(json: String): JSONObject =
    try {
        JSONTokener(json).nextValue() as? JSONObject ?: JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }
