package com.margelo.nitro.locationsession.engine

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.locationsession.core.SessionConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

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
        try {
            c.startCommand(0, 1)
            idleEngine()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(c.get()).isStoppedBySelf)
        } finally {
            onEngine { LaunchGate.serviceStartDelivered() }
            c.destroy()
        }
    }

    @Test
    fun processStartDrainIsQueuedAfterTheMainThreadFinishesStarting() {
        idleEngine()
        val engine = shadowOf(EngineThread.looper)
        engine.pause()
        try {
            Robolectric.buildContentProvider(SessionInitProvider::class.java).create()
            assertTrue(engine.isIdle)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(engine.isIdle)
        } finally {
            engine.unPause()
        }
        idleEngine()
    }

    @Test
    fun serviceStoppingWithoutASessionIsStartedAgainByANewSession() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val c = Robolectric.buildService(LocationTrackingService::class.java).create()
        try {
            c.startCommand(0, 1)
            idleEngine()
            onEngine { LaunchGate.promote(ctx) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(LocationTrackingService::class.java.name, shadowOf(ctx as Application).nextStartedService?.component?.className)
            assertEquals(1, shadowOf(c.get()).stopSelfResultId)
        } finally {
            onEngine {
                LaunchGate.serviceStartDelivered()
                LaunchGate.onPromoted(ctx)
            }
            c.destroy()
        }
    }

    @Test
    fun serviceKeptForASessionIsTreatedAsForegroundAgain() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val active = SessionEngine::class.java.getDeclaredField("config").apply { isAccessible = true }
        val c = Robolectric.buildService(LocationTrackingService::class.java).create()
        try {
            c.startCommand(0, 1)
            idleEngine()
            active.set(null, config())
            shadowOf(Looper.getMainLooper()).idle()
            idleEngine()
            onEngine { LaunchGate.promote(ctx) }
            assertEquals(null, shadowOf(ctx as Application).nextStartedService)
            assertFalse(shadowOf(c.get()).isStoppedBySelf)
        } finally {
            active.set(null, null)
            onEngine {
                LaunchGate.serviceStartDelivered()
                LaunchGate.onPromoted(ctx)
            }
            c.destroy()
        }
    }

    @Test
    @Config(shadows = [ServiceStateAtStartForeground::class])
    fun serviceIsNotSeenAliveBeforeItIsForeground() {
        ServiceStateAtStartForeground.seen.clear()
        val c = Robolectric.buildService(LocationTrackingService::class.java).create()
        try {
            assertEquals(listOf(false to false), ServiceStateAtStartForeground.seen)
            assertTrue(LocationTrackingService.isAlive)
            assertTrue(LocationTrackingService.isForeground)
        } finally {
            c.destroy()
            idleEngine()
        }
    }
}

@Implements(Service::class)
class ServiceStateAtStartForeground : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        seen.add(LocationTrackingService.isAlive to LocationTrackingService.isForeground)
        super.startForeground(id, notification, foregroundServiceType)
    }

    companion object {
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
    }
}
