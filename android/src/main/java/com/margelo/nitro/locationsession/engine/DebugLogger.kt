package com.margelo.nitro.locationsession.engine

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.locationsession.BuildConfig
import com.margelo.nitro.locationsession.core.DebugOptions
import com.margelo.nitro.locationsession.core.LogLine
import com.margelo.nitro.locationsession.core.encodeLogLine
import com.margelo.nitro.locationsession.core.lineSeq
import com.margelo.nitro.locationsession.core.planBatch
import com.margelo.nitro.locationsession.core.validateDebugOptions
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class DebugBatch(val url: String, val headers: Map<String, String>, val body: ByteArray, val toSeq: Long, val count: Int)

object DebugLogger {
    private const val PREFS = "expo_location_session_debug"
    private const val KEY_OPTIONS = "options"
    private const val KEY_SEQ = "seq"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_FILE_INDEX = "fileIndex"
    private const val KEY_INSTALL_ID = "installId"
    private const val MAX_PENDING_LINES = 1000
    private const val MAX_EARLY_LINES = 200
    private const val CHECKPOINT_EVERY = 256L
    private const val CHUNK_BYTES = 256 * 1024
    private const val LAUNCH_FALLBACK_MS = 2_000L
    internal const val SHIP_TIMEOUT_MS = 20_000
    private const val WINDOW_MARGIN_MS = 500L
    private const val MIN_WINDOW_REQUEST_MS = 1_000L

    private class Checkpoint(val seq: Long, val offset: Long)

    private class FileState {
        var firstSeq: Long? = null
        var lastSeq: Long? = null
        var size = 0L
        val checkpoints = ArrayList<Checkpoint>()
    }

    private class EarlyLine(val level: String, val tag: String, val msg: String, val data: Map<String, Any?>?, val sessionId: String?, val t: Long)

    private class Pending(val seq: Long, val line: String)

    val pid: Int = android.os.Process.myPid()

