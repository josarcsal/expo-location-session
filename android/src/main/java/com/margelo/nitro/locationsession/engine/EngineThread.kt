package com.margelo.nitro.locationsession.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

object EngineThread {
    const val NAME = "els-engine"

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var current: Handler? = null

    val looper: Looper
        get() = handler.looper

    val handler: Handler
        get() = synchronized(lock) {
            val h = current
            if (h != null && thread?.isAlive == true) return h
            val t = HandlerThread(NAME).also { it.start() }
            thread = t
            Handler(t.looper).also { current = it }
        }

    fun isCurrent(): Boolean {
        val mine = Looper.myLooper() ?: return false
        return mine == synchronized(lock) { current?.looper }
    }

    fun post(block: () -> Unit) {
        val r = Runnable { block() }
        val h = handler
        if (!h.post(r)) replace(h).post(r)
    }

    fun postDelayed(r: Runnable, delayMs: Long) {
        val h = handler
        if (!h.postDelayed(r, delayMs)) replace(h).postDelayed(r, delayMs)
    }

    fun removeCallbacks(r: Runnable) {
        handler.removeCallbacks(r)
    }

    private fun replace(dead: Handler): Handler {
        synchronized(lock) {
            if (current === dead) {
                thread = null
                current = null
            }
        }
        return handler
    }
}

fun assertEngine() {
    check(EngineThread.isCurrent()) { "expo-location-session engine must run on the els-engine thread" }
}
