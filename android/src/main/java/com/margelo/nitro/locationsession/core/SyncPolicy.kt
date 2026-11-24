package com.margelo.nitro.locationsession.core

import org.json.JSONException
import org.json.JSONObject

sealed class SyncAction {
    object DeleteSent : SyncAction()
    object RefreshAndRetry : SyncAction()
    data class Stop(val reason: StopReason) : SyncAction()
    object DropSent : SyncAction()
    object DropSession : SyncAction()
    object Retry : SyncAction()
}

fun syncAction(status: Int?, ended: Boolean, refreshConfigured: Boolean, alreadyRefreshed: Boolean): SyncAction {
    if (status == null) return SyncAction.Retry
    return when {
        status in 200..299 -> SyncAction.DeleteSent
        status == 401 && refreshConfigured -> when {
            !alreadyRefreshed -> SyncAction.RefreshAndRetry
            ended -> SyncAction.Retry
            else -> SyncAction.Stop(StopReason.UNAUTHORIZED)
        }
        status == 401 || status == 403 ->
            if (ended) SyncAction.DropSent else SyncAction.Stop(StopReason.UNAUTHORIZED)
        status == 410 -> if (ended) SyncAction.DropSession else SyncAction.Stop(StopReason.REJECTED)
        status in 400..499 -> SyncAction.DropSent
        else -> SyncAction.Retry
    }
}

enum class StopQueueAction { DELETE_ROWS, DELETE_ROWS_DROPPED, MARK_ENDED }

fun stopQueueAction(mode: QueueMode, reason: StopReason): StopQueueAction = when {
    mode != QueueMode.ALL -> StopQueueAction.DELETE_ROWS
    reason == StopReason.REJECTED -> StopQueueAction.DELETE_ROWS_DROPPED
    else -> StopQueueAction.MARK_ENDED
}

fun isExpired(fixTs: Long, nowMs: Long, maxAgeMs: Long): Boolean = nowMs - fixTs > maxAgeMs

fun trimCount(rows: Int, maxRecords: Int): Int = maxOf(0, rows - maxRecords)

data class RefreshResult(val headers: Map<String, String>, val refreshHeaders: Map<String, String>?)

private fun stringMap(o: JSONObject): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    for (key in o.keys()) {
        val v = o.opt(key) as? String ?: return null
        out[key] = v
    }
    return out
}

fun parseRefreshResponse(status: Int?, body: String): RefreshResult? {
    if (status == null || status !in 200..299) return null
    val obj = try { JSONObject(body) } catch (e: JSONException) { return null }
    val headers = (obj.opt("headers") as? JSONObject)?.let(::stringMap)?.takeIf { it.isNotEmpty() } ?: return null
    val refreshHeaders = if (obj.has("refreshHeaders")) {
        (obj.opt("refreshHeaders") as? JSONObject)?.let(::stringMap) ?: return null
    } else null
    return RefreshResult(headers, refreshHeaders)
}

fun refreshRequestBody(bodyJson: String?, sessionId: String): String =
    bodyJson ?: JSONObject().put("sessionId", sessionId).toString()

class ConnectivityEdge {
    private var satisfied: Boolean? = null

    fun update(satisfied: Boolean): Boolean {
        val regained = this.satisfied == false && satisfied
        this.satisfied = satisfied
        return regained
    }
}

const val QUEUE_SYNC_WORK = "els-queue-sync"

fun shouldScheduleSync(enabled: Boolean, pending: Int, sessionRunning: Boolean): Boolean =
    enabled && pending > 0 && !sessionRunning