    internal val queue: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "expo-location-session.debug")
    }

    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val launchLogged = AtomicBoolean(false)
    @Volatile private var providerAtMs: Long? = null

    private var prefs: SharedPreferences? = null
    private var options: DebugOptions? = null
    private var loaded = false
    private var active = false
    private var seq = 0L
    private val files = arrayOf(FileState(), FileState())
    private var current = 0
    private var handle: RandomAccessFile? = null
    private val pending = ArrayDeque<Pending>()
    private var droppedPending = 0
    private val early = ArrayList<EarlyLine>()
    private var droppedEarly = 0
    private var timer: ScheduledFuture<*>? = null
    private var receiver: BroadcastReceiver? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        onQueue { loadIfNeeded() }
    }

    fun processStarted(ctx: Context) {
        providerAtMs = nowMs()
        init(ctx)
        main.postDelayed({ launched("unknown") }, LAUNCH_FALLBACK_MS)
    }

    fun launched(source: String, extra: Map<String, Any?> = emptyMap()) {
        if (!launchLogged.compareAndSet(false, true)) return
        val t = nowMs()
        onQueue {
            loadIfNeeded()
            if (!active && loaded) return@onQueue
            val ctx = app
            val data = mutableMapOf<String, Any?>(
                "pid" to pid,
                "launch" to source,
                "providerInit" to (providerAtMs != null),
                "libVersion" to BuildConfig.LIB_VERSION,
                "os" to osVersion,
                "model" to model,
                "sdk" to Build.VERSION.SDK_INT
            )
            data.putAll(extra)
            providerAtMs?.let { data["sinceProviderMs"] = t - it }
            ctx?.let { lastExit(it) }?.let { data["previousExit"] = it }
            record("info", "lifecycle", "process start", data, null, t)
            ctx?.let { record("info", "permission", "capabilities", Permissions.capabilities(it), null, t) }
            if (canShip) DebugShipper.ship(null)
        }
    }

    fun log(level: String, tag: String, msg: String, data: Map<String, Any?>? = null, sessionId: String? = null) {
        val t = nowMs()
        onQueue { record(level, tag, msg, data, sessionId, t) }
    }

    fun configure(ctx: Context, o: DebugOptions, completion: (Exception?) -> Unit) {
        validateDebugOptions(o)?.let { throw LocationSessionException("INVALID_CONFIG", it) }
        init(ctx)
        onQueue {
            try {
                val p = prefs() ?: throw LocationSessionException("INTERNAL", "Debug storage is not available")
                if (!p.edit().putString(KEY_OPTIONS, encodeOptions(o)).commit()) {
                    throw LocationSessionException("INTERNAL", "Could not persist debug options")
                }
                loaded = true
                apply(o)
                completion(null)
            } catch (e: Exception) {
                completion(e)
            }
        }
    }

    fun read(ctx: Context, limit: Int, completion: (String) -> Unit) {
        init(ctx)
        onQueue {
            val newest = if (active) current else if (prefs()?.getInt(KEY_FILE_INDEX, 0) == 1) 1 else 0
            val lines = ArrayDeque<String>()
            val dir = directory()
            if (dir != null) {
                for (index in intArrayOf(1 - newest, newest)) {
                    forEachLine(File(dir, fileName(index)), 0) { bytes, start, end, _ ->
                        if (lineSeq(bytes, start, end) != null) {
                            lines.addLast(String(bytes, start, end - start, Charsets.UTF_8))
                            if (limit > 0 && lines.size > limit) lines.removeFirst()
                        }
                        true
                    }
                }
            }
            pending.forEach { lines.addLast(it.line) }
            while (limit > 0 && lines.size > limit) lines.removeFirst()
            completion(lines.joinToString(separator = ",", prefix = "[", postfix = "]"))
        }
    }

    fun clear(ctx: Context, completion: () -> Unit) {
        init(ctx)
        onQueue {
            closeHandle()
            directory()?.deleteRecursively()
            files[0] = FileState()
            files[1] = FileState()
            pending.clear()
            droppedPending = 0
            completion()
        }
    }

    fun flush(ctx: Context, completion: (Int) -> Unit) {
        init(ctx)
        onQueue {
            loadIfNeeded()
            if (canShip) DebugShipper.ship(completion) else completion(0)
        }
    }

    fun installId(ctx: Context, completion: (String?) -> Unit) {
        init(ctx)
        onQueue { completion(installId()) }
    }

    fun onUploadCompleted() = trigger()

    fun onStopping() = trigger()

    private fun trigger() {
        onQueue {
            loadIfNeeded()
            if (canShip) DebugShipper.ship(null)
        }
    }

    private fun onQueue(body: () -> Unit) {
        try {
            queue.execute {
                try {
                    body()
                } catch (e: Throwable) {
                    android.util.Log.w("ExpoLocationSession", "debug logger failed: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ExpoLocationSession", "debug logger rejected work: ${e.javaClass.simpleName}")
        }
    }

    private val canShip: Boolean get() = active && options?.url != null

    private fun prefs(): SharedPreferences? {
        prefs?.let { return it }
        val ctx = app ?: return null
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun directory(): File? = try {
        app?.filesDir?.let { File(it, "expo-location-session") }
    } catch (e: Exception) {
        null
    }

    private fun fileName(index: Int) = "debug-$index.jsonl"

    private fun record(level: String, tag: String, msg: String, data: Map<String, Any?>?, sessionId: String?, t: Long) {
        loadIfNeeded()
        if (active) {
            append(level, tag, msg, data, sessionId, t)
        } else if (!loaded) {
            if (early.size < MAX_EARLY_LINES) early.add(EarlyLine(level, tag, msg, data, sessionId, t)) else droppedEarly++
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        val p = prefs() ?: return
        loaded = true
        val stored = p.getString(KEY_OPTIONS, null)?.let(::decodeOptions)
        if (stored == null) {
            dropEarly()
            return
        }
        options = stored
        if (!stored.enabled) {
            dropEarly()
            return
        }
        activate()
        replayEarly()
        restartTimer()
        if (canShip) DebugShipper.ship(null)
    }

    private fun apply(o: DebugOptions) {
        options = o
        if (!o.enabled) {
            dropEarly()
            deactivate()
            return
        }
        activate()
        replayEarly()
        restartTimer()
        val data = mutableMapOf<String, Any?>(
            "flushIntervalMs" to o.flushIntervalMs,
            "maxFileBytes" to o.maxFileBytes,
            "headers" to o.headers.keys.sorted()
        )
        o.url?.let { raw ->
            runCatching { URI(raw) }.getOrNull()?.let { u ->
                data["url"] = "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}${u.path ?: ""}"
            }
        }
        append("info", "debug", "configured", data, null, nowMs())
        if (canShip) DebugShipper.ship(null)
    }

    private fun replayEarly() {
        val lines = early.toList()
        val dropped = droppedEarly
        dropEarly()
        lines.forEach { append(it.level, it.tag, it.msg, it.data, it.sessionId, it.t) }
        if (dropped > 0) {
            append("warn", "debug", "lines dropped before debug options were readable", mapOf("count" to dropped), null, nowMs())
        }
    }

    private fun dropEarly() {
        early.clear()
        droppedEarly = 0
    }

    private fun activate() {
        if (active) return
        val p = prefs() ?: return
        active = true
        current = if (p.getInt(KEY_FILE_INDEX, 0) == 1) 1 else 0
        val dir = directory()
        for (index in 0..1) {
            files[index] = dir?.let { scan(File(it, fileName(index))) } ?: FileState()
        }
        val onDisk = files.mapNotNull { it.lastSeq }.maxOrNull() ?: 0L
        seq = maxOf(p.getLong(KEY_SEQ, 0L), onDisk)
        observe()
    }

    private fun deactivate() {
        active = false
        timer?.cancel(false)
        timer = null
        closeHandle()
        unobserve()
    }

    private fun restartTimer() {
        timer?.cancel(false)
        timer = null
        val o = options ?: return
        if (!canShip) return
        timer = queue.scheduleWithFixedDelay({
            try {
                if (canShip) DebugShipper.ship(null)
            } catch (e: Throwable) {
                android.util.Log.w("ExpoLocationSession", "debug flush timer failed: ${e.javaClass.simpleName}")
            }
        }, o.flushIntervalMs, o.flushIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun observe() {
        if (receiver != null) return
        val ctx = app ?: return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val power = context.getSystemService(PowerManager::class.java)
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED ->
                        log("info", "permission", "power save mode changed", mapOf("enabled" to power?.isPowerSaveMode))
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED ->
                        log("info", "lifecycle", "device idle mode changed", mapOf("idle" to power?.isDeviceIdleMode))
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (e: Exception) {
            android.util.Log.w("ExpoLocationSession", "debug receiver not registered: ${e.javaClass.simpleName}")
        }
    }

    private fun unobserve() {
        val r = receiver ?: return
        receiver = null
        runCatching { app?.unregisterReceiver(r) }
    }

    private fun append(level: String, tag: String, msg: String, data: Map<String, Any?>?, sessionId: String?, t: Long) {
        seq += 1
        prefs()?.edit()?.putLong(KEY_SEQ, seq)?.commit()
        val line = encodeLogLine(LogLine(seq, t, pid, level, tag, msg, sessionId, data))
        pending.addLast(Pending(seq, line))
        drainPending()
        val events = SessionEngine.events
        if (events.hasListeners("log")) events.emitJson("log", line)
    }

    private fun drainPending() {
        while (pending.isNotEmpty()) {
            val h = openHandle() ?: break
            val entry = pending.first()
            val bytes = (entry.line + "\n").toByteArray(Charsets.UTF_8)
            val state = files[current]
            val offset = state.size
            try {
                h.seek(offset)
                h.write(bytes)
            } catch (e: Exception) {
                runCatching { h.setLength(offset) }
                closeHandle()
                break
            }
            pending.removeFirst()
            state.size += bytes.size
            if (state.firstSeq == null) state.firstSeq = entry.seq
            state.lastSeq = entry.seq
            if (entry.seq - (state.checkpoints.lastOrNull()?.seq ?: (Long.MIN_VALUE / 2)) >= CHECKPOINT_EVERY) {
                state.checkpoints.add(Checkpoint(entry.seq, offset))
            }
            if (state.size >= (options?.maxFileBytes ?: 1_000_000L)) rotate()
        }
        if (pending.isEmpty() && droppedPending > 0) {
            val count = droppedPending
            droppedPending = 0
            append("warn", "debug", "lines dropped while the log file was not writable", mapOf("count" to count), null, nowMs())
        } else if (pending.size > MAX_PENDING_LINES) {
            val excess = pending.size - MAX_PENDING_LINES
            repeat(excess) { pending.removeFirst() }
            droppedPending += excess
        }
    }

    private fun rotate() {
        closeHandle()
        current = 1 - current
        directory()?.let { File(it, fileName(current)).delete() }
        files[current] = FileState()
        prefs()?.edit()?.putInt(KEY_FILE_INDEX, current)?.commit()
    }

    private fun closeHandle() {
        runCatching { handle?.close() }
        handle = null
    }

    private fun openHandle(): RandomAccessFile? {
        handle?.let { return it }
        val dir = directory() ?: return null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val raf = RandomAccessFile(File(dir, fileName(current)), "rw")
            files[current].size = raf.length()
            handle = raf
            raf
        } catch (e: Exception) {
            null
        }
    }

    private fun scan(file: File): FileState {
        val state = FileState()
        if (!file.exists()) return state
        var end = 0L
        forEachLine(file, 0) { bytes, start, stop, offset ->
            end = offset + (stop - start) + 1
            val s = lineSeq(bytes, start, stop) ?: return@forEachLine true
            if (state.firstSeq == null) state.firstSeq = s
            state.lastSeq = s
            if (s - (state.checkpoints.lastOrNull()?.seq ?: (Long.MIN_VALUE / 2)) >= CHECKPOINT_EVERY) {
                state.checkpoints.add(Checkpoint(s, offset))
            }
            true
        }
        try {
            RandomAccessFile(file, "rw").use { raf -> if (raf.length() > end) raf.setLength(end) }
        } catch (e: Exception) {
            return state.also { it.size = end }
        }
        state.size = end
        return state
    }

    private fun forEachLine(file: File, from: Long, body: (ByteArray, Int, Int, Long) -> Boolean) {
        val raf = try {
            RandomAccessFile(file, "r")
        } catch (e: Exception) {
            return
        }
        raf.use {
            try {
                it.seek(from)
            } catch (e: Exception) {
                return
            }
            var buffer = ByteArray(CHUNK_BYTES)
            var length = 0
            var bufferOffset = from
            while (true) {
                if (length == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                val read = try {
                    it.read(buffer, length, buffer.size - length)
                } catch (e: Exception) {
                    -1
                }
                if (read <= 0) return
                val scanFrom = length
                length += read
                var start = 0
                for (i in scanFrom until length) {
                    if (buffer[i] != '\n'.code.toByte()) continue
                    if (i > start && !body(buffer, start, i, bufferOffset + start)) return
                    start = i + 1
                }
                if (start > 0) {
                    System.arraycopy(buffer, start, buffer, 0, length - start)
                    bufferOffset += start
                    length -= start
                }
            }
        }
    }

    internal fun requestTimeoutMs(): Int? {
        if (LocationTrackingService.isForeground) return SHIP_TIMEOUT_MS
        val deadline = asyncDeadlineMs
        if (deadline == 0L) return SHIP_TIMEOUT_MS
        val remaining = deadline - nowMs() - WINDOW_MARGIN_MS
        if (remaining < MIN_WINDOW_REQUEST_MS) return null
        return minOf(SHIP_TIMEOUT_MS.toLong(), remaining).toInt()
    }

    internal fun makeBatch(): DebugBatch? {
        if (!canShip) return null
        val o = options ?: return null
        val url = o.url ?: return null
        val p = prefs() ?: return null
        val low = files.mapNotNull { it.firstSeq }.minOrNull() ?: return null
        val high = files.mapNotNull { it.lastSeq }.maxOrNull() ?: return null
        if (low > high) return null
        var cursor = p.getLong(KEY_CURSOR, 0L)
        var lines: MutableList<String> = mutableListOf()
        var toSeq = 0L
        while (lines.isEmpty()) {
            var plan = planBatch(low..high, cursor) ?: return null
            if (plan.lostFrom != null && plan.toSeq - plan.fromSeq + 1 >= 500) {
                planBatch(low..high, cursor, max = 499)?.let { plan = it }
            }
            lines = readLines(plan.fromSeq, plan.toSeq)
            val lostFrom = plan.lostFrom
            val lostTo = plan.lostTo
            if (lostFrom != null && lostTo != null) {
                val lost = LogLine(lostTo, nowMs(), pid, "warn", "debug", "lines lost", data = mapOf("from" to lostFrom, "to" to lostTo))
                lines.add(0, encodeLogLine(lost))
            }
            toSeq = plan.toSeq
            if (lines.isEmpty()) {
                setCursor(toSeq)
                cursor = toSeq
            }
        }
        val install = installInfo() ?: return null
        val body = "{\"install\":$install,\"lines\":[${lines.joinToString(",")}]}".toByteArray(Charsets.UTF_8)
        return DebugBatch(url, o.headers, body, toSeq, lines.size)
    }

    internal fun delivered(upTo: Long) = setCursor(upTo)

    internal fun shipFailed(status: Int?, error: String?) {
        if (!active) return
        val data = mutableMapOf<String, Any?>()
        status?.let { data["status"] = it }
        error?.let { data["error"] = it }
        append("warn", "debug", "log batch not delivered", data, null, nowMs())
    }

    private fun setCursor(value: Long) {
        prefs()?.edit()?.putLong(KEY_CURSOR, value)?.commit()
    }

    private fun readLines(from: Long, to: Long): MutableList<String> {
        val out = mutableListOf<String>()
        val dir = directory() ?: return out
        for (index in intArrayOf(1 - current, current)) {
            val state = files[index]
            val first = state.firstSeq ?: continue
            val last = state.lastSeq ?: continue
            if (last < from || first > to) continue
            val start = state.checkpoints.lastOrNull { it.seq <= from }?.offset ?: 0L
            var done = false
            forEachLine(File(dir, fileName(index)), start) { bytes, s, e, _ ->
                val value = lineSeq(bytes, s, e) ?: return@forEachLine true
                if (value > to) {
                    done = true
                    return@forEachLine false
                }
                if (value >= from) out.add(String(bytes, s, e - s, Charsets.UTF_8))
                true
            }
            if (done) break
        }
        return out
    }

    private fun installId(): String? {
        val p = prefs() ?: return null
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().lowercase()
        return if (p.edit().putString(KEY_INSTALL_ID, id).commit()) id else null
    }

    private fun installInfo(): String? {
        val id = installId() ?: return null
        return JSONObject()
            .put("id", id)
            .put("platform", "android")
            .put("model", model)
            .put("os", osVersion)
            .put("appVersion", appVersion())
            .put("libVersion", BuildConfig.LIB_VERSION)
            .toString()
    }

    private fun appVersion(): String {
        val ctx = app ?: return "unknown"
        return try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName ?: "0"} (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun lastExit(ctx: Context): Map<String, Any?>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val info = ctx.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(ctx.packageName, 0, 1)
                ?.firstOrNull() ?: return null
            mapOf(
                "pid" to info.pid,
                "reason" to info.reason,
                "status" to info.status,
                "importance" to info.importance,
                "description" to info.description,
                "at" to info.timestamp
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeOptions(o: DebugOptions): String {
        val headers = JSONObject()
        o.headers.forEach { (k, v) -> headers.put(k, v) }
        return JSONObject()
            .put("enabled", o.enabled)
            .put("url", o.url ?: JSONObject.NULL)
            .put("headers", headers)
            .put("flushIntervalMs", o.flushIntervalMs)
            .put("maxFileBytes", o.maxFileBytes)
            .toString()
    }

    private fun decodeOptions(json: String): DebugOptions? = try {
        val obj = JSONObject(json)
        val h = obj.optJSONObject("headers")
        val headers = mutableMapOf<String, String>()
        h?.keys()?.forEach { k -> headers[k] = h.getString(k) }
        DebugOptions(
            enabled = obj.getBoolean("enabled"),
            url = if (obj.isNull("url")) null else obj.getString("url"),
            headers = headers,
            flushIntervalMs = obj.getLong("flushIntervalMs"),
            maxFileBytes = obj.getLong("maxFileBytes")
        )
    } catch (e: Exception) {
        null
    }

    val osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    val model: String = "${Build.MANUFACTURER} ${Build.MODEL}"
}
