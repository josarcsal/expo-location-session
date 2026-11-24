package com.margelo.nitro.locationsession.core

import java.net.URI
import org.json.JSONException
import org.json.JSONObject

enum class QueueMode(val wire: String) {
    LATEST("latest"),
    ALL("all");

    companion object {
        fun fromWire(value: String): QueueMode? = values().firstOrNull { it.wire == value }
    }
}

data class BatchConfig(val maxSize: Int, val rootProperty: String = "locations")

data class RefreshConfig(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyJson: String? = null
)

data class SyncConfig(
    val mode: QueueMode = QueueMode.LATEST,
    val maxRecords: Int = 10_000,
    val maxAgeMs: Long = 86_400_000,
    val batch: BatchConfig? = null,
    val templateJson: String? = null,
    val refresh: RefreshConfig? = null
) {
    fun toJsonObject(): JSONObject {
        val o = JSONObject()
        o.put("mode", mode.wire)
        o.put("maxRecords", maxRecords)
        o.put("maxAgeMs", maxAgeMs)
        if (batch != null) {
            o.put("batch", JSONObject().put("maxSize", batch.maxSize).put("rootProperty", batch.rootProperty))
        }
        if (templateJson != null) o.put("templateJson", templateJson)
        if (refresh != null) {
            val headers = JSONObject()
            refresh.headers.forEach { (k, v) -> headers.put(k, v) }
            val r = JSONObject().put("url", refresh.url).put("headers", headers)
            if (refresh.bodyJson != null) r.put("bodyJson", refresh.bodyJson)
            o.put("refresh", r)
        }
        return o
    }

    companion object {
        fun fromJsonObject(o: JSONObject): SyncConfig {
            val b = o.optJSONObject("batch")
            val r = o.optJSONObject("refresh")
            return SyncConfig(
                mode = QueueMode.fromWire(o.optString("mode", "latest")) ?: throw JSONException("unknown queue mode"),
                maxRecords = o.optInt("maxRecords", 10_000),
                maxAgeMs = o.optLong("maxAgeMs", 86_400_000),
                batch = b?.let { BatchConfig(it.getInt("maxSize"), it.optString("rootProperty", "locations")) },
                templateJson = if (o.has("templateJson")) o.getString("templateJson") else null,
                refresh = r?.let {
                    val h = it.optJSONObject("headers")
                    val headers = HashMap<String, String>()
                    h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
                    RefreshConfig(it.getString("url"), headers, if (it.has("bodyJson")) it.getString("bodyJson") else null)
                }
            )
        }
    }
}

fun validateSync(s: SyncConfig, extrasJson: String): String? {
    val b = s.batch
    if (b != null) {
        if (s.mode != QueueMode.ALL) return "batch requires queue.mode 'all'"
        if (b.maxSize !in 1..500) return "batch.maxSize must be between 1 and 500"
        if (b.rootProperty.isEmpty()) return "batch.rootProperty must not be empty"
    }
    if (s.maxRecords !in 1..100_000) return "queue.maxRecords must be between 1 and 100000"
    if (s.maxAgeMs !in 60_000L..2_592_000_000L) return "queue.maxAgeMs must be between 60000 and 2592000000"
    val r = s.refresh
    if (r != null) {
        val uri = try { URI(r.url) } catch (e: Exception) { null }
        if (uri == null || uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty()) {
            return "auth.refresh.url must be an https URL"
        }
        if (r.bodyJson != null) {
            if (parseObject(r.bodyJson) == null) return "auth.refresh.body must be a JSON object"
        }
    }
    if (s.templateJson != null) {
        val reason = validateTemplate(s.templateJson, extrasJson)
        if (reason != null) return "template: $reason"
    }
    return null
}
