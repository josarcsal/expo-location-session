package com.margelo.nitro.locationsession.engine

import android.content.Context
import com.margelo.nitro.locationsession.core.BoundedBuffer
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.QueueStatus
import com.margelo.nitro.locationsession.core.QueueTarget
import com.margelo.nitro.locationsession.core.UnsettledRows
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PendingInsert(
    val sessionId: String,
    val uuid: String,
    val fixTs: Long,
    val body: String,
    val mode: QueueMode,
    val maxRecords: Int
)

sealed class EnqueueResult {
    data class Stored(val trimmed: Int) : EnqueueResult()
    data class Buffered(val dropped: Boolean) : EnqueueResult()
    object Failed : EnqueueResult()
}

class QueueDatabase(context: Context, private val file: File, private val clock: () -> Long = ::nowMs) {
    private val appContext = context.applicationContext
    private val credentials = TargetCredentials(appContext)
    private var opened: QueueStore? = null
    private var failedAtMs: Long? = null
    private val buffer = BoundedBuffer<PendingInsert>(500)
    private var unsettled = UnsettledRows()
    private val unsavedTargets = sortedMapOf<String, QueueTarget>()
    private val undeletedSessions = sortedSetOf<String>()
    private var loggedUnavailable = false
    private var insertFailing = false
    @Volatile var unavailable = false
        private set

    var onDeletesSettled: (() -> Unit)? = null

    val isOpen: Boolean
        @Synchronized get() = opened != null

    val failing: Boolean
        @Synchronized get() = unavailable || insertFailing

    val buffered: Int
        @Synchronized get() = buffer.count

    @Synchronized
    fun store(): QueueStore? {
        opened?.let { return it }
        failedAtMs?.let { if (clock() - it < REOPEN_COOLDOWN_MS) return null }
        val openedAt = System.nanoTime()
        val recovering = !recovered.get()
        val s = try {
            file.parentFile?.mkdirs()
            QueueStore(appContext, file)
        } catch (e: Exception) {
            failedAtMs = clock()
            unavailable = true
            if (!loggedUnavailable) {
                loggedUnavailable = true
                ElsLog.info("queue", "database unavailable: ${e.javaClass.simpleName}", mapOf("error" to e.message), level = "warn")
            }
            return null
        }
        if (recovering) {
            val rows = s.recoverInFlight()
            if (rows < 0) {
                s.close()
                failedAtMs = clock()
                unavailable = true
                if (!loggedUnavailable) {
                    loggedUnavailable = true
                    ElsLog.debug("queue", "in-flight recovery failed; database unavailable", level = "warn")
                }
                return null
            }
            recovered.set(true)
            ElsLog.debug("queue", "recovered in-flight rows", mapOf("rows" to rows))
        }
        opened = s
        failedAtMs = null
        loggedUnavailable = false
        unavailable = false
        retrySessionDeletes(s)
        purge(s)
        if (recovering) {
            val durationMs = (System.nanoTime() - openedAt) / 1_000_000
            ElsLog.debug("queue", "queue opened in ${durationMs}ms", mapOf("durationMs" to durationMs))
        }
        return s
    }

    fun purge() {
        store()?.let(::purge)
    }

    private fun purge(s: QueueStore) {
        val purged = s.purge(nowMs())
        if (purged.rowsDeleted > 0) prune(s)
        purged.targetsRemoved.forEach(credentials::remove)
        if (purged.rowsDeleted > 0 || purged.targetsRemoved.isNotEmpty()) {
            ElsLog.debug("queue", "purged", mapOf("rows" to purged.rowsDeleted, "targets" to purged.targetsRemoved.size))
        }
    }

    @Synchronized
    fun enqueue(p: PendingInsert): EnqueueResult {
        val s = store() ?: return keep(p)
        settle(s)
        val flushed = flushBuffer(s)
        if (!flushed.first || !saveTargets(s)) {
            insertFailing = true
            return keep(p)
        }
        return try {
            val trimmed = s.insert(p.sessionId, p.uuid, p.fixTs, p.body, p.mode, p.maxRecords)
            insertFailing = false
            ElsLog.debug("queue", "inserted", mapOf("mode" to p.mode.wire, "fixTimestamp" to p.fixTs, "trimmed" to trimmed), p.sessionId)
            if (trimmed > 0) {
                ElsLog.debug("queue", "trimmed", mapOf("rows" to trimmed, "maxRecords" to p.maxRecords), p.sessionId, level = "warn")
            }
            EnqueueResult.Stored(flushed.second + trimmed)
        } catch (e: QueueStoreException) {
            if (!insertFailing) {
                insertFailing = true
                ElsLog.debug("queue", "insert failed", mapOf("error" to e.message), p.sessionId, level = "warn")
            }
            keep(p)
        }
    }

    @Synchronized
    fun prepareSend(): Int {
        val s = store() ?: return 0
        settle(s)
        return flushBuffer(s).second
    }

    @Synchronized
    fun probe(): Int {
        if (!failing) return 0
        val s = store() ?: return 0
        settle(s)
        return if (buffer.isEmpty) 0 else flushBuffer(s).second
    }

    @Synchronized
    fun status(hidden: Set<Long> = emptySet()): QueueStatus? = store()?.status(unsettled.deletes + hidden)

