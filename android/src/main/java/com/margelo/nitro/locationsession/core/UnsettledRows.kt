package com.margelo.nitro.locationsession.core

class UnsettledRows {
    private val pendingDeletes = mutableSetOf<Long>()
    private val pendingReleases = mutableSetOf<Long>()

    val deletes: Set<Long> get() = pendingDeletes.toSet()

    val releases: Set<Long> get() = pendingReleases.toSet()

    val isEmpty: Boolean get() = pendingDeletes.isEmpty() && pendingReleases.isEmpty()

    fun delete(ids: List<Long>, op: (List<Long>) -> Boolean): Boolean {
        if (ids.isEmpty()) return true
        val ok = op(ids)
        if (ok) pendingDeletes.removeAll(ids.toSet()) else pendingDeletes.addAll(ids)
        pendingReleases.removeAll(ids.toSet())
        return ok
    }

    fun release(ids: List<Long>, op: (List<Long>) -> Boolean): Boolean {
        val open = ids.filter { it !in pendingDeletes }
        if (open.isEmpty()) return true
        val ok = op(open)
        if (ok) pendingReleases.removeAll(open.toSet()) else pendingReleases.addAll(open)
        return ok
    }

    fun retain(existing: Set<Long>) {
        pendingDeletes.retainAll(existing)
        pendingReleases.retainAll(existing)
    }

    val ids: List<Long> get() = (pendingDeletes + pendingReleases).sorted()

    fun retry(delete: (List<Long>) -> Boolean, release: (List<Long>) -> Boolean): Int {
        var settled = 0
        if (pendingDeletes.isNotEmpty() && delete(pendingDeletes.sorted())) {
            settled += pendingDeletes.size
            pendingDeletes.clear()
        }
        if (pendingReleases.isNotEmpty() && release(pendingReleases.sorted())) {
            settled += pendingReleases.size
            pendingReleases.clear()
        }
        return settled
    }
}
