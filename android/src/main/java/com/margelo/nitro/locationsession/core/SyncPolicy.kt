package com.margelo.nitro.locationsession.core

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

fun isExpired(fixTs: Long, nowMs: Long, maxAgeMs: Long): Boolean = nowMs - fixTs > maxAgeMs

fun trimCount(rows: Int, maxRecords: Int): Int = maxOf(0, rows - maxRecords)
