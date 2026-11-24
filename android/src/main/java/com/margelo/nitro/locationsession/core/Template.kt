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

internal fun parseObject(json: String): JSONObject? {
    val strict = StrictJson(json).normalizedObject() ?: return null
    return try {
        val tokener = JSONTokener(strict)
        val value = tokener.nextValue() as? JSONObject
        if (value != null && tokener.nextClean() != 0.toChar()) null else value
    } catch (e: Exception) {
        null
    }
}

private class StrictJson(private val s: String) {
    private var i = 0
    private val dropped = mutableListOf<Int>()

    fun normalizedObject(): String? {
        if (peek() == '\uFEFF') {
            dropped.add(i)
            i++
        }
        skipSpace()
        if (peek() != '{' || !value()) return null
        skipSpace()
        if (i != s.length) return null
        if (dropped.isEmpty()) return s
        val out = StringBuilder(s.length)
        var from = 0
        for (at in dropped) {
            out.append(s, from, at)
            from = at + 1
        }
        return out.append(s, from, s.length).toString()
    }

    private fun trailingComma(close: Char): Boolean {
        val comma = i - 1
        skipSpace()
        if (peek() != close) return false
        dropped.add(comma)
        i++
        return true
    }

    private fun peek(): Char? = if (i < s.length) s[i] else null

    private fun skipSpace() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun value(): Boolean {
        skipSpace()
        return when (peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true")
            'f' -> word("false")
            'n' -> word("null")
            else -> num()
        }
    }

    private fun obj(): Boolean {
        i++
        skipSpace()
        if (peek() == '}') {
            i++
            return true
        }
        while (true) {
            skipSpace()
            if (peek() != '"' || !str()) return false
            skipSpace()
            if (peek() != ':') return false
            i++
            if (!value()) return false
            skipSpace()
            when (peek()) {
                ',' -> {
                    i++
                    if (trailingComma('}')) return true
                }
                '}' -> {
                    i++
                    return true
                }
                else -> return false
            }
        }
    }

    private fun arr(): Boolean {
        i++
        skipSpace()
        if (peek() == ']') {
            i++
            return true
        }
        while (true) {
            if (!value()) return false
            skipSpace()
            when (peek()) {
                ',' -> {
                    i++
                    if (trailingComma(']')) return true
                }
                ']' -> {
                    i++
                    return true
                }
                else -> return false
            }
        }
    }

    private fun str(): Boolean {
        i++
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '"' -> return true
                c < ' ' -> return false
                c == '\\' -> {
                    val e = peek() ?: return false
                    i++
                    if (e == 'u') {
                        if (i + 4 > s.length || !s.substring(i, i + 4).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
                        i += 4
                    } else if (e !in "\"\\/bfnrt") {
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun word(w: String): Boolean {
        if (!s.startsWith(w, i)) return false
        i += w.length
        return true
    }

    private fun num(): Boolean {
        val start = i
        if (peek() == '-') i++
        when (peek()) {
            '0' -> i++
            in '1'..'9' -> digits()
            else -> return false
        }
        if (peek() == '.') {
            i++
            if (!digits()) return false
        }
        if (peek() == 'e' || peek() == 'E') {
            i++
            if (peek() == '+' || peek() == '-') i++
            if (!digits()) return false
        }
        return i > start
    }

    private fun digits(): Boolean {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        return i > start
    }
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

private val isoFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

private fun isoString(ms: Long): String = isoFormatter.get()!!.format(Date(ms))

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
