package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.locationsession.core.Battery
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.FixRecord
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.QueuedRow
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import com.margelo.nitro.locationsession.core.SyncAction
import com.margelo.nitro.locationsession.core.UploadOutcome
import com.margelo.nitro.locationsession.core.backoffMs
import com.margelo.nitro.locationsession.core.batchLimit
import com.margelo.nitro.locationsession.core.buildElement
import com.margelo.nitro.locationsession.core.buildRequestBody
import com.margelo.nitro.locationsession.core.FINAL_UPLOAD_BUDGET_MS
import com.margelo.nitro.locationsession.core.shouldUploadNow
import com.margelo.nitro.locationsession.core.parseRefreshResponse
import com.margelo.nitro.locationsession.core.refreshRequestBody
import com.margelo.nitro.locationsession.core.syncAction
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Uploader(
    context: Context,
    private val store: SessionStore,
    private val queue: QueueDatabase,
    private val credentials: TargetCredentials,
    private val events: EventBus
) {
    var onStop: ((StopReason) -> Unit)? = null

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "expo-location-session.upload") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "expo-location-session.upload-watchdog") }

    private var config: SessionConfig? = null
    var lastSuccessMs: Long? = null
        private set
    private var attempt = 0
    private var inFlight = false
    private var inFlightRows: List<Long> = emptyList()
    private var refreshing = false
    private val refreshed = mutableSetOf<String>()
    @Volatile private var connection: HttpURLConnection? = null
    private var timer: Runnable? = null
    private var flushDeadline: Runnable? = null
    private var sendWatchdog: Runnable? = null
    @Volatile private var epoch = 0
    private var flushCompletion: (() -> Unit)? = null
    private val settledWaiters = mutableListOf<() -> Unit>()

    val isBusy: Boolean get() = inFlight || flushCompletion != null

    fun begin(c: SessionConfig, pending: Fix?, lastSuccessMs: Long?) {
        assertMain()
        reset()
        config = c
        this.lastSuccessMs = lastSuccessMs
        if (c.sync.mode != QueueMode.LATEST) return
        val fix = pending?.let(::sanitized) ?: return
        val q = queue.store() ?: return
        if (q.latestPending(c.id) != null) return
        enqueue(fix, c)
    }

    fun reset() {
        assertMain()
        epoch += 1
        if (inFlightRows.isNotEmpty()) {
            val c = config
            if (c != null) giveBack(inFlightRows, c) else queue.store()?.release(inFlightRows)
        }
        inFlightRows = emptyList()
        inFlight = false
        refreshing = false
        refreshed.clear()
        connection?.let { conn -> watchdog.execute { runCatching { conn.disconnect() } } }
        connection = null
        cancelTimer()
        cancelFlushDeadline()
        cancelSendWatchdog()
        config = null
        lastSuccessMs = null
        attempt = 0
        flushCompletion = null
        notifySettled()
    }

    fun put(fix: Fix) {
        assertMain()
        val c = config ?: return
        val trimmed = enqueue(fix, c)
        store.pendingFix = fix
        if (c.sync.mode == QueueMode.ALL && trimmed > 0) {
            events.emit(
                "error",
                JSONObject()
                    .put("code", "QUEUE_TRIMMED")
                    .put("message", "Queue full; $trimmed oldest location${if (trimmed == 1) "" else "s"} dropped")
                    .put("sessionId", c.id)
            )
        }
    }

    fun pump() {
        assertMain()
        val c = config ?: return
        if (inFlight || flushCompletion != null || !hasPending(c)) return
        refreshed.clear()
        val now = nowMs()
        if (shouldUploadNow(lastSuccessMs, now, c.uploadMinIntervalMs, false)) {
            send()
        } else {
            val last = lastSuccessMs ?: return
            val wait = last + c.uploadMinIntervalMs - now
            ElsLog.debug("upload", "throttled", mapOf("waitMs" to wait, "generation" to generation(c), "minIntervalMs" to c.uploadMinIntervalMs), c.id)
            startTimer(wait) { pump() }
        }
    }

    fun flush(completion: () -> Unit) {
        assertMain()
        val c = config
        if (c == null || (!inFlight && !hasPending(c))) {
            ElsLog.debug("upload", "flush skipped, nothing pending", sessionId = c?.id)
            completion()
            return
        }
        ElsLog.debug("upload", "flush start", mapOf("generation" to generation(c), "inFlight" to inFlight), c.id)
        flushCompletion = completion
        cancelTimer()
        val r = Runnable {
            flushDeadline = null
            flushDeadlineReached()
        }
        flushDeadline = r
        main.postDelayed(r, FINAL_UPLOAD_BUDGET_MS)
        if (!inFlight) {
            refreshed.clear()
            send()
        }
    }

    fun whenSettled(callback: () -> Unit) {
        assertMain()
        if (isBusy) settledWaiters.add(callback) else callback()
    }

    private fun notifySettled() {
        if (isBusy || settledWaiters.isEmpty()) return
        val waiters = settledWaiters.toList()
        settledWaiters.clear()
        waiters.forEach { it() }
    }

    private fun enqueue(fix: Fix, c: SessionConfig): Int {
        val uuid = UUID.randomUUID().toString()
        val body = try {
            when (c.sync.mode) {
                QueueMode.LATEST -> encodeFix(fix)
                QueueMode.ALL -> buildElement(FixRecord(uuid, c.id, fix, battery()), c.sync, c.uploadExtrasJson)
            }
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            ElsLog.debug("queue", "could not encode location", mapOf("fixTimestamp" to fix.timestampMs), c.id, level = "warn")
            return 0
        }
        val q = queue.store()
        if (q == null) {
            ElsLog.debug("queue", "database unavailable; location dropped", mapOf("fixTimestamp" to fix.timestampMs), c.id, level = "warn")
            return 0
        }
        return try {
            val trimmed = q.insert(c.id, uuid, fix.timestampMs, body, c.sync.mode, c.sync.maxRecords)
            ElsLog.debug("queue", "inserted", mapOf("mode" to c.sync.mode.wire, "fixTimestamp" to fix.timestampMs, "trimmed" to trimmed), c.id)
            if (trimmed > 0) {
                ElsLog.debug("queue", "trimmed", mapOf("rows" to trimmed, "maxRecords" to c.sync.maxRecords), c.id, level = "warn")
            }
            trimmed
        } catch (e: QueueStoreException) {
            ElsLog.debug("queue", "insert failed", mapOf("error" to e.message), c.id, level = "warn")
            0
        }
    }

    private fun generation(c: SessionConfig): Long = queue.store()?.latestPending(c.id)?.id ?: 0L

    private fun hasPending(c: SessionConfig): Boolean = queue.store()?.latestPending(c.id) != null

    private fun syncPendingFix(c: SessionConfig) {
        if (c.sync.mode != QueueMode.LATEST) return
        val q = queue.store() ?: return
        store.pendingFix = q.latestPending(c.id)?.let { decodeFix(it.body) }
    }

    private fun giveBack(ids: List<Long>, c: SessionConfig) {
        val q = queue.store() ?: return
        if (c.sync.mode == QueueMode.LATEST && q.latestPending(c.id) != null) {
            q.delete(ids)
        } else {
            q.release(ids)
        }
    }

    private fun scheduleFlushRetry(delayMs: Long) {
        val c = config ?: return
        val remaining = c.expiresAtMs - nowMs()
        if (remaining <= 0) {
            completeFlush()
            return
        }
        startTimer(minOf(delayMs, remaining)) {
            val current = config ?: return@startTimer
            if (nowMs() >= current.expiresAtMs) completeFlush() else send()
        }
    }

    private fun startTimer(delayMs: Long, fire: () -> Unit) {
        cancelTimer()
        val r = Runnable {
            timer = null
            fire()
        }
        timer = r
        main.postDelayed(r, maxOf(0L, delayMs))
    }

    private fun cancelTimer() {
        timer?.let { main.removeCallbacks(it) }
        timer = null
    }

    private fun cancelFlushDeadline() {
        flushDeadline?.let { main.removeCallbacks(it) }
        flushDeadline = null
    }

    private fun cancelSendWatchdog() {
        sendWatchdog?.let { main.removeCallbacks(it) }
        sendWatchdog = null
    }

    private fun flushDeadlineReached() {
        if (flushCompletion == null) return
        ElsLog.info(
            "upload",
            "final upload deadline reached",
            mapOf("pending" to (config?.let(::hasPending) ?: false), "inFlight" to inFlight),
            config?.id,
            level = "warn"
        )
        cancelTimer()
        completeFlush()
    }

    private fun completeFlush() {
        cancelFlushDeadline()
        val done = flushCompletion
        if (done != null) ElsLog.debug("upload", "flush end", mapOf("pending" to (config?.let(::hasPending) ?: false)), config?.id)
        flushCompletion = null
        done?.invoke()
        notifySettled()
    }

    private fun continueAfterSend(c: SessionConfig) {
        if (flushCompletion != null) {
            if (hasPending(c)) send() else completeFlush()
        } else if (c.sync.mode == QueueMode.ALL && hasPending(c)) {
            send()
        } else {
            pump()
        }
    }

    private fun send() {
        val c = config
        val q = queue.store()
        if (c == null || q == null) {
            completeFlush()
            return
        }
        cancelTimer()
        epoch += 1
        var rows = q.claim(c.id, if (c.sync.mode == QueueMode.LATEST) 1 else batchLimit(c.sync))
        if (c.sync.mode == QueueMode.LATEST) {
            while (rows.isNotEmpty() && q.latestPending(c.id) != null) {
                val row = rows.first()
                q.delete(listOf(row.id))
                ElsLog.debug("queue", "superseded row deleted", mapOf("fixTimestamp" to row.fixTs), c.id)
                rows = q.claim(c.id, 1)
            }
        }
        if (rows.isEmpty()) {
            completeFlush()
            return
        }
        val elements = ArrayList<String>()
        val sent = ArrayList<QueuedRow>()
        val unreadable = ArrayList<QueuedRow>()
        for (row in rows) {
            val element = element(row, c)
            if (element != null) {
                elements.add(element)
                sent.add(row)
            } else {
                unreadable.add(row)
            }
        }
        if (unreadable.isNotEmpty()) {
            q.delete(unreadable.map { it.id })
            for (row in unreadable) {
                ElsLog.debug("queue", "unreadable row deleted", mapOf("fixTimestamp" to row.fixTs, "attempts" to row.attempts), c.id, level = "warn")
                events.emit(
                    "error",
                    JSONObject().put("code", "UPLOAD_DROPPED").put("message", "Queued location unreadable; location dropped").put("sessionId", c.id)
                )
            }
            syncPendingFix(c)
        }
        if (sent.isEmpty()) {
            continueAfterSend(c)
            return
        }
        val ids = sent.map { it.id }
        val body = buildRequestBody(c.id, elements, c.sync, c.uploadExtrasJson)
        val headers = credentials.load(c.id)?.headers ?: emptyMap()
        val sentEpoch = epoch
        inFlight = true
        inFlightRows = ids
        val startedAt = nowMs()
        ElsLog.debug(
            "upload",
            "attempt",
            mapOf(
                "generation" to ids.last(),
                "attempt" to attempt,
                "bytes" to body.length,
                "flush" to (flushCompletion != null),
                "rows" to ids.size,
                "fixTimestamp" to sent.first().fixTs
            ),
            c.id
        )
        startSendWatchdog(sentEpoch, startedAt)
        val url = c.uploadUrl
        executor.execute {
            val status = post(url, headers, body, sentEpoch, c.id, "upload")?.status
            main.post { finish(sentEpoch, status, nowMs() - startedAt) }
        }
    }

    private fun element(row: QueuedRow, c: SessionConfig): String? = try {
        when (c.sync.mode) {
            QueueMode.LATEST -> decodeFix(row.body)?.let {
                buildElement(FixRecord(row.uuid, c.id, it, battery()), c.sync, c.uploadExtrasJson)
            }
            QueueMode.ALL -> {
                val tokener = JSONTokener(row.body)
                tokener.nextValue()
                if (tokener.more()) null else row.body
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun startSendWatchdog(sentEpoch: Int, startedAt: Long) {
        cancelSendWatchdog()
        val r = Runnable {
            sendWatchdog = null
            if (sentEpoch == epoch) abandonInFlight(nowMs() - startedAt)
        }
        sendWatchdog = r
        main.postDelayed(r, (TIMEOUT_MS + 5_000).toLong())
    }

    private fun abandonInFlight(durationMs: Long) {
        epoch += 1
        connection?.let { conn -> watchdog.execute { runCatching { conn.disconnect() } } }
        if (refreshing) {
            ElsLog.info("auth", "refresh watchdog fired", mapOf("durationMs" to durationMs), config?.id, level = "warn")
            completeRefresh(null, "", durationMs)
        } else {
            ElsLog.info("upload", "upload watchdog fired", mapOf("durationMs" to durationMs), config?.id, level = "warn")
            complete(null, durationMs)
        }
    }

    private class Response(val status: Int, val body: String)

    private fun post(url: String, headers: Map<String, String>, body: String, sentEpoch: Int, sessionId: String, tag: String): Response? {
        if (sentEpoch != epoch) return null
        var conn: HttpURLConnection? = null
        var deadline: ScheduledFuture<*>? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            val watched = conn
            deadline = watchdog.schedule({ runCatching { watched.disconnect() } }, TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            if (sentEpoch != epoch) return null
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = runCatching {
                (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull() ?: ""
            Response(status, text)
        } catch (e: Exception) {
            ElsLog.info(tag, "$tag failed: ${e.javaClass.simpleName}", sessionId = sessionId, level = "warn")
            null
        } finally {
            deadline?.cancel(false)
            conn?.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun finish(sentEpoch: Int, status: Int?, durationMs: Long) {
        if (sentEpoch != epoch) {
            ElsLog.debug("upload", "stale response ignored", mapOf("status" to status))
            return
        }
        complete(status, durationMs)
    }

    private fun complete(status: Int?, durationMs: Long) {
        val rows = inFlightRows
        inFlight = false
        inFlightRows = emptyList()
        cancelSendWatchdog()
        val c = config
        if (c == null) {
            if (rows.isNotEmpty()) queue.store()?.release(rows)
            notifySettled()
            return
        }
        val at = nowMs()
        val action = syncAction(status, ended = false, refreshConfigured = c.sync.refresh != null, alreadyRefreshed = c.id in refreshed)
        val payload = JSONObject().put("ok", action == SyncAction.DeleteSent).put("at", at).put("sessionId", c.id)
        status?.let { payload.put("status", it) }
        if (c.sync.mode == QueueMode.ALL) payload.put("count", rows.size)
        events.emit("upload", payload)
        val outcome = outcomeName(action)
        val retrying = action == SyncAction.Retry || action == SyncAction.DropSession
        val detail = mutableMapOf<String, Any?>(
            "generation" to (rows.lastOrNull() ?: 0L),
            "outcome" to outcome,
            "durationMs" to durationMs,
            "status" to status,
            "rows" to rows.size
        )
        if (retrying) detail["backoffMs"] = backoffMs(attempt)
        ElsLog.info("upload", "upload status=${status ?: "none"} outcome=$outcome", detail, c.id)
        DebugLogger.onUploadCompleted()

        when (action) {
            SyncAction.DeleteSent -> {
                queue.store()?.delete(rows)
                syncPendingFix(c)
                lastSuccessMs = at
                store.lastUploadAtMs = at
                attempt = 0
            }
            SyncAction.DropSent -> {
                queue.store()?.delete(rows)
                syncPendingFix(c)
                attempt = 0
                val message = if (rows.size == 1) {
                    "Server responded ${status ?: 0}; location dropped"
                } else {
                    "Server responded ${status ?: 0}; ${rows.size} locations dropped"
                }
                events.emit("error", JSONObject().put("code", "UPLOAD_DROPPED").put("message", message).put("sessionId", c.id))
            }
            is SyncAction.Stop -> {
                giveBack(rows, c)
                onStop?.invoke(action.reason)
                return
            }
            SyncAction.RefreshAndRetry -> {
                giveBack(rows, c)
                refresh(c)
                return
            }
            SyncAction.Retry, SyncAction.DropSession -> {
                giveBack(rows, c)
                scheduleRetry()
                notifySettled()
                return
            }
        }
        continueAfterSend(c)
        notifySettled()
    }

    private fun scheduleRetry() {
        val delay = backoffMs(attempt)
        attempt += 1
        if (flushCompletion != null) scheduleFlushRetry(delay) else startTimer(delay) { pump() }
    }

    private fun refresh(c: SessionConfig) {
        refreshed.add(c.id)
        epoch += 1
        val r = c.sync.refresh
        if (r == null) {
            completeRefresh(null, "", 0L)
            return
        }
        val headers = credentials.load(c.id)?.refreshHeaders ?: r.headers
        val body = refreshRequestBody(r.bodyJson, c.id)
        val sentEpoch = epoch
        inFlight = true
        refreshing = true
        val startedAt = nowMs()
        ElsLog.debug("auth", "refresh attempt", mapOf("headers" to headers.keys.sorted()), c.id)
        startSendWatchdog(sentEpoch, startedAt)
        executor.execute {
            val response = post(r.url, headers, body, sentEpoch, c.id, "auth")
            main.post {
                if (sentEpoch != epoch) {
                    ElsLog.debug("auth", "stale refresh response ignored", mapOf("status" to response?.status))
                } else {
                    completeRefresh(response?.status, response?.body ?: "", nowMs() - startedAt)
                }
            }
        }
    }

    private fun completeRefresh(status: Int?, body: String, durationMs: Long) {
        inFlight = false
        refreshing = false
        cancelSendWatchdog()
        val c = config
        if (c == null) {
            notifySettled()
            return
        }
        val result = parseRefreshResponse(status, body)
        if (result == null) {
            val message = if (status != null) "Refresh rejected: server responded $status without usable headers" else "Refresh transport failure: no response"
            refreshFailed(c, status != null, message, mapOf("status" to status, "durationMs" to durationMs))
            return
        }
        val refreshHeaders = result.refreshHeaders ?: credentials.load(c.id)?.refreshHeaders ?: c.sync.refresh?.headers ?: emptyMap()
        if (!credentials.save(c.id, result.headers, refreshHeaders)) {
            refreshFailed(c, false, "Could not store refreshed credentials", mapOf("status" to status, "durationMs" to durationMs))
            return
        }
        val data = mutableMapOf<String, Any?>("status" to status, "durationMs" to durationMs, "headers" to result.headers.keys.sorted())
        result.refreshHeaders?.let { data["refreshHeaders"] = it.keys.sorted() }
        ElsLog.debug("auth", "refresh succeeded", data, c.id)
        send()
        notifySettled()
    }

    private fun refreshFailed(c: SessionConfig, rejected: Boolean, message: String, data: Map<String, Any?>) {
        ElsLog.debug("auth", if (rejected) "refresh rejected" else "refresh failed", data, c.id, level = "warn")
        events.emit("error", JSONObject().put("code", "AUTH_REFRESH_FAILED").put("message", message).put("sessionId", c.id))
        if (rejected) {
            val action = syncAction(401, ended = false, refreshConfigured = true, alreadyRefreshed = true)
            if (action is SyncAction.Stop) {
                onStop?.invoke(action.reason)
                return
            }
        } else {
            refreshed.remove(c.id)
        }
        scheduleRetry()
        notifySettled()
    }

    private fun outcomeName(action: SyncAction): String = when (action) {
        SyncAction.DeleteSent -> UploadOutcome.Success.javaClass.simpleName
        SyncAction.DropSent -> UploadOutcome.Drop.javaClass.simpleName
        SyncAction.Retry -> UploadOutcome.Retry.javaClass.simpleName
        is SyncAction.Stop -> UploadOutcome.Stop::class.java.simpleName
        else -> action.javaClass.simpleName
    }

    private fun battery(): Battery? {
        val intent: Intent = try {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            null
        } ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val fraction = level.toDouble() / scale
        if (!fraction.isFinite()) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(fraction.coerceIn(0.0, 1.0), charging)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}

fun nowMs(): Long = System.currentTimeMillis()

fun assertMain() {
    check(Looper.myLooper() == Looper.getMainLooper()) { "expo-location-session engine must run on the main thread" }
}
