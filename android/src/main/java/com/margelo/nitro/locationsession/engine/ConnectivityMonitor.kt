package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.margelo.nitro.locationsession.core.ConnectivityEdge

class ConnectivityMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var edge = ConnectivityEdge()
    private var current: Network? = null
    private var pending: Runnable? = null
    private var generation = 0
    private var registerFailedAtMs: Long? = null

    fun start(onRegained: () -> Unit) {
        assertEngine()
        if (callback != null || manager == null) return
        registerFailedAtMs?.let { if (nowMs() - it < REGISTER_RETRY_MS) return }
        generation += 1
        val gen = generation
        edge = ConnectivityEdge()
        current = manager.activeNetwork
        edge.update(satisfied(current?.let { manager.getNetworkCapabilities(it) }))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = satisfied(caps)
                EngineThread.post {
                    if (gen != generation) return@post
                    current = network
                    update(ok, onRegained)
                }
            }

            override fun onLost(network: Network) {
                EngineThread.post {
                    if (gen != generation || network != current) return@post
                    update(false, onRegained)
                }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(cb)
            callback = cb
            registerFailedAtMs = null
        } catch (e: RuntimeException) {
            if (registerFailedAtMs == null) {
                ElsLog.debug("sync", "connectivity callback unavailable", mapOf("error" to e.javaClass.simpleName), level = "warn")
            }
            registerFailedAtMs = nowMs()
        }
    }

    fun stop() {
        assertEngine()
        generation += 1
        callback?.let { cb -> runCatching { manager?.unregisterNetworkCallback(cb) } }
        callback = null
        current = null
        cancelPending()
    }

    private fun update(satisfied: Boolean, onRegained: () -> Unit) {
        if (edge.update(satisfied)) {
            cancelPending()
            val gen = generation
            val r = Runnable {
                pending = null
                if (gen == generation) onRegained()
            }
            pending = r
            EngineThread.postDelayed(r, REGAIN_DELAY_MS)
        } else if (!satisfied) {
            cancelPending()
        }
    }

    private fun cancelPending() {
        pending?.let { EngineThread.removeCallbacks(it) }
        pending = null
    }

    private fun satisfied(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val REGAIN_DELAY_MS = 1_000L
        const val REGISTER_RETRY_MS = 60_000L
    }
}
