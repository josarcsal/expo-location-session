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
import com.margelo.nitro.locationsession.core.QueueTarget
import com.margelo.nitro.locationsession.core.QueuedRow
import com.margelo.nitro.locationsession.core.RefreshConfig
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import com.margelo.nitro.locationsession.core.SyncAction
import com.margelo.nitro.locationsession.core.SyncConfig
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
    private class Target(val id: String, val url: String, val sync: SyncConfig, val extrasJson: String, val ended: Boolean)

    private class Waiter(val base: Int, val done: (Int) -> Unit)

    private class Pass(val waiters: List<Waiter>) {
        var requests = 0
    }

    private enum class PassEnd { DONE, RETRY, CANCELLED }

    var onStop: ((StopReason) -> Unit)? = null

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = ConnectivityMonitor(context)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "expo-location-session.upload") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "expo-location-session.upload-watchdog") }

    private var config: SessionConfig? = null
    var lastSuccessMs: Long? = null
        private set
    private var attempt = 0
    private var inFlight = false
    private var inFlightRows: List<Long> = emptyList()
    private var inFlightTarget: Target? = null
    private var pass: Pass? = null
    private val deferred = mutableListOf<Waiter>()
    private var dirty = false
    private var drained = 0
    private var drainBudget: Runnable? = null
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
        evaluateConnectivity()
        val fix = pending?.let(::sanitized)
        val q = queue.store()
        if (c.sync.mode == QueueMode.LATEST && fix != null && q != null && q.latestPending(c.id) == null) enqueue(fix, c)
        if (dirty) startPass(null, emptyList())
    }

    fun reset() {
        assertMain()
        epoch += 1
        if (inFlightRows.isNotEmpty()) {
            val t = inFlightTarget
            if (t != null) giveBack(inFlightRows, t) else queue.store()?.release(inFlightRows)
        }
        inFlightRows = emptyList()
        inFlightTarget = null
        inFlight = false
        pass?.let {
            pass = null
            deferred.addAll(0, it.waiters)
            dirty = true
        }
        cancelDrainBudget()
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
        evaluateConnectivity()
        notifySettled()
    }

    fun evaluateConnectivity() {
        assertMain()
        if (config != null || (queue.store()?.status()?.pending ?: 0) > 0) {
            connectivity.start { connectivityRegained() }
        } else {
            connectivity.stop()
        }
    }

    private fun connectivityRegained() {
        if (config == null && (queue.store()?.status()?.pending ?: 0) == 0) {
            evaluateConnectivity()
            return
        }
        ElsLog.debug(
            "sync",
            "connectivity regained",
            mapOf("running" to (config != null), "inFlight" to inFlight, "flush" to (flushCompletion != null), "attempt" to attempt),
            config?.id
        )
        attempt = 0
        val c = config
        if (c == null) {
            drain(null) {}
            return
        }
        if (inFlight) return
        cancelTimer()
        if (flushCompletion != null) send(running(c)) else pump()
    }

    fun drain(budgetMs: Long?, completion: (Int) -> Unit) {
        assertMain()
        val waiter = Waiter(drained, completion)
        if (inFlight || flushCompletion != null || pass != null) {
            dirty = true
            deferred.add(waiter)
            return
        }
        startPass(budgetMs, listOf(waiter))
    }

    fun cancelDrain(release: Boolean) {
        assertMain()
        abandonDrainRequest(release)
        if (config == null) cancelTimer()
        if (pass == null) pass = Pass(emptyList())
        endPass(PassEnd.CANCELLED)
    }

    private fun startPass(budgetMs: Long?, waiters: List<Waiter>) {
        dirty = false
        pass = Pass(waiters + deferred)
        deferred.clear()
        if (config == null) cancelTimer()
        refreshed.retainAll { it == config?.id }
        if (budgetMs != null) {
            val r = Runnable {
                drainBudget = null
                drainBudgetReached()
            }
            drainBudget = r
            main.postDelayed(r, maxOf(0L, budgetMs))
        }
        queue.purge()
        drainNext()
    }

    private fun drainNext() {
        if (pass == null) return
        val q = queue.store()
        val running = config?.id
        val id = if (flushCompletion == null) q?.nextSession(running) else null
        val t = if (id != null && id != running) q?.target(id) else null
        if (t == null || !t.ended) {
            endPass(PassEnd.DONE)
            return
        }
        send(ended(t))
    }

    private fun drainBudgetReached() {
        if (pass == null) return
        ElsLog.debug("sync", "drain budget reached", mapOf("inFlight" to inFlight))
        abandonDrainRequest(true)
        endPass(PassEnd.DONE)
    }

    private fun abandonDrainRequest(release: Boolean) {
        if (inFlightTarget?.ended != true) return
        epoch += 1
        if (release) {
            connection?.let { conn -> watchdog.execute { runCatching { conn.disconnect() } } }
            if (inFlightRows.isNotEmpty()) queue.store()?.release(inFlightRows)
        }
        inFlight = false
        inFlightRows = emptyList()
        inFlightTarget = null
        refreshing = false
        cancelSendWatchdog()
    }

    private fun endPass(end: PassEnd) {
        val p = pass ?: return
        pass = null
        cancelDrainBudget()
        val waiters = p.waiters.toMutableList()
        if (end != PassEnd.DONE) {
            dirty = false
            waiters.addAll(deferred)
            deferred.clear()
        }
        val total = drained
        if (end == PassEnd.RETRY && config == null) {
            val delay = backoffMs(attempt)
            attempt += 1
            ElsLog.debug("sync", "drain retry scheduled", mapOf("backoffMs" to delay))
            startTimer(delay) { drain(null) {} }
        }
        if (p.requests > 0) resumeRunning()
        if (dirty && !inFlight && flushCompletion == null) {
            startPass(null, emptyList())
        } else {
            evaluateConnectivity()
        }
        waiters.forEach { it.done(total - it.base) }
        notifySettled()
    }

    private fun cancelDrainBudget() {
        drainBudget?.let { main.removeCallbacks(it) }
        drainBudget = null
    }

    private fun resumeRunning() {
        val c = config ?: return
        if (inFlight || timer != null) return
        if (flushCompletion != null) send(running(c)) else pump()
    }

    private fun drainIfIdle() {
        val c = config ?: return
        if (inFlight || flushCompletion != null || pass != null || hasPending(c)) return
        if (!dirty && queue.store()?.nextSession(null) == null) return
        startPass(null, emptyList())
    }

    private fun running(c: SessionConfig) = Target(c.id, c.uploadUrl, c.sync, c.uploadExtrasJson, false)

    private fun ended(t: QueueTarget): Target {
        val refresh = t.refreshUrl?.let { RefreshConfig(it, bodyJson = t.refreshBodyJson) }
        return Target(t.sessionId, t.url, SyncConfig(mode = t.mode, maxAgeMs = t.maxAgeMs, batch = t.batch, refresh = refresh), t.extrasJson, true)
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
            send(running(c))
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
            send(running(c))
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

    private fun syncPendingFix(t: Target) {
        if (t.ended || t.sync.mode != QueueMode.LATEST) return
        val q = queue.store() ?: return
        store.pendingFix = q.latestPending(t.id)?.let { decodeFix(it.body) }
    }

    private fun giveBack(ids: List<Long>, t: Target) {
        val q = queue.store() ?: return
        if (t.sync.mode == QueueMode.LATEST && q.latestPending(t.id) != null) {
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
            if (nowMs() >= current.expiresAtMs) completeFlush() else send(running(current))
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

    private fun continueAfterSend(t: Target) {
        if (t.ended) {
            drainNext()
            return
        }
        val c = config ?: return
        if (flushCompletion != null) {
            if (hasPending(c)) send(t) else completeFlush()
        } else if (c.sync.mode == QueueMode.ALL && hasPending(c)) {
            send(t)
        } else {
            pump()
        }
        drainIfIdle()
    }

    private fun sendFailed(t: Target) {
        if (t.ended) endPass(PassEnd.DONE) else completeFlush()
    }

    private fun send(t: Target) {
        val q = queue.store()
        if (q == null) {
            sendFailed(t)
            return
        }
        if (!t.ended) cancelTimer()
        epoch += 1
        var rows = q.claim(t.id, if (t.sync.mode == QueueMode.LATEST) 1 else batchLimit(t.sync))
        if (t.sync.mode == QueueMode.LATEST) {
            while (rows.isNotEmpty() && q.latestPending(t.id) != null) {
                val row = rows.first()
                q.delete(listOf(row.id))
                ElsLog.debug("queue", "superseded row deleted", mapOf("fixTimestamp" to row.fixTs), t.id)
                rows = q.claim(t.id, 1)
            }
        }
        if (rows.isEmpty()) {
            sendFailed(t)
            return
        }
        val elements = ArrayList<String>()
        val sent = ArrayList<QueuedRow>()
        val unreadable = ArrayList<QueuedRow>()
        for (row in rows) {
            val element = element(row, t)
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
                ElsLog.debug("queue", "unreadable row deleted", mapOf("fixTimestamp" to row.fixTs, "attempts" to row.attempts), t.id, level = "warn")
                events.emit(
                    "error",
                    JSONObject().put("code", "UPLOAD_DROPPED").put("message", "Queued location unreadable; location dropped").put("sessionId", t.id)
                )
            }
            syncPendingFix(t)
        }
        if (sent.isEmpty()) {
            continueAfterSend(t)
            return
        }
        val ids = sent.map { it.id }
        val body = buildRequestBody(t.id, elements, t.sync, t.extrasJson)
        val headers = credentials.load(t.id)?.headers ?: emptyMap()
        val sentEpoch = epoch
        inFlight = true
        inFlightRows = ids
        inFlightTarget = t
        val startedAt = nowMs()
        val data = mutableMapOf<String, Any?>(
            "generation" to ids.last(),
            "bytes" to body.length,
            "flush" to (flushCompletion != null),
            "rows" to ids.size,
            "fixTimestamp" to sent.first().fixTs
        )
        if (ownsAttempt(t)) data["attempt"] = attempt
        if (t.ended) {
            data["ended"] = true
            pass?.let { it.requests += 1 }
        }
        ElsLog.debug("upload", "attempt", data, t.id)
        startSendWatchdog(sentEpoch, startedAt)
        val url = t.url
        executor.execute {
            val status = post(url, headers, body, sentEpoch, t.id, "upload")?.status
            main.post { finish(sentEpoch, status, nowMs() - startedAt) }
        }
    }

    private fun element(row: QueuedRow, t: Target): String? = try {
        when (t.sync.mode) {
            QueueMode.LATEST -> decodeFix(row.body)?.let {
                buildElement(FixRecord(row.uuid, t.id, it, battery()), t.sync, t.extrasJson)
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
            ElsLog.info("auth", "refresh watchdog fired", mapOf("durationMs" to durationMs), inFlightTarget?.id ?: config?.id, level = "warn")
            completeRefresh(null, "", durationMs)
        } else {
            ElsLog.info("upload", "upload watchdog fired", mapOf("durationMs" to durationMs), inFlightTarget?.id ?: config?.id, level = "warn")
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
        val t = inFlightTarget
        inFlight = false
        inFlightRows = emptyList()
        inFlightTarget = null
        cancelSendWatchdog()
        if (t == null || (!t.ended && t.id != config?.id)) {
            if (rows.isNotEmpty()) queue.store()?.release(rows)
            notifySettled()
            return
        }
        val at = nowMs()
        val action = syncAction(status, ended = t.ended, refreshConfigured = t.sync.refresh != null, alreadyRefreshed = t.id in refreshed)
        val payload = JSONObject().put("ok", action == SyncAction.DeleteSent).put("at", at).put("sessionId", t.id)
        status?.let { payload.put("status", it) }
        if (t.sync.mode == QueueMode.ALL) payload.put("count", rows.size)
        events.emit("upload", payload)
        val outcome = outcomeName(action)
        val detail = mutableMapOf<String, Any?>(
            "generation" to (rows.lastOrNull() ?: 0L),
            "outcome" to outcome,
            "durationMs" to durationMs,
            "status" to status,
            "rows" to rows.size
        )
        if (action == SyncAction.Retry && ownsAttempt(t)) detail["backoffMs"] = backoffMs(attempt)
        ElsLog.info("upload", "upload status=${status ?: "none"} outcome=$outcome", detail, t.id)
        DebugLogger.onUploadCompleted()

        when (action) {
            SyncAction.DeleteSent -> {
                queue.store()?.delete(rows)
                if (ownsAttempt(t)) attempt = 0
                if (t.ended) {
                    drained += rows.size
                } else {
                    syncPendingFix(t)
                    lastSuccessMs = at
                    store.lastUploadAtMs = at
                }
            }
            SyncAction.DropSent -> {
                queue.store()?.delete(rows)
                syncPendingFix(t)
                if (ownsAttempt(t)) attempt = 0
                val message = if (rows.size == 1) {
                    "Server responded ${status ?: 0}; location dropped"
                } else {
                    "Server responded ${status ?: 0}; ${rows.size} locations dropped"
                }
                events.emit("error", JSONObject().put("code", "UPLOAD_DROPPED").put("message", message).put("sessionId", t.id))
            }
            SyncAction.DropSession -> {
                val deleted = queue.store()?.deleteSession(t.id) ?: 0
                credentials.remove(t.id)
                if (ownsAttempt(t)) attempt = 0
                ElsLog.debug("queue", "session rows deleted", mapOf("rows" to deleted, "status" to status), t.id)
                events.emit(
                    "error",
                    JSONObject()
                        .put("code", "UPLOAD_DROPPED")
                        .put("message", "Server responded ${status ?: 0}; $deleted location${if (deleted == 1) "" else "s"} of the ended session dropped")
                        .put("sessionId", t.id)
                )
            }
            is SyncAction.Stop -> {
                giveBack(rows, t)
                onStop?.invoke(action.reason)
                return
            }
            SyncAction.RefreshAndRetry -> {
                giveBack(rows, t)
                refresh(t)
                return
            }
            SyncAction.Retry -> {
                giveBack(rows, t)
                retryLater(t)
                notifySettled()
                return
            }
        }
        if (!t.ended) resolveDeferredIfBlocked()
        continueAfterSend(t)
        notifySettled()
    }

    private fun ownsAttempt(t: Target): Boolean = !t.ended || config == null

    private fun retryLater(t: Target) {
        if (t.ended) {
            endPass(PassEnd.RETRY)
        } else {
            resolveDeferredIfBlocked()
            scheduleRetry()
        }
    }

    private fun resolveDeferredIfBlocked() {
        val c = config ?: return
        if (deferred.isEmpty() || !hasPending(c)) return
        val waiters = deferred.toList()
        deferred.clear()
        val total = drained
        waiters.forEach { it.done(total - it.base) }
    }

    private fun scheduleRetry() {
        val delay = backoffMs(attempt)
        attempt += 1
        if (flushCompletion != null) scheduleFlushRetry(delay) else startTimer(delay) { pump() }
    }

    private fun refresh(t: Target) {
        refreshed.add(t.id)
        epoch += 1
        inFlightTarget = t
        val r = t.sync.refresh
        if (r == null) {
            completeRefresh(null, "", 0L)
            return
        }
        val headers = credentials.load(t.id)?.refreshHeaders ?: r.headers
        val body = refreshRequestBody(r.bodyJson, t.id)
        val sentEpoch = epoch
        inFlight = true
        refreshing = true
        val startedAt = nowMs()
        ElsLog.debug("auth", "refresh attempt", mapOf("headers" to headers.keys.sorted()), t.id)
        startSendWatchdog(sentEpoch, startedAt)
        executor.execute {
            val response = post(r.url, headers, body, sentEpoch, t.id, "auth")
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
        val t = inFlightTarget
        inFlight = false
        inFlightTarget = null
        refreshing = false
        cancelSendWatchdog()
        if (t == null || (!t.ended && t.id != config?.id)) {
            notifySettled()
            return
        }
        val result = parseRefreshResponse(status, body)
        if (result == null) {
            val rejected = status != null && status !in 300..399
            val message = when {
                status == null -> "Refresh transport failure: no response"
                rejected -> "Refresh rejected: server responded $status without usable headers"
                else -> "Refresh transport failure: redirect $status refused"
            }
            refreshFailed(t, rejected, message, mapOf("status" to status, "durationMs" to durationMs))
            return
        }
        val refreshHeaders = result.refreshHeaders ?: credentials.load(t.id)?.refreshHeaders ?: t.sync.refresh?.headers ?: emptyMap()
        if (!credentials.save(t.id, result.headers, refreshHeaders)) {
            refreshFailed(t, false, "Could not store refreshed credentials", mapOf("status" to status, "durationMs" to durationMs))
            return
        }
        val data = mutableMapOf<String, Any?>("status" to status, "durationMs" to durationMs, "headers" to result.headers.keys.sorted())
        result.refreshHeaders?.let { data["refreshHeaders"] = it.keys.sorted() }
        ElsLog.debug("auth", "refresh succeeded", data, t.id)
        send(t)
        notifySettled()
    }

    private fun refreshFailed(t: Target, rejected: Boolean, message: String, data: Map<String, Any?>) {
        ElsLog.debug("auth", if (rejected) "refresh rejected" else "refresh failed", data, t.id, level = "warn")
        events.emit("error", JSONObject().put("code", "AUTH_REFRESH_FAILED").put("message", message).put("sessionId", t.id))
        if (rejected) {
            val action = syncAction(401, ended = t.ended, refreshConfigured = true, alreadyRefreshed = true)
            if (action is SyncAction.Stop) {
                onStop?.invoke(action.reason)
                return
            }
        } else {
            refreshed.remove(t.id)
        }
        retryLater(t)
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
