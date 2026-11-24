package com.margelo.nitro.locationsession.engine

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class QueueDatabase(context: Context, private val file: File) {
    private val appContext = context.applicationContext
    private var opened: QueueStore? = null
    private var loggedUnavailable = false

    @Synchronized
    fun store(): QueueStore? {
        opened?.let { return it }
        val s = try {
            file.parentFile?.mkdirs()
            QueueStore(appContext, file)
        } catch (e: Exception) {
            if (!loggedUnavailable) {
                loggedUnavailable = true
                ElsLog.info("queue", "database unavailable: ${e.javaClass.simpleName}", mapOf("error" to e.message), level = "warn")
            }
            return null
        }
        opened = s
        loggedUnavailable = false
        if (recovered.compareAndSet(false, true)) {
            val rows = s.recoverInFlight()
            ElsLog.debug("queue", "recovered in-flight rows", mapOf("rows" to rows))
        }
        val purged = s.purge(nowMs())
        if (purged.rowsDeleted > 0 || purged.targetsRemoved.isNotEmpty()) {
            ElsLog.debug("queue", "purged", mapOf("rows" to purged.rowsDeleted, "targets" to purged.targetsRemoved.size))
        }
        return s
    }

    companion object {
        private val recovered = AtomicBoolean(false)
        private var shared: QueueDatabase? = null

        @Synchronized
        fun shared(context: Context): QueueDatabase = shared ?: QueueDatabase(
            context,
            File(context.applicationContext.filesDir, "expo-location-session/queue.sqlite")
        ).also { shared = it }
    }
}
