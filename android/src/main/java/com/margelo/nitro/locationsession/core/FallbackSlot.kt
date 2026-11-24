package com.margelo.nitro.locationsession.core

data class FallbackFix(val sessionId: String, val uuid: String, val fix: Fix, val generation: Int)

enum class LatestPick { MEMORY, QUEUE, NONE }

class FallbackSlot {
    var pending: FallbackFix? = null
        private set
    private var generation = 0
    private var floorSessionId: String? = null
    private var floorFixTs = 0L

    fun put(sessionId: String, uuid: String, fix: Fix): Int {
        generation += 1
        pending = FallbackFix(sessionId, uuid, fix, generation)
        floorFixTs = if (floorSessionId == sessionId) maxOf(floorFixTs, fix.timestampMs) else fix.timestampMs
        floorSessionId = sessionId
        return generation
    }

    fun next(sessionId: String): FallbackFix? = pending?.takeIf { it.sessionId == sessionId }

    fun pick(sessionId: String, queuedFixTs: Long?): LatestPick = when {
        next(sessionId) != null -> LatestPick.MEMORY
        queuedFixTs == null || supersedes(sessionId, queuedFixTs) -> LatestPick.NONE
        else -> LatestPick.QUEUE
    }

    fun markSent(sent: Int) {
        if (pending?.generation == sent) pending = null
    }

    fun stored() {
        pending = null
        floorSessionId = null
    }

    fun supersedes(sessionId: String, fixTs: Long): Boolean = floorSessionId == sessionId && fixTs <= floorFixTs

    fun keep(sessionId: String?) {
        if (pending?.sessionId != sessionId) pending = null
        if (floorSessionId != sessionId) floorSessionId = null
    }

    companion object {
        fun resumes(pendingFixTs: Long, queuedFixTs: Long?): Boolean = queuedFixTs?.let { pendingFixTs > it } ?: true
    }
}
