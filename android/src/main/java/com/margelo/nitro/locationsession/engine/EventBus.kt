package com.margelo.nitro.locationsession.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EventBus(private val post: (Runnable) -> Unit = { mainHandler.post(it) }) {
    private class Listener(val event: String, val callback: (String) -> Unit)

    private val lock = Any()
    private val listeners = ConcurrentHashMap<Double, Listener>()
    private val nextId = AtomicLong(0)
    private val stickyErrors = LinkedHashMap<String, String>()
    private val awaitingReplay = HashMap<Double, MutableSet<String>>()

    fun add(event: String, callback: (String) -> Unit): Double {
        val id = nextId.incrementAndGet().toDouble()
        synchronized(lock) {
            listeners[id] = Listener(event, callback)
            if (event == "error") awaitingReplay[id] = mutableSetOf()
        }
        if (event == "error") {
            post(Runnable {
                val replay = synchronized(lock) {
                    val deliveredLive = awaitingReplay.remove(id) ?: emptySet<String>()
                    if (listeners[id] == null) emptyList() else stickyErrors.filterKeys { it !in deliveredLive }.values.toList()
                }
                replay.forEach { deliver(event, callback, it) }
            })
        }
        return id
    }

    fun remove(id: Double) {
        synchronized(lock) {
            listeners.remove(id)
            awaitingReplay.remove(id)
        }
    }

    fun hasListeners(event: String): Boolean = listeners.values.any { it.event == event }

    fun emit(event: String, payload: JSONObject) = emitJson(event, payload.toString())

    fun emitStickyError(code: String, message: String, sessionId: String?) {
        val payload = JSONObject().put("code", code).put("message", message)
        sessionId?.let { payload.put("sessionId", it) }
        val json = payload.toString()
        val targets = synchronized(lock) {
            stickyErrors[code] = json
            awaitingReplay.values.forEach { it.add(code) }
            listeners.values.filter { it.event == "error" }
        }
        targets.forEach { deliver("error", it.callback, json) }
    }

    fun clearStickyError(code: String) {
        synchronized(lock) { stickyErrors.remove(code) }
    }

    fun clearStickyErrors() {
        synchronized(lock) { stickyErrors.clear() }
    }

    fun emitJson(event: String, json: String) {
        listeners.values.filter { it.event == event }.forEach { deliver(event, it.callback, json) }
    }

    private fun deliver(event: String, callback: (String) -> Unit, json: String) {
        try {
            callback(json)
        } catch (e: Exception) {
            Log.i("ExpoLocationSession", "listener for $event threw: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    }
}

object ElsLog {
    private const val TAG = "ExpoLocationSession"

    private const val ROUTE_TAG = "ELS"

    fun info(tag: String, message: String, data: Map<String, Any?>? = null, sessionId: String? = null, level: String = "info") {
        Log.i(TAG, message)
        DebugLogger.log(level, tag, message, data, sessionId)
    }

    fun route(message: String, data: Map<String, Any?>? = null, sessionId: String? = null) {
        Log.i(ROUTE_TAG, message)
        Log.i(TAG, message)
        DebugLogger.log("info", "gate", message, data, sessionId)
    }

    fun debug(tag: String, message: String, data: Map<String, Any?>? = null, sessionId: String? = null, level: String = "debug") {
        DebugLogger.log(level, tag, message, data, sessionId)
    }
}
