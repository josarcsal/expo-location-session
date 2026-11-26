package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.margelo.nitro.locationsession.core.shouldScheduleSync
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueueSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val done = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var remaining = true

    override fun doWork(): Result {
        val ctx = applicationContext
        main.post {
            EngineThread.post {
                ElsLog.debug("sync", "background sync started", mapOf("attempt" to runAttemptCount))
                SessionEngine.syncQueue(ctx, DRAIN_BUDGET_MS) {
                    val status = runCatching { SessionEngine.queueStatus(ctx) }.getOrNull()
                    remaining = status == null || shouldScheduleSync(true, status.pending, SessionEngine.isActive)
                    ElsLog.debug("sync", "background sync finished", mapOf("pending" to status?.pending, "sessionRunning" to SessionEngine.isActive))
                    done.countDown()
                }
            }
        }
        if (!done.await(AWAIT_MS, TimeUnit.MILLISECONDS)) {
            ElsLog.debug("sync", "background sync timed out", level = "warn")
            main.post { EngineThread.post { SessionEngine.cancelSync(ctx) } }
            return Result.retry()
        }
        return if (remaining) Result.retry() else Result.success()
    }

    override fun onStopped() {
        if (done.count == 0L) return
        val ctx = applicationContext
        main.post {
            EngineThread.post {
                ElsLog.debug("sync", "background sync stopped")
                SessionEngine.cancelSync(ctx)
            }
        }
    }

    private companion object {
        const val DRAIN_BUDGET_MS = 8 * 60_000L
        const val AWAIT_MS = 9 * 60_000L
    }
}
