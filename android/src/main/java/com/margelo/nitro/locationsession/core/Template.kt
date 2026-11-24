package com.margelo.nitro.locationsession.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class FixRecord(val uuid: String, val sessionId: String, val fix: Fix, val battery: Battery?)

private sealed class Segment {
    data class Text(val value: String) : Segment()
    data class Placeholder(val name: String) : Segment()
}

private val templateFields = setOf(
    "id", "sessionId", "latitude", "longitude", "accuracy", "altitude", "speed", "heading",
    "timestamp", "timestampIso", "batteryLevel", "batteryCharging"
)

private const val MAX_TEMPLATE_BYTES = 8192
private const val EXTRAS_PREFIX = "extras."

private fun segmentsOf(s: String): List<Segment>? {
    val out = ArrayList<Segment>()
    val text = StringBuilder()
    var pos = 0
    while (true) {
        val open = s.indexOf("{{", pos)
        if (open < 0) break
        text.append(s, pos, open)
        val close = s.indexOf("}}", open + 2)
        if (close < 0) return null
        val name = s.substring(open + 2, close)
        if (name.isEmpty() || name.contains('{')) return null
        if (text.isNotEmpty()) {
            out.add(Segment.Text(text.toString()))
            text.setLength(0)
        }
        out.add(Segment.Placeholder(name))
        pos = close + 2
    }
    text.append(s, pos, s.length)
    if (text.isNotEmpty()) out.add(Segment.Text(text.toString()))
    return out
}

private fun parseObject(json: String): JSONObject? =
    try {
        val tokener = JSONTokener(json)
        val value = tokener.nextValue() as? JSONObject
        if (value != null && tokener.nextClean() != 0.toChar()) null else value
    } catch (e: Exception) {
        null
    }

private fun checkNode(node: Any?, extras: JSONObject): String? {
    when (node) {
        is JSONObject -> for (k in node.keys()) checkNode(node.get(k), extras)?.let { return it }
        is JSONArray -> for (i in 0 until node.length()) checkNode(node.get(i), extras)?.let { return it }
        is String -> {
            val segs = segmentsOf(node) ?: return "malformed placeholder in \"$node\""
            for (seg in segs) {
                if (seg !is Segment.Placeholder) continue
                val name = seg.name
                if (name.startsWith(EXTRAS_PREFIX)) {
                    val key = name.substring(EXTRAS_PREFIX.length)
                    if (!extras.has(key)) return "unknown extras key $key"
                } else if (name !in templateFields) {
                    return "unknown field $name"
                }
            }
        }
    }
    return null
}

fun validateTemplate(json: String, extrasJson: String): String? {
    val root = parseObject(json) ?: return "must be a JSON object"
    if (json.toByteArray(Charsets.UTF_8).size > MAX_TEMPLATE_BYTES) return "larger than 8 KB"
    return checkNode(root, parseObject(extrasJson) ?: JSONObject())
}

private fun isoString(ms: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms))
}

private fun lookup(name: String, r: FixRecord, extras: JSONObject): Any? {
    if (name.startsWith(EXTRAS_PREFIX)) {
        val v = extras.opt(name.substring(EXTRAS_PREFIX.length))
        return if (v == null || v == JSONObject.NULL) null else v
    }
    val value = rawLookup(name, r)
    return if (value is Double && !value.isFinite()) JSONObject.NULL else value
}

private fun rawLookup(name: String, r: FixRecord): Any? {
    return when (name) {
        "id" -> r.uuid
        "sessionId" -> r.sessionId
        "latitude" -> r.fix.latitude
        "longitude" -> r.fix.longitude
        "accuracy" -> r.fix.accuracy
        "altitude" -> r.fix.altitude
        "speed" -> r.fix.speed
        "heading" -> r.fix.heading
        "timestamp" -> r.fix.timestampMs
        "timestampIso" -> isoString(r.fix.timestampMs)
        "batteryLevel" -> r.battery?.level
        "batteryCharging" -> r.battery?.charging
        else -> null
    }
}

private fun render(node: Any?, r: FixRecord, extras: JSONObject): Any? {
    when (node) {
        is JSONObject -> {
            val out = JSONObject()
            for (k in node.keys()) {
                val v = render(node.get(k), r, extras)
                if (v != null) out.put(k, v)
            }
            return out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until node.length()) out.put(render(node.get(i), r, extras) ?: JSONObject.NULL)
            return out
        }
        is String -> {
            val segs = segmentsOf(node) ?: return node
            val only = segs.singleOrNull()
            if (only is Segment.Placeholder) return lookup(only.name, r, extras)
            val sb = StringBuilder()
            for (seg in segs) {
                when (seg) {
                    is Segment.Text -> sb.append(seg.value)
                    is Segment.Placeholder -> lookup(seg.name, r, extras)?.let { if (it != JSONObject.NULL) sb.append(it.toString()) }
                }
            }
            return sb.toString()
        }
    }
    return node
}

fun renderTemplate(json: String, record: FixRecord, extrasJson: String): String {
    val root = parseObject(json) ?: return "{}"
    return (render(root, record, parseObject(extrasJson) ?: JSONObject()) ?: JSONObject()).toString()
}
