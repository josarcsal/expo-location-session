package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.locationsession.core.SessionConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineThreadTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun idleEngine() = shadowOf(EngineThread.looper).idle()

    private fun <T> onEngine(block: () -> T): T {
        var result: Result<T>? = null
        EngineThread.post { result = runCatching(block) }
        idleEngine()
        return result!!.getOrThrow()
    }

    private fun config() = SessionConfig(
        id = "s1",
        expiresAtMs = nowMs() + 3_600_000,
        destination = null,
        accuracy = "high",
        distanceFilterMeters = 0.0,
        uploadUrl = "https://127.0.0.1/",
        uploadHeaders = emptyMap(),
        uploadExtrasJson = "{}",
        uploadMinIntervalMs = 0,
        notificationTitle = "",
        notificationText = "",
        notificationChannelName = ""
    )

    @Test
    fun engineThreadIsNotMain() {
        assertEquals(EngineThread.NAME, onEngine { Thread.currentThread().name })
        assertEquals("els-engine", EngineThread.NAME)
        assertNotEquals(Looper.getMainLooper(), EngineThread.looper)
    }

    @Test
    fun assertEngineThrowsOnMain() {
        val e = assertThrows(IllegalStateException::class.java) { assertEngine() }
        assertTrue(e.message!!.contains("els-engine"))
    }

    @Test
    fun engineEntryPointsRejectTheMainThread() {
        assertThrows(IllegalStateException::class.java) { SessionEngine.syncQueue(ctx) {} }
    }

    @Test
    fun syncQueuePostedFromMainCompletesOnTheEngine() {
        var name: String? = null
        EngineThread.post { SessionEngine.syncQueue(ctx) { name = Thread.currentThread().name } }
        idleEngine()
        assertEquals("els-engine", name)
    }

    @Test
    fun locationBroadcastOnMainIsProcessedOnTheEngineThread() {
        SessionStore(ctx).save(config().copy(expiresAtMs = nowMs() - 1_000), nowMs() - 60_000)
        var thread: String? = null
        var json: String? = null
        val id = SessionEngine.events.add("stop") {
            thread = Thread.currentThread().name
            json = it
        }
        try {
            ctx.sendBroadcast(Intent(ctx, LaunchGate::class.java).setAction(LaunchGate.Kind.LOCATION.action))
            shadowOf(Looper.getMainLooper()).idle()
            idleEngine()
            assertEquals("els-engine", thread)
            assertEquals("expired", JSONObject(json!!).getString("reason"))
        } finally {
            SessionEngine.events.remove(id)
        }
    }

    @Test
    fun engineThreadIsRecreatedAfterItDies() {
        val old = onEngine { Thread.currentThread() }
        EngineThread.looper.quit()
        old.join(2_000)
        val fresh = onEngine { Thread.currentThread() }
        assertNotSame(old, fresh)
        assertEquals("els-engine", fresh.name)
    }

    @Test
    fun serviceStartsForegroundOnMainThenStopsWhenTheEngineHasNoSession() {
        val c = Robolectric.buildService(LocationTrackingService::class.java).create()
        assertNotNull(shadowOf(c.get()).lastForegroundNotification)
        c.startCommand(0, 1)
        idleEngine()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(c.get()).isStoppedBySelf)
    }
}
