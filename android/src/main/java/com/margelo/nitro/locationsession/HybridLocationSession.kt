package com.margelo.nitro.locationsession

import com.margelo.nitro.core.Promise
import java.util.concurrent.atomic.AtomicInteger

class HybridLocationSession : HybridLocationSessionSpec() {
    private val nextListenerId = AtomicInteger(0)

    private fun <T> notImplemented(): Promise<T> =
        Promise.rejected(RuntimeException("NOT_IMPLEMENTED: not implemented"))

    override fun getPermissionStatus(): Promise<NativePermissionStatus> = notImplemented()

    override fun requestPermission(level: PermissionLevel): Promise<NativePermissionStatus> = notImplemented()

    override fun getCurrentPosition(accuracy: Accuracy, timeoutMs: Double, maxAgeMs: Double): Promise<NativeLocation> = notImplemented()

    override fun start(config: NativeSessionConfig): Promise<Unit> = notImplemented()

    override fun stop(): Promise<Unit> = notImplemented()

    override fun getState(): Promise<NativeSessionState> = notImplemented()

    override fun addListener(event: SessionEvent, callback: (payloadJson: String) -> Unit): Double =
        nextListenerId.incrementAndGet().toDouble()

    override fun removeListener(id: Double) {}
}
