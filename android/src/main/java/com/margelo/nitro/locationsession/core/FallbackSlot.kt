package com.margelo.nitro.locationsession.core

data class FallbackFix(val sessionId: String, val uuid: String, val fix: Fix, val generation: Int)

enum class LatestPick { MEMORY, QUEUE, NONE }

class FallbackSlot {
    var pending: FallbackFix? = null
        private set
    private var generation = 0
    private var floorSessionId: String? = null
    private var floorFix: Fix? = null

    fun put(sessionId: String, uuid: String, fix: Fix): Int {
        generation += 1
        pending = FallbackFix(sessionId, uuid, fix, generation)
        val floor = floorFix
        if (floorSessionId == sessionId && floor != null && floor.timestampMs >= fix.timestampMs) return generation
        floorSessionId = sessionId
        floorFix = fix
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
        floorFix = null
    }

    fun supersedes(sessionId: String, fixTs: Long): Boolean {
        val floor = floorFix ?: return false
        return floorSessionId == sessionId && fixTs <= floor.timestampMs
    }

    fun pendingFix(sessionId: String, queuedFixTs: Long?, queuedFix: Fix?, queueReadable: Boolean): Fix? =
        when (pick(sessionId, queuedFixTs)) {
            LatestPick.MEMORY -> pending?.fix
            LatestPick.QUEUE -> queuedFix
            LatestPick.NONE -> floorFix.takeIf { floorSessionId == sessionId && (queuedFixTs != null || !queueReadable) }
        }

    fun keep(sessionId: String?) {
        if (pending?.sessionId != sessionId) pending = null
        if (floorSessionId != sessionId) {
            floorSessionId = null
            floorFix = null
        }
    }

    companion object {
        fun resumes(pendingFixTs: Long, queuedFixTs: Long?): Boolean = queuedFixTs?.let { pendingFixTs > it } ?: true
    }
}
