package com.margelo.nitro.locationsession.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class DebugOptions(
    val enabled: Boolean,
    val url: String?,
    val headers: Map<String, String>,
    val flushIntervalMs: Long,
    val maxFileBytes: Long
)

data class LogLine(
    val seq: Long,
    val t: Long,
    val pid: Int,
    val level: String,
    val tag: String,
    val msg: String,
    val sessionId: String? = null,
    val data: Map<String, Any?>? = null
)

data class BatchPlan(
    val fromSeq: Long,
    val toSeq: Long,
    val lostFrom: Long? = null,
    val lostTo: Long? = null
)

const val DEBUG_MIN_FLUSH_INTERVAL_MS = 10_000L
val DEBUG_MAX_FILE_BYTES_RANGE = 100_000L..10_000_000L

fun validateDebugOptions(o: DebugOptions): String? {
    o.url?.let { raw ->
        val uri = try { URI(raw) } catch (e: Exception) { null }
        if (uri == null || uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) {
            return "debug url must be an https URL"
        }
    }
    if (o.flushIntervalMs < DEBUG_MIN_FLUSH_INTERVAL_MS) {
        return "flushIntervalMs must be at least $DEBUG_MIN_FLUSH_INTERVAL_MS"
    }
    if (o.maxFileBytes !in DEBUG_MAX_FILE_BYTES_RANGE) {
        return "maxFileBytes must be between ${DEBUG_MAX_FILE_BYTES_RANGE.first} and ${DEBUG_MAX_FILE_BYTES_RANGE.last}"
    }
    return null
}

fun encodeLogLine(line: LogLine): String {
    val obj = JSONObject()
        .put("seq", line.seq)
        .put("t", line.t)
        .put("pid", line.pid)
        .put("level", line.level)
        .put("tag", line.tag)
        .put("msg", line.msg)
    line.sessionId?.let { obj.put("sessionId", it) }
    line.data?.let { obj.put("data", sanitizeMap(it)) }
    return obj.toString()
}

fun planBatch(fileSeqRange: LongRange, cursor: Long, max: Int = 500): BatchPlan? {
    val next = cursor + 1
    if (next > fileSeqRange.last || max <= 0) return null
    val from = maxOf(next, fileSeqRange.first)
    val to = minOf(fileSeqRange.last, from + max - 1)
    if (next < fileSeqRange.first) {
        return BatchPlan(fromSeq = from, toSeq = to, lostFrom = next, lostTo = fileSeqRange.first - 1)
    }
    return BatchPlan(fromSeq = from, toSeq = to)
}

private object Drop

private fun sanitize(value: Any?): Any? = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Short, is Byte -> value
    is Double -> if (value.isFinite()) value else Drop
    is Float -> if (value.isFinite()) value.toDouble() else Drop
    is Number -> value.toDouble().let { if (it.isFinite()) value else Drop }
    is Map<*, *> -> sanitizeMap(value)
    is Iterable<*> -> JSONArray().also { arr -> value.forEach { v -> sanitize(v).takeIf { it !== Drop }?.let(arr::put) } }
    is Array<*> -> sanitize(value.asList())
    else -> value.toString()
}

private fun sanitizeMap(map: Map<*, *>): JSONObject {
    val out = JSONObject()
    map.forEach { (k, v) ->
        val clean = sanitize(v)
        if (clean !== Drop) out.put(k.toString(), clean)
    }
    return out
}
