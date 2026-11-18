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

    fun emit(event: String, payload: JSONObject) {
        val json = payload.toString()
        listeners.values.filter { it.event == event }.forEach {
            try {
                it.callback(json)
            } catch (e: Exception) {
                ElsLog.info("listener for $event threw: $e")
            }
        }
    }
}

object ElsLog {
    private const val TAG = "ExpoLocationSession"

    fun info(message: String) {
        Log.i(TAG, message)
    }
}
