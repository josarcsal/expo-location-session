package com.margelo.nitro.locationsession

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.locationsession.core.BatchConfig
import com.margelo.nitro.locationsession.core.DebugOptions
import com.margelo.nitro.locationsession.core.Destination
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.RefreshConfig
import com.margelo.nitro.locationsession.core.SessionConfig
import com.margelo.nitro.locationsession.core.StopReason
import com.margelo.nitro.locationsession.core.SyncConfig
import com.margelo.nitro.locationsession.engine.CurrentPosition
import com.margelo.nitro.locationsession.engine.DebugLogger
import com.margelo.nitro.locationsession.engine.EngineState
import com.margelo.nitro.locationsession.engine.Grant
import com.margelo.nitro.locationsession.engine.LocationSessionException
import com.margelo.nitro.locationsession.engine.PermissionSnapshot
import com.margelo.nitro.locationsession.engine.Permissions
import com.margelo.nitro.locationsession.engine.SessionEngine
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToLong

@DoNotStrip
@Keep
class HybridLocationSession : HybridLocationSessionSpec() {
    private val main = Handler(Looper.getMainLooper())
    private val listenerIds: MutableSet<Double> = Collections.synchronizedSet(HashSet())

    init {
        main.post { context()?.let { SessionEngine.resumeIfNeeded(it) } }
    }

    override fun dispose() {
        val ids = synchronized(listenerIds) { listenerIds.toList() }
        ids.forEach { SessionEngine.events.remove(it) }
        listenerIds.clear()
        super.dispose()
    }

    private fun context(): Context? = NitroModules.applicationContext?.applicationContext

    private fun <T> onMain(body: (Context, Promise<T>) -> Unit): Promise<T> {
        val promise = Promise<T>()
        main.post {
            val ctx = context()
            if (ctx == null) {
                promise.reject(RuntimeException("INTERNAL: React context is not available"))
                return@post
            }
            try {
                body(ctx, promise)
            } catch (e: LocationSessionException) {
                promise.reject(RuntimeException(e.message))
            } catch (e: Exception) {
                promise.reject(RuntimeException("INTERNAL: $e"))
            }
        }
        return promise
    }

    override fun getPermissionStatus(): Promise<NativePermissionStatus> = onMain { ctx, promise ->
        promise.resolve(nativePermission(Permissions.status(ctx, NitroModules.applicationContext?.currentActivity)))
    }

    override fun requestPermission(level: PermissionLevel): Promise<NativePermissionStatus> = onMain { ctx, promise ->
        Permissions.request(ctx, { NitroModules.applicationContext?.currentActivity }, level == PermissionLevel.ALWAYS) {
            promise.resolve(nativePermission(it))
        }
    }

    override fun getCurrentPosition(accuracy: Accuracy, timeoutMs: Double, maxAgeMs: Double): Promise<NativeLocation> =
        onMain { ctx, promise ->
            CurrentPosition.locate(ctx, accuracy.wire(), timeoutMs, maxAgeMs) { result ->
                result.fold(
                    onSuccess = { promise.resolve(nativeLocation(it)) },
                    onFailure = { promise.reject(RuntimeException(it.message)) }
                )
            }
        }

    override fun start(config: NativeSessionConfig): Promise<Unit> = onMain { ctx, promise ->
        SessionEngine.start(ctx, sessionConfig(config))
        promise.resolve(Unit)
    }

    override fun stop(): Promise<Unit> = onMain { ctx, promise ->
        SessionEngine.stop(ctx, StopReason.STOPPED)
        promise.resolve(Unit)
    }

    override fun getState(): Promise<NativeSessionState> = onMain { ctx, promise ->
        promise.resolve(nativeState(SessionEngine.state(ctx)))
    }

