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
import com.margelo.nitro.locationsession.core.shouldUploadNow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class Uploader(context: Context, private val store: SessionStore, private val events: EventBus) {
    var onStop: ((StopReason) -> Unit)? = null

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "expo-location-session.upload") }

    private var config: SessionConfig? = null
    private var slot = PendingSlot()
    private var generation = 0
    var lastSuccessMs: Long? = null
        private set
    private var attempt = 0
    private var inFlight = false
    @Volatile private var connection: HttpURLConnection? = null
    private var timer: Runnable? = null
    private var epoch = 0
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
        connection?.let { conn -> Thread { runCatching { conn.disconnect() } }.start() }
        connection = null
        cancelTimer()
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
            startTimer(last + c.uploadMinIntervalMs - now) { pump() }
        }
    }

    fun flush(completion: () -> Unit) {
        assertMain()
        if (config == null || (!inFlight && slot.fix == null)) {
            completion()
            return
        }
        flushCompletion = completion
        flushTarget = generation
        cancelTimer()
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

    private fun completeFlush() {
        val done = flushCompletion
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
            ElsLog.info("could not encode location; dropping it")
            slot.markSent(sentGeneration)
            store.pendingFix = slot.fix
            events.emit("error", JSONObject().put("code", "UPLOAD_DROPPED").put("message", "Location could not be encoded; location dropped"))
            if (flushCompletion != null) completeFlush() else pump()
            return
        }
        val sentEpoch = epoch
        inFlight = true
        executor.execute {
            val status = post(c, body)
            main.post { finish(sentGeneration, status, sentEpoch) }
        }
    }

    private fun post(c: SessionConfig, body: String): Int? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(c.uploadUrl).openConnection() as HttpURLConnection
            connection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            c.uploadHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            runCatching { (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            status
        } catch (e: Exception) {
            ElsLog.info("upload failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
            if (connection === conn) connection = null
        }
    }

    private fun finish(sent: Int, status: Int?, sentEpoch: Int) {
        if (sentEpoch != epoch) return
        inFlight = false
        val at = nowMs()
        val outcome = classify(status)
        val payload = JSONObject().put("ok", outcome == UploadOutcome.Success).put("at", at)
        status?.let { payload.put("status", it) }
        events.emit("upload", payload)
        ElsLog.info("upload status=${status ?: "none"} outcome=${outcome.javaClass.simpleName}")

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
