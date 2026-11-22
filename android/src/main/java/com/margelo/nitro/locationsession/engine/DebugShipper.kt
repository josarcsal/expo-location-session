package com.margelo.nitro.locationsession.engine

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object DebugShipper {
    private class Result(val status: Int?, val error: String?)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "expo-location-session.debug-ship") }

    private var inFlight = false
    private var sent = 0
    private val waiters = mutableListOf<(Int) -> Unit>()

    fun ship(completion: ((Int) -> Unit)?) {
        completion?.let(waiters::add)
        if (inFlight) return
        sent = 0
        sendNext()
    }

    private fun sendNext() {
        val timeout = DebugLogger.requestTimeoutMs()
        val batch = if (timeout == null) null else DebugLogger.makeBatch()
        if (timeout == null || batch == null) {
            finish()
            return
        }
        inFlight = true
        executor.execute {
            val result = post(batch, timeout)
            DebugLogger.queue.execute {
                val status = result.status
                if (status != null && status in 200..299) {
                    DebugLogger.delivered(batch.toSeq)
                    sent += batch.count
                    sendNext()
                } else {
                    finish()
                    DebugLogger.shipFailed(status, result.error)
                }
            }
        }
    }

    private fun finish() {
        inFlight = false
        val done = waiters.toList()
        waiters.clear()
        val count = sent
        done.forEach { it(count) }
    }

    private fun post(batch: DebugBatch, timeoutMs: Int): Result {
        var conn: HttpURLConnection? = null
        var deadline: ScheduledFuture<*>? = null
        return try {
            conn = URL(batch.url).openConnection() as HttpURLConnection
            val watched = conn
            deadline = DebugLogger.queue.schedule({ runCatching { watched.disconnect() } }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            batch.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.setFixedLengthStreamingMode(batch.body.size)
            conn.outputStream.use { it.write(batch.body) }
            val status = conn.responseCode
            runCatching { (if (status >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            Result(status, null)
        } catch (e: Exception) {
            Result(null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        } finally {
            deadline?.cancel(false)
            conn?.disconnect()
        }
    }
}
