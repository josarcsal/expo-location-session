package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import com.margelo.nitro.locationsession.core.BatchConfig
import com.margelo.nitro.locationsession.core.PurgeResult
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.QueueStatus
import com.margelo.nitro.locationsession.core.QueueTarget
import com.margelo.nitro.locationsession.core.QueuedRow
import com.margelo.nitro.locationsession.core.parseObject
import com.margelo.nitro.locationsession.core.trimCount
import java.io.File
import org.json.JSONObject

class QueueStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class QueueStore(context: Context, file: File) {
    private val helper = object : SQLiteOpenHelper(context, file.absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS fixes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    fix_ts INTEGER NOT NULL,
                    body TEXT NOT NULL,
                    state INTEGER NOT NULL DEFAULT 0,
                    attempts INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS fixes_state_id ON fixes (state, id)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS targets (
                    session_id TEXT PRIMARY KEY,
                    url TEXT NOT NULL,
                    headers TEXT NOT NULL,
                    refresh TEXT,
                    batch TEXT,
                    extras TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    max_age_ms INTEGER NOT NULL,
                    ended INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA busy_timeout=5000", null).close()
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private val db: SQLiteDatabase

    init {
        helper.setWriteAheadLoggingEnabled(true)
        db = try {
            helper.writableDatabase
        } catch (e: Exception) {
            helper.close()
            throw QueueStoreException(e.message ?: "open failed", e)
        }
    }

    @Synchronized
    fun close() = helper.close()

    @Synchronized
    fun recoverInFlight(): Int = attempt(-1) { run("UPDATE fixes SET state = 0 WHERE state = 1") }

    @Synchronized
    fun saveTarget(t: QueueTarget) {
        val batch = t.batch?.let { JSONObject().put("maxSize", it.maxSize).put("rootProperty", it.rootProperty).toString() }
        val refresh = t.refreshUrl?.let { url ->
            val o = JSONObject().put("url", url)
            if (t.refreshBodyJson != null) o.put("body", t.refreshBodyJson)
            o.toString()
        }
        try {
            run(
                """
                INSERT INTO targets (session_id, url, headers, refresh, batch, extras, mode, max_age_ms, ended)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET url = excluded.url, headers = excluded.headers, refresh = excluded.refresh,
                    batch = excluded.batch, extras = excluded.extras, mode = excluded.mode, max_age_ms = excluded.max_age_ms, ended = excluded.ended
                """.trimIndent(),
                t.sessionId, t.url, "target.${t.sessionId}", refresh, batch, t.extrasJson, t.mode.wire, t.maxAgeMs, if (t.ended) 1L else 0L
            )
        } catch (e: Exception) {
            throw QueueStoreException(e.message ?: "save failed", e)
        }
    }

    @Synchronized
    fun target(sessionId: String): QueueTarget? = attempt(null) { readTarget(sessionId) }

    @Synchronized
    fun readTarget(sessionId: String): QueueTarget? = try {
        query("SELECT url, refresh, batch, extras, mode, max_age_ms, ended FROM targets WHERE session_id = ?", sessionId) { c ->
            val refresh = c.getStringOrNull(1)?.let(::parseObject)
            val mode = c.getStringOrNull(4)?.let(QueueMode::fromWire) ?: return@query null
            val rawBatch = c.getStringOrNull(2)
            val batch = rawBatch?.let(::batchOf)
            if (rawBatch != null && batch == null) return@query null
            QueueTarget(
                sessionId = sessionId,
                url = c.getStringOrNull(0) ?: "",
                mode = mode,
                batch = batch,
                extrasJson = c.getStringOrNull(3) ?: "{}",
                maxAgeMs = c.getLong(5),
                refreshUrl = refresh?.optString("url")?.takeIf { refresh.has("url") },
                refreshBodyJson = refresh?.optString("body")?.takeIf { refresh.has("body") },
                ended = c.getLong(6) != 0L
            )
        }.firstOrNull()
    } catch (e: Exception) {
        throw QueueStoreException(e.message ?: "read failed", e)
    }

    @Synchronized
    fun insert(sessionId: String, uuid: String, fixTs: Long, body: String, mode: QueueMode, maxRecords: Int): Int {
        var trimmed = 0
        transaction {
            if (mode == QueueMode.LATEST) run("DELETE FROM fixes WHERE session_id = ? AND state = 0", sessionId)
            run("INSERT INTO fixes (uuid, session_id, fix_ts, body, state, attempts) VALUES (?, ?, ?, ?, 0, 0)", uuid, sessionId, fixTs, body)
            if (mode == QueueMode.ALL) {
                val total = scalar("SELECT COUNT(*) FROM fixes") ?: 0L
                val excess = trimCount(total.toInt(), maxRecords)
                if (excess > 0) {
                    trimmed = run("DELETE FROM fixes WHERE id IN (SELECT id FROM fixes WHERE state = 0 ORDER BY id LIMIT ?)", excess.toLong())
                }
            }
        }?.let { throw QueueStoreException(it.message ?: "insert failed", it) }
        return trimmed
    }

    @Synchronized
    fun claim(sessionId: String, limit: Int): List<QueuedRow> {
        var rows: List<QueuedRow> = emptyList()
        val failure = transaction {
            val found = query(
                "SELECT id, uuid, session_id, fix_ts, body, attempts FROM fixes WHERE session_id = ? AND state = 0 ORDER BY id LIMIT ?",
                sessionId, limit.toLong()
            ) { it.toRow() }
            for (row in found) run("UPDATE fixes SET state = 1, attempts = attempts + 1 WHERE id = ?", row.id)
            rows = found.map { it.copy(attempts = it.attempts + 1) }
        }
        return if (failure == null) rows else emptyList()
    }

    @Synchronized
    fun delete(ids: List<Long>): Boolean = updateIds(ids, "DELETE FROM fixes WHERE id IN")

    @Synchronized
    fun release(ids: List<Long>): Boolean = updateIds(ids, "UPDATE fixes SET state = 0 WHERE id IN")

    @Synchronized
    fun existing(ids: List<Long>, sessionId: String? = null): Set<Long> = attempt(ids.toSet()) {
        ids.chunked(500).flatMap { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            if (sessionId == null) {
                query("SELECT id FROM fixes WHERE id IN ($marks)", *chunk.toTypedArray()) { it.getLong(0) }
            } else {
                query("SELECT id FROM fixes WHERE id IN ($marks) AND session_id = ?", *chunk.toTypedArray(), sessionId) { it.getLong(0) }
            }
        }.toSet()
    }

    @Synchronized
    fun deleteSession(sessionId: String): Int {
        var rows = 0
        val failure = transaction {
            rows = run("DELETE FROM fixes WHERE session_id = ?", sessionId)
            run("DELETE FROM targets WHERE session_id = ?", sessionId)
        }
        return if (failure == null) rows else -1
    }

    @Synchronized
    fun markEnded(sessionId: String) {
        attempt(0) { run("UPDATE targets SET ended = 1 WHERE session_id = ?", sessionId) }
    }

    @Synchronized
    fun endLeftovers(running: String? = null): List<String> {
        var removed: List<String> = emptyList()
        val failure = transaction {
            removed = query("SELECT session_id FROM targets WHERE ended = 0 AND mode = 'latest' AND session_id IS NOT ? ORDER BY session_id", running) {
                it.getString(0)
            }
            for (id in removed) {
                run("DELETE FROM fixes WHERE session_id = ?", id)
                run("DELETE FROM targets WHERE session_id = ?", id)
            }
            run("UPDATE targets SET ended = 1 WHERE ended = 0 AND session_id IS NOT ?", running)
        }
        return if (failure == null) removed else emptyList()
    }

    @Synchronized
    fun purge(nowMs: Long): PurgeResult {
        var result = PurgeResult(0, emptyList())
        val failure = transaction {
            val aged = run(
                """
                DELETE FROM fixes WHERE state = 0 AND session_id IN (SELECT session_id FROM targets WHERE mode = 'all')
                AND ? - fix_ts > (SELECT max_age_ms FROM targets WHERE targets.session_id = fixes.session_id)
                """.trimIndent(),
                nowMs
            )
            val badMode = query("SELECT session_id FROM targets WHERE mode NOT IN ('latest', 'all') ORDER BY session_id") { it.getString(0) }
            val badBatch = query("SELECT session_id, batch FROM targets WHERE batch IS NOT NULL ORDER BY session_id") { it.getString(0) to it.getString(1) }
                .filter { batchOf(it.second) == null }
                .map { it.first }
            val unreadable = (badMode + badBatch).distinct().sorted()
            for (id in unreadable) run("DELETE FROM targets WHERE session_id = ?", id)
            val orphans = run("DELETE FROM fixes WHERE state = 0 AND session_id NOT IN (SELECT session_id FROM targets)")
            val removed = query(
                "SELECT session_id FROM targets WHERE ended = 1 AND session_id NOT IN (SELECT session_id FROM fixes) ORDER BY session_id"
            ) { it.getString(0) }
            for (id in removed) run("DELETE FROM targets WHERE session_id = ?", id)
            result = PurgeResult(aged + orphans, unreadable + removed)
        }
        return if (failure == null) result else PurgeResult(0, emptyList())
    }

    @Synchronized
    fun nextSession(running: String?): String? = attempt(null) {
        if (running != null && (scalar("SELECT COUNT(*) FROM fixes WHERE session_id = ? AND state = 0", running) ?: 0L) > 0L) {
            running
        } else {
            query("SELECT session_id FROM fixes WHERE state = 0 ORDER BY id LIMIT 1") { it.getString(0) }.firstOrNull()
        }
    }

    @Synchronized
    fun latestPending(sessionId: String): QueuedRow? = attempt(null) { newestPending(sessionId) }

    @Synchronized
    fun newestPending(sessionId: String): QueuedRow? = try {
        query(
            "SELECT id, uuid, session_id, fix_ts, body, attempts FROM fixes WHERE session_id = ? AND state = 0 ORDER BY id DESC LIMIT 1",
            sessionId
        ) { it.toRow() }.firstOrNull()
    } catch (e: Exception) {
        throw QueueStoreException(e.message ?: "read failed", e)
    }

    @Synchronized
    fun status(excluding: Set<Long> = emptySet()): QueueStatus = attempt(QueueStatus(0, null, 0)) {
        val filter = if (excluding.isEmpty()) "" else " WHERE id NOT IN (${excluding.sorted().joinToString(",")})"
        query("SELECT COUNT(*), MIN(fix_ts), COUNT(DISTINCT session_id) FROM fixes$filter") { c ->
            QueueStatus(c.getInt(0), if (c.isNull(1)) null else c.getLong(1), c.getInt(2))
        }.first()
    }

    @Synchronized
    fun clear(): List<String> {
        var removed: List<String> = emptyList()
        val failure = transaction {
            run("DELETE FROM fixes")
            removed = query("SELECT session_id FROM targets WHERE ended = 1 ORDER BY session_id") { it.getString(0) }
            run("DELETE FROM targets WHERE ended = 1")
        }
        return if (failure == null) removed else emptyList()
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toRow() = QueuedRow(getLong(0), getString(1), getString(2), getLong(3), getString(4), getInt(5))

    private fun batchOf(raw: String): BatchConfig? =
        parseObject(raw)?.let { runCatching { BatchConfig(it.getInt("maxSize"), it.getString("rootProperty")) }.getOrNull() }

    private fun <T> attempt(fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        fallback
    }

    private fun bind(p: SQLiteProgram, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> p.bindNull(idx)
                is String -> p.bindString(idx, a)
                is Long -> p.bindLong(idx, a)
                is Int -> p.bindLong(idx, a.toLong())
                is Double -> p.bindDouble(idx, a)
                else -> throw IllegalArgumentException("unsupported bind")
            }
        }
    }

    private fun run(sql: String, vararg args: Any?): Int {
        val st = db.compileStatement(sql)
        try {
            bind(st, args)
            return st.executeUpdateDelete()
        } finally {
            st.close()
        }
    }

    private fun <T> query(sql: String, vararg args: Any?, map: (Cursor) -> T): List<T> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, table, q ->
            bind(q, args)
            SQLiteCursor(driver, table, q)
        }
        db.rawQueryWithFactory(factory, sql, null, "").use { c ->
            val out = ArrayList<T>()
            while (c.moveToNext()) out.add(map(c))
            return out
        }
    }

    private fun scalar(sql: String, vararg args: Any?): Long? = query(sql, *args) { it.getLong(0) }.firstOrNull()

    private fun transaction(body: () -> Unit): Exception? {
        try {
            db.beginTransactionNonExclusive()
        } catch (e: Exception) {
            return e
        }
        var failure: Exception? = null
        try {
            body()
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            failure = e
        } finally {
            try {
                db.endTransaction()
            } catch (e: Exception) {
                if (failure == null) failure = e
            }
        }
        return failure
    }

    private fun updateIds(ids: List<Long>, prefix: String): Boolean {
        if (ids.isEmpty()) return true
        return transaction {
            for (chunk in ids.chunked(500)) {
                run("$prefix (${chunk.joinToString(",") { "?" }})", *chunk.toTypedArray())
            }
        } == null
    }
}