    override fun configureDebug(options: NativeDebugOptions): Promise<Unit> = onMain { ctx, promise ->
        DebugLogger.configure(ctx, debugOptions(options)) { error ->
            when (error) {
                null -> promise.resolve(Unit)
                is LocationSessionException -> promise.reject(RuntimeException(error.message))
                else -> promise.reject(RuntimeException("INTERNAL: $error"))
            }
        }
    }

    override fun getDebugLog(limit: Double): Promise<String> = onMain { ctx, promise ->
        if (!limit.isFinite() || limit < 0 || limit > Int.MAX_VALUE) {
            throw LocationSessionException("INVALID_CONFIG", "limit must be a non-negative number")
        }
        DebugLogger.read(ctx, limit.toInt()) { promise.resolve(it) }
    }

    override fun clearDebugLog(): Promise<Unit> = onMain { ctx, promise ->
        DebugLogger.clear(ctx) { promise.resolve(Unit) }
    }

    override fun flushDebugLog(): Promise<Double> = onMain { ctx, promise ->
        DebugLogger.flush(ctx) { promise.resolve(it.toDouble()) }
    }

    override fun getDebugInstallId(): Promise<String> = onMain { ctx, promise ->
        DebugLogger.installId(ctx) { id ->
            if (id != null) promise.resolve(id) else promise.reject(RuntimeException("INTERNAL: Debug storage is not available"))
        }
    }

    override fun getQueueStatus(): Promise<NativeQueueStatus> = onMain { ctx, promise ->
        val status = SessionEngine.queueStatus(ctx)
        promise.resolve(NativeQueueStatus(status.pending.toDouble(), status.oldestAt?.toDouble(), status.sessions.toDouble()))
    }

    override fun syncQueue(): Promise<Double> = onMain { ctx, promise ->
        SessionEngine.syncQueue(ctx) { promise.resolve(it.toDouble()) }
    }

    override fun clearQueue(): Promise<Unit> = onMain { ctx, promise ->
        SessionEngine.clearQueue(ctx)
        promise.resolve(Unit)
    }

    override fun addListener(event: SessionEvent, callback: (payloadJson: String) -> Unit): Double {
        val name = when (event) {
            SessionEvent.LOCATION -> "location"
            SessionEvent.UPLOAD -> "upload"
            SessionEvent.STOP -> "stop"
            SessionEvent.ERROR -> "error"
            SessionEvent.LOG -> "log"
        }
        val id = SessionEngine.events.add(name, callback)
        listenerIds.add(id)
        return id
    }

    override fun removeListener(id: Double) {
        SessionEngine.events.remove(id)
        listenerIds.remove(id)
    }
}

private fun Accuracy.wire(): String = when (this) {
    Accuracy.HIGH -> "high"
    Accuracy.BALANCED -> "balanced"
    Accuracy.LOW -> "low"
}

private fun milliseconds(value: Double): Long? =
    if (value.isFinite() && abs(value) < 9.0e15) value.roundToLong() else null

private fun integer(value: Double, field: String): Long {
    if (!value.isFinite() || value != Math.rint(value) || abs(value) >= 9.0e15) {
        throw LocationSessionException("INVALID_CONFIG", "$field must be an integer")
    }
    return value.toLong()
}

private fun syncConfig(c: NativeSessionConfig): SyncConfig {
    val maxRecords = integer(c.uploadQueueMaxRecords, "queue.maxRecords")
    val maxAgeMs = integer(c.uploadQueueMaxAgeMs, "queue.maxAgeMs")
    val batch = c.uploadBatchMaxSize?.let {
        BatchConfig(integer(it, "batch.maxSize").coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(), c.uploadBatchRootProperty ?: "locations")
    }
    val refresh = c.uploadRefreshUrl?.let { RefreshConfig(it, c.uploadRefreshHeaders ?: emptyMap(), c.uploadRefreshBodyJson) }
    return SyncConfig(
        mode = when (c.uploadQueueMode) {
            NativeQueueMode.LATEST -> QueueMode.LATEST
            NativeQueueMode.ALL -> QueueMode.ALL
        },
        maxRecords = maxRecords.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        maxAgeMs = maxAgeMs,
        batch = batch,
        templateJson = c.uploadBodyTemplateJson,
        refresh = refresh
    )
}

