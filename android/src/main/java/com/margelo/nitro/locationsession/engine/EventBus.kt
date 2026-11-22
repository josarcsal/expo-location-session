package com.margelo.nitro.locationsession.engine

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EventBus {
    private class Listener(val event: String, val callback: (String) -> Unit)

    private val listeners = ConcurrentHashMap<Double, Listener>()
    private val nextId = AtomicLong(0)

    fun add(event: String, callback: (String) -> Unit): Double {
        val id = nextId.incrementAndGet().toDouble()
        listeners[id] = Listener(event, callback)
        return id
    }

    fun remove(id: Double) {
        listeners.remove(id)
    }

    fun hasListeners(event: String): Boolean = listeners.values.any { it.event == event }

    fun emit(event: String, payload: JSONObject) = emitJson(event, payload.toString())

    fun emitJson(event: String, json: String) {
        listeners.values.filter { it.event == event }.forEach {
            try {
                it.callback(json)
            } catch (e: Exception) {
                Log.i("ExpoLocationSession", "listener for $event threw: ${e.javaClass.simpleName}")
            }
        }
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
