package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.locationsession.core.Fix
import com.margelo.nitro.locationsession.core.SessionConfig
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploaderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val requests = AtomicInteger()
    private lateinit var server: ServerSocket
    private lateinit var dir: File

    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    requests.incrementAndGet()
                    it.getOutputStream().write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
        dir = File(ctx.filesDir, UUID.randomUUID().toString())
    }

    @After
    fun stopServer() {
        server.close()
        dir.deleteRecursively()
    }

    private fun uploader(): Uploader =
        Uploader(ctx, SessionStore(ctx), QueueDatabase(ctx, File(dir, "queue.sqlite")), TargetCredentials(ctx), EventBus())

    private fun config() = SessionConfig(
        id = "s1",
        expiresAtMs = nowMs() + 3_600_000,
        destination = null,
        accuracy = "high",
        distanceFilterMeters = 0.0,
        uploadUrl = "http://127.0.0.1:${server.localPort}/",
        uploadHeaders = emptyMap(),
        uploadExtrasJson = "{}",
        uploadMinIntervalMs = 0,
        notificationTitle = "",
        notificationText = "",
        notificationChannelName = ""
    )

    private fun fix(ts: Long) = Fix(latitude = 19.43, longitude = -99.13, accuracy = 5.0, timestampMs = ts)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitSettled(u: Uploader, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (requests.get() >= count && !u.isBusy) return
            Thread.sleep(10)
        }
        throw AssertionError("expected $count settled requests, got ${requests.get()}")
    }

    @Test
    fun newFixWaitsForTheBackoffAfterAFailure() {
        val u = uploader()
        u.begin(config(), null, null)
        u.put(fix(nowMs()))
        u.pump()
        awaitSettled(u, 1)

        u.put(fix(nowMs() + 1))
        u.pump()
        Thread.sleep(300)
        idle()
        assertEquals(1, requests.get())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
        awaitSettled(u, 2)
        assertEquals(2, requests.get())

        u.put(fix(nowMs() + 2))
        u.pump()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_000))
        Thread.sleep(300)
        idle()
        assertEquals(2, requests.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        awaitSettled(u, 3)
        assertEquals(3, requests.get())
        u.reset()
    }
}