private fun sessionConfig(c: NativeSessionConfig): SessionConfig {
    val expiresAt = milliseconds(c.expiresAt)
    val minInterval = milliseconds(c.uploadMinIntervalMs)
    if (expiresAt == null || minInterval == null || !c.distanceFilterMeters.isFinite()) {
        throw LocationSessionException("INVALID_CONFIG", "expiresAt, distanceFilterMeters and upload.minIntervalMs must be finite numbers")
    }
    val destination = c.destination?.let { Destination(it.latitude, it.longitude, it.radiusMeters) }
    if (destination != null && !(
            destination.latitude.isFinite() && destination.longitude.isFinite() && destination.radiusMeters.isFinite() &&
                abs(destination.latitude) <= 90 && abs(destination.longitude) <= 180
            )
    ) {
        throw LocationSessionException("INVALID_CONFIG", "destination coordinates are invalid")
    }
    return SessionConfig(
        id = c.id,
        expiresAtMs = expiresAt,
        destination = destination,
        accuracy = c.accuracy.wire(),
        distanceFilterMeters = c.distanceFilterMeters,
        uploadUrl = c.uploadUrl,
        uploadHeaders = c.uploadHeaders,
        uploadExtrasJson = c.uploadExtrasJson,
        uploadMinIntervalMs = minInterval,
        notificationTitle = c.androidNotificationTitle,
        notificationText = c.androidNotificationText,
        notificationChannelName = c.androidNotificationChannelName,
        sync = syncConfig(c)
    )
}

private fun debugOptions(o: NativeDebugOptions): DebugOptions {
    val flush = milliseconds(o.flushIntervalMs)
    val maxBytes = milliseconds(o.maxFileBytes)
    if (flush == null || maxBytes == null) {
        throw LocationSessionException("INVALID_CONFIG", "flushIntervalMs and maxFileBytes must be finite numbers")
    }
    return DebugOptions(o.enabled, o.url, o.headers, flush, maxBytes)
}

private fun Grant.native(): PermissionValue = when (this) {
    Grant.GRANTED -> PermissionValue.GRANTED
    Grant.DENIED -> PermissionValue.DENIED
    Grant.UNDETERMINED -> PermissionValue.UNDETERMINED
}

private fun nativePermission(s: PermissionSnapshot): NativePermissionStatus =
    NativePermissionStatus(s.foreground.native(), s.background.native(), s.precise, s.notifications.native())

private fun nativeLocation(f: Fix): NativeLocation =
    NativeLocation(f.latitude, f.longitude, f.accuracy, f.altitude, f.speed, f.heading, f.timestampMs.toDouble())

private fun nativeState(s: EngineState): NativeSessionState {
    if (s.running) {
        return NativeSessionState(
            status = SessionStatus.RUNNING,
            sessionId = s.sessionId,
            startedAt = s.startedAtMs?.toDouble(),
            expiresAt = s.expiresAtMs?.toDouble(),
            lastLocation = s.lastFix?.let(::nativeLocation),
            lastUploadAt = s.lastUploadAtMs?.toDouble(),
            survivesProcessDeath = s.survivesProcessDeath,
            lastStopReason = null,
            lastStopAt = null,
            lastStopSessionId = null
        )
    }
    return NativeSessionState(
        status = SessionStatus.IDLE,
        sessionId = null,
        startedAt = null,
        expiresAt = null,
        lastLocation = null,
        lastUploadAt = null,
        survivesProcessDeath = null,
        lastStopReason = s.lastStop?.reason?.wire,
        lastStopAt = s.lastStop?.atMs?.toDouble(),
        lastStopSessionId = s.lastStop?.sessionId
    )
}
