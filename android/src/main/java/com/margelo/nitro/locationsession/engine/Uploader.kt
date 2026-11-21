package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.locationsession.core.Battery
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.PendingSlot
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import com.margelo.nitro.locationsession.core.UploadOutcome
import com.margelo.nitro.locationsession.core.backoffMs
import com.margelo.nitro.locationsession.core.buildBody
import com.margelo.nitro.locationsession.core.classify
import com.margelo.nitro.locationsession.core.FINAL_UPLOAD_BUDGET_MS
import com.margelo.nitro.locationsession.core.shouldUploadNow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class Uploader(context: Context, private val store: SessionStore, private val events: EventBus) {
    var onStop: ((StopReason) -> Unit)? = null

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "expo-location-session.upload") }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "expo-location-session.upload-watchdog") }

    private var config: SessionConfig? = null
    private var slot = PendingSlot()
    private var generation = 0
    var lastSuccessMs: Long? = null
        private set
    private var attempt = 0
    private var inFlight = false
    @Volatile private var connection: HttpURLConnection? = null
    private var timer: Runnable? = null
    private var flushDeadline: Runnable? = null
    @Volatile private var epoch = 0
    private var flushCompletion: (() -> Unit)? = null
    private var flushTarget = 0
    private val settledWaiters = mutableListOf<() -> Unit>()

    val isBusy: Boolean get() = inFlight || flushCompletion != null

    fun begin(c: SessionConfig, pending: Fix?, lastSuccessMs: Long?) {
        assertMain()
        reset()
        config = c
        this.lastSuccessMs = lastSuccessMs
        pending?.let(::sanitized)?.let { generation = slot.put(it) }
    }

    fun reset() {
        assertMain()
        epoch += 1
        inFlight = false
        connection?.let { conn -> watchdog.execute { runCatching { conn.disconnect() } } }
        connection = null
        cancelTimer()
        cancelFlushDeadline()
        config = null
        slot = PendingSlot()
        generation = 0
        lastSuccessMs = null
        attempt = 0
        flushCompletion = null
        flushTarget = 0
        notifySettled()
    }

    fun put(fix: Fix) {
        assertMain()
        if (config == null) return
        generation = slot.put(fix)
        store.pendingFix = fix
    }

    fun pump() {
        assertMain()
        val c = config ?: return
        if (inFlight || flushCompletion != null || slot.fix == null) return
        val now = nowMs()
        if (shouldUploadNow(lastSuccessMs, now, c.uploadMinIntervalMs, false)) {
            send()
        } else {
            val last = lastSuccessMs ?: return
            val wait = last + c.uploadMinIntervalMs - now
            ElsLog.debug("upload", "throttled", mapOf("waitMs" to wait, "generation" to generation, "minIntervalMs" to c.uploadMinIntervalMs), c.id)
            startTimer(wait) { pump() }
        }
    }

    fun flush(completion: () -> Unit) {
        assertMain()
        if (config == null || (!inFlight && slot.fix == null)) {
            ElsLog.debug("upload", "flush skipped, nothing pending", sessionId = config?.id)
            completion()
            return
        }
        ElsLog.debug("upload", "flush start", mapOf("generation" to generation, "inFlight" to inFlight), config?.id)
        flushCompletion = completion
        flushTarget = generation
        cancelTimer()
        val r = Runnable {
            flushDeadline = null
            flushDeadlineReached()
        }
        flushDeadline = r
        main.postDelayed(r, FINAL_UPLOAD_BUDGET_MS)
        if (!inFlight) send()
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

    private fun flushDeadlineReached() {
        if (flushCompletion == null) return
        ElsLog.info(
            "upload",
            "final upload deadline reached",
            mapOf("pending" to (slot.fix != null), "inFlight" to inFlight),
            config?.id,
            level = "warn"
        )
        cancelTimer()
        completeFlush()
    }

    private fun completeFlush() {
        cancelFlushDeadline()
        val done = flushCompletion
        if (done != null) ElsLog.debug("upload", "flush end", mapOf("pending" to (slot.fix != null)), config?.id)
        flushCompletion = null
        done?.invoke()
        notifySettled()
    }

    private fun send() {
        val c = config
        val fix = slot.fix
        if (c == null || fix == null) {
            completeFlush()
            return
        }
        cancelTimer()
        val sentGeneration = generation
        val body = try {
            buildBody(c.id, fix, battery(), c.uploadExtrasJson)
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            ElsLog.info("upload", "could not encode location; dropping it", sessionId = c.id, level = "warn")
            slot.markSent(sentGeneration)
            store.pendingFix = slot.fix
            events.emit("error", JSONObject().put("code", "UPLOAD_DROPPED").put("message", "Location could not be encoded; location dropped"))
            if (flushCompletion != null) completeFlush() else pump()
            return
        }
        val sentEpoch = epoch
        inFlight = true
        val startedAt = nowMs()
        ElsLog.debug(
            "upload",
            "attempt",
            mapOf("generation" to sentGeneration, "attempt" to attempt, "bytes" to body.length, "flush" to (flushCompletion != null), "fixTimestamp" to fix.timestampMs),
            c.id
        )
        executor.execute {
            val status = post(c, body, sentEpoch)
            main.post { finish(sentGeneration, status, sentEpoch, nowMs() - startedAt) }
        }
    }

    private fun post(c: SessionConfig, body: String, sentEpoch: Int): Int? {
        if (sentEpoch != epoch) return null
        var conn: HttpURLConnection? = null
        var deadline: ScheduledFuture<*>? = null
        return try {
            conn = URL(c.uploadUrl).openConnection() as HttpURLConnection
            connection = conn
            val watched = conn
            deadline = watchdog.schedule({ runCatching { watched.disconnect() } }, TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            c.uploadHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            if (sentEpoch != epoch) return null
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            runCatching { (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            status
        } catch (e: Exception) {
            ElsLog.info("upload", "upload failed: ${e.javaClass.simpleName}", sessionId = c.id, level = "warn")
            null
        } finally {
            deadline?.cancel(false)
            conn?.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun finish(sent: Int, status: Int?, sentEpoch: Int, durationMs: Long) {
        if (sentEpoch != epoch) {
            ElsLog.debug("upload", "stale response ignored", mapOf("status" to status))
            return
        }
        inFlight = false
        val at = nowMs()
        val outcome = classify(status)
        val payload = JSONObject().put("ok", outcome == UploadOutcome.Success).put("at", at)
        status?.let { payload.put("status", it) }
        events.emit("upload", payload)
        val detail = mutableMapOf<String, Any?>(
            "generation" to sent,
            "outcome" to outcome.javaClass.simpleName,
            "durationMs" to durationMs,
            "status" to status
        )
        if (outcome == UploadOutcome.Retry) detail["backoffMs"] = backoffMs(attempt)
        ElsLog.info("upload", "upload status=${status ?: "none"} outcome=${outcome.javaClass.simpleName}", detail, config?.id)
        DebugLogger.onUploadCompleted()

        when (outcome) {
            UploadOutcome.Success -> {
                slot.markSent(sent)
                store.pendingFix = slot.fix
                lastSuccessMs = at
                store.lastUploadAtMs = at
                attempt = 0
            }
            is UploadOutcome.Stop -> {
                onStop?.invoke(outcome.reason)
                return
            }
            UploadOutcome.Drop -> {
                slot.markSent(sent)
                store.pendingFix = slot.fix
                attempt = 0
                events.emit(
                    "error",
                    JSONObject().put("code", "UPLOAD_DROPPED").put("message", "Server responded ${status ?: 0}; location dropped")
                )
            }
            UploadOutcome.Retry -> {
                val delay = backoffMs(attempt)
                attempt += 1
                if (flushCompletion != null) scheduleFlushRetry(delay) else startTimer(delay) { pump() }
                notifySettled()
                return
            }
        }

        if (flushCompletion != null) {
            if (sent >= flushTarget || slot.fix == null) completeFlush() else send()
        } else {
            pump()
        }
        notifySettled()
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