    @Synchronized
    fun hasUnsettledDeletes(sessionId: String): Boolean {
        val deletes = unsettled.deletes
        if (deletes.isEmpty()) return false
        val s = store() ?: return true
        return s.existing(deletes.sorted(), sessionId).isNotEmpty()
    }

    @Synchronized
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val s = store()
        if (!unsettled.delete(ids) { s?.delete(it) ?: false }) {
            ElsLog.debug("queue", "delete failed; rows kept as sent", mapOf("rows" to ids.size, "unsettled" to unsettled.deletes.size), level = "warn")
        }
    }

    @Synchronized
    fun release(ids: List<Long>) {
        if (ids.isEmpty()) return
        val s = store()
        if (!unsettled.release(ids) { s?.release(it) ?: false }) {
            ElsLog.debug("queue", "release failed; rows kept in flight", mapOf("rows" to ids.size, "unsettled" to unsettled.releases.size), level = "warn")
        }
    }

    @Synchronized
    fun saveTarget(t: QueueTarget) {
        unsavedTargets[t.sessionId] = t
        if (store()?.let(::saveTargets) != true) {
            ElsLog.debug("queue", "target save deferred", sessionId = t.sessionId, level = "warn")
        }
    }

    @Synchronized
    fun markEnded(sessionId: String) {
        unsavedTargets[sessionId]?.let { unsavedTargets[sessionId] = it.copy(ended = true) }
        store()?.markEnded(sessionId)
    }

    @Synchronized
    fun deleteSession(sessionId: String): Int {
        unsavedTargets.remove(sessionId)
        val buffered = buffer.removeAll { it.sessionId == sessionId }
        val s = store()
        val deleted = s?.deleteSession(sessionId) ?: -1
        if (s == null || deleted < 0) {
            keepUndeleted(sessionId)
            return buffered
        }
        undeletedSessions.remove(sessionId)
        prune(s)
        return buffered + deleted
    }

    @Synchronized
    fun clear(): List<String>? {
        buffer.drain()
        unsavedTargets.values.removeAll { it.ended }
        val s = store() ?: return null
        unsettled = UnsettledRows()
        return s.clear()
    }

    private fun keepUndeleted(sessionId: String) {
        undeletedSessions.add(sessionId)
        ElsLog.debug("queue", "session delete failed; retried when the database is available", sessionId = sessionId, level = "warn")
    }

    private fun retrySessionDeletes(s: QueueStore) {
        if (undeletedSessions.isEmpty()) return
        for (id in undeletedSessions.toList()) {
            if (s.deleteSession(id) >= 0) {
                undeletedSessions.remove(id)
                ElsLog.debug("queue", "session rows deleted on retry", sessionId = id)
            }
        }
        prune(s)
    }

    private fun keep(p: PendingInsert): EnqueueResult {
        if (p.mode != QueueMode.ALL) return EnqueueResult.Failed
        val dropped = buffer.append(p)
        ElsLog.debug("queue", "buffered", mapOf("buffered" to buffer.count, "dropped" to dropped), p.sessionId, level = if (dropped) "warn" else "debug")
        return EnqueueResult.Buffered(dropped)
    }

    private fun prune(s: QueueStore) {
        if (unsettled.isEmpty) return
        val deletes = unsettled.deletes.size
        unsettled.retain(s.existing(unsettled.ids))
        if (unsettled.deletes.size < deletes) onDeletesSettled?.invoke()
    }

    private fun settle(s: QueueStore) {
        retrySessionDeletes(s)
        if (unsettled.isEmpty) return
        val deletes = unsettled.deletes.size
        val settled = unsettled.retry({ s.delete(it) }, { s.release(it) })
        if (settled > 0) {
            ElsLog.debug("queue", "unsettled rows settled", mapOf("rows" to settled, "unsettled" to unsettled.deletes.size + unsettled.releases.size))
        }
        if (unsettled.deletes.size < deletes) onDeletesSettled?.invoke()
    }

    private fun saveTargets(s: QueueStore): Boolean {
        for (id in unsavedTargets.keys.toList()) {
            val t = unsavedTargets[id] ?: continue
            try {
                s.saveTarget(t)
                unsavedTargets.remove(id)
            } catch (e: QueueStoreException) {
                return false
            }
        }
        return true
    }

    private fun flushBuffer(s: QueueStore): Pair<Boolean, Int> {
        if (buffer.isEmpty) return true to 0
        if (!saveTargets(s)) {
            insertFailing = true
            return false to 0
        }
        var trimmed = 0
        val inserted = buffer.flush { p ->
            try {
                trimmed += s.insert(p.sessionId, p.uuid, p.fixTs, p.body, p.mode, p.maxRecords)
                true
            } catch (e: QueueStoreException) {
                false
            }
        }
        insertFailing = !buffer.isEmpty
        if (inserted > 0) {
            ElsLog.debug(
                "queue",
                "buffer drained",
                mapOf("rows" to inserted, "trimmed" to trimmed, "remaining" to buffer.count),
                level = if (buffer.isEmpty) "debug" else "warn"
            )
        }
        return buffer.isEmpty to trimmed
    }

    companion object {
        private const val REOPEN_COOLDOWN_MS = 5_000L
        private val recovered = AtomicBoolean(false)
        private var shared: QueueDatabase? = null

        @Synchronized
        fun shared(context: Context): QueueDatabase = shared ?: QueueDatabase(
            context,
            File(context.applicationContext.filesDir, "expo-location-session/queue.sqlite")
        ).also { shared = it }
    }
}
