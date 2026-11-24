package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.margelo.nitro.locationsession.core.ConnectivityEdge

class ConnectivityMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var edge = ConnectivityEdge()
    private var current: Network? = null
    private var pending: Runnable? = null
    private var generation = 0

    fun start(onRegained: () -> Unit) {
        assertMain()
        if (callback != null || manager == null) return
        generation += 1
        val gen = generation
        edge = ConnectivityEdge()
        current = manager.activeNetwork
        edge.update(satisfied(current?.let { manager.getNetworkCapabilities(it) }))
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ok = satisfied(caps)
                main.post {
                    if (gen != generation) return@post
                    current = network
                    update(ok, onRegained)
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    if (gen != generation || network != current) return@post
                    update(false, onRegained)
                }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: RuntimeException) {
            ElsLog.debug("sync", "connectivity callback unavailable", mapOf("error" to e.javaClass.simpleName), level = "warn")
        }
    }

    fun stop() {
        assertMain()
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
            main.postDelayed(r, REGAIN_DELAY_MS)
        } else if (!satisfied) {
            cancelPending()
        }
    }

    private fun cancelPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }

    private fun satisfied(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private companion object {
        const val REGAIN_DELAY_MS = 1_000L
    }
}
