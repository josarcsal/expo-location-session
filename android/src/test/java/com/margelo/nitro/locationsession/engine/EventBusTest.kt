package com.margelo.nitro.locationsession.engine

import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventBusTest {
    private fun codes(received: List<String>) = received.map { JSONObject(it).getString("code") }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun stickyErrorIsReplayedToLateListener() {
        val bus = EventBus()
        bus.emitStickyError("QUEUE_UNAVAILABLE", "unavailable", "s1")
        val received = mutableListOf<String>()
        bus.add("error") { received.add(it) }
        assertEquals(emptyList<String>(), received)
        idle()
        assertEquals(listOf("QUEUE_UNAVAILABLE"), codes(received))
        assertEquals("s1", JSONObject(received[0]).getString("sessionId"))
        idle()
        assertEquals(1, received.size)
    }

    @Test
    fun stickyErrorDeliveredLiveIsNotReplayed() {
        val bus = EventBus()
        val received = mutableListOf<String>()
        bus.add("error") { received.add(it) }
        bus.emitStickyError("QUEUE_UNAVAILABLE", "unavailable", null)
        idle()
        assertEquals(listOf("QUEUE_UNAVAILABLE"), codes(received))
    }

    @Test
    fun clearedStickyErrorIsNotReplayed() {
        val bus = EventBus()
        bus.emitStickyError("QUEUE_UNAVAILABLE", "unavailable", null)
        bus.clearStickyError("QUEUE_UNAVAILABLE")
        val received = mutableListOf<String>()
        bus.add("error") { received.add(it) }
        idle()
        assertEquals(emptyList<String>(), received)
    }

    @Test
    fun removedListenerGetsNoReplayAndOtherEventsAreNotSticky() {
        val bus = EventBus()
        bus.emitStickyError("QUEUE_UNAVAILABLE", "unavailable", null)
        val removed = mutableListOf<String>()
        bus.remove(bus.add("error") { removed.add(it) })
        val uploads = mutableListOf<String>()
        bus.add("upload") { uploads.add(it) }
        idle()
        assertEquals(emptyList<String>(), removed)
        assertEquals(emptyList<String>(), uploads)
    }
}
