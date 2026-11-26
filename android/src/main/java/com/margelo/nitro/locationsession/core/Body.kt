package com.margelo.nitro.locationsession.core

import org.json.JSONObject

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

private fun parseExtras(json: String): JSONObject = parseObject(json) ?: JSONObject()

fun batchLimit(s: SyncConfig): Int = s.batch?.maxSize ?: 1

fun buildElement(record: FixRecord, sync: SyncConfig, extrasJson: String): String {
    sync.templateJson?.let { return renderTemplate(it, record, extrasJson) }
    if (batchLimit(sync) > 1) {
        val fix = record.fix
        val location = JSONObject()
            .put("latitude", fix.latitude)
            .put("longitude", fix.longitude)
            .put("accuracy", fix.accuracy)
            .put("timestamp", fix.timestampMs)
        fix.altitude?.let { location.put("altitude", it) }
        fix.speed?.let { location.put("speed", it) }
        fix.heading?.let { location.put("heading", it) }
        val element = JSONObject().put("id", record.uuid).put("location", location)
        record.battery?.let { element.put("battery", JSONObject().put("level", it.level).put("charging", it.charging)) }
        return element.toString()
    }
    val v01 = buildBody(record.sessionId, record.fix, record.battery, extrasJson)
    if (sync.mode == QueueMode.ALL) return JSONObject(v01).put("id", record.uuid).toString()
    return v01
}

fun buildRequestBody(sessionId: String, elements: List<String>, sync: SyncConfig, extrasJson: String): String {
    val batch = sync.batch
    if (batchLimit(sync) <= 1 || batch == null) return elements.firstOrNull() ?: "{}"
    return "{\"sessionId\":${JSONObject.quote(sessionId)},${JSONObject.quote(batch.rootProperty)}:[${elements.joinToString(",")}],\"extras\":${parseExtras(extrasJson)}}"
}
