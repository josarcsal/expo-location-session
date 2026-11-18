package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cfg(
    id: String = "s1",
    expiresAtMs: Long = 10_000,
    dest: Destination? = null,
    accuracy: String = "high",
    distanceFilter: Double = 10.0,
    url: String = "https://x.dev/l",
    minInterval: Long = 15_000,
    extras: String = "{}"
) = SessionConfig(
    id = id,
    expiresAtMs = expiresAtMs,
    destination = dest,
    accuracy = accuracy,
    distanceFilterMeters = distanceFilter,
    uploadUrl = url,
    uploadHeaders = emptyMap(),
    uploadExtrasJson = extras,
    uploadMinIntervalMs = minInterval,
    notificationTitle = "Title",
    notificationText = "Text",
    notificationChannelName = "Channel"
)

private fun fix(lat: Double = 0.0, lng: Double = 0.0, acc: Double = 5.0, ts: Long = 0) =
    Fix(latitude = lat, longitude = lng, accuracy = acc, timestampMs = ts)

private val here = Destination(40.4168, -3.7038, 50.0)

class ValidationTests {
    @Test fun rejectsAlreadyRunning() {
        assertEquals("ALREADY_RUNNING", validate(cfg(), nowMs = 0, running = true))
    }

    @Test fun rejectsExpired() {
        assertEquals("EXPIRED", validate(cfg(expiresAtMs = 999), nowMs = 1000, running = false))
        assertEquals("EXPIRED", validate(cfg(expiresAtMs = 1000), nowMs = 1000, running = false))
    }

    @Test fun acceptsValid() {
        assertNull(validate(cfg(), nowMs = 0, running = false))
        assertNull(validate(cfg(dest = here), nowMs = 0, running = false))
    }

    @Test fun orderIsRunningThenInvalidThenExpired() {
        assertEquals("ALREADY_RUNNING", validate(cfg(id = ""), nowMs = 0, running = true))
        assertEquals("INVALID_CONFIG", validate(cfg(id = "", expiresAtMs = 1), nowMs = 1000, running = false))
    }

    @Test fun rejectsInvalidConfig() {
        listOf(
            cfg(url = "http://x.dev/l"),
            cfg(id = ""),
            cfg(url = "not a url"),
            cfg(url = ""),
            cfg(dest = Destination(1.0, 1.0, 0.0)),
            cfg(dest = Destination(1.0, 1.0, -5.0)),
            cfg(distanceFilter = -1.0),
            cfg(minInterval = -1),
            cfg(accuracy = "best")
        ).forEach {
            assertEquals("INVALID_CONFIG", validate(it, nowMs = 0, running = false))
        }
    }

    @Test fun acceptsKnownAccuracy() {
        listOf("high", "balanced", "low").forEach {
            assertNull(validate(cfg(accuracy = it), nowMs = 0, running = false))
        }
    }

    @Test fun rejectsNotificationPermissionDenied() {
        assertEquals(
            "NOTIFICATION_PERMISSION_DENIED",
            validate(cfg(), nowMs = 0, running = false, notificationsGranted = false)
        )
    }

    @Test fun notificationPermissionOrdering() {
        assertEquals("ALREADY_RUNNING", validate(cfg(), nowMs = 0, running = true, notificationsGranted = false))
        assertEquals("INVALID_CONFIG", validate(cfg(id = ""), nowMs = 0, running = false, notificationsGranted = false))
        assertEquals(
            "NOTIFICATION_PERMISSION_DENIED",
            validate(cfg(expiresAtMs = 1), nowMs = 1000, running = false, notificationsGranted = false)
        )
    }
}

class StopPolicyTests {
    @Test fun acceptFixDropsCachedFixFromBeforeStart() {
        assertFalse(acceptFix(fix(ts = 999), startedAtMs = 1000))
        assertTrue(acceptFix(fix(ts = 1000), startedAtMs = 1000))
    }

    @Test fun arrivalNeedsGoodAccuracy() {
        assertTrue(isArrival(here, fix(lat = 40.4168, lng = -3.7038, acc = 20.0)))
        assertFalse(isArrival(here, fix(lat = 40.4168, lng = -3.7038, acc = 500.0)))
        assertFalse(isArrival(here, fix(lat = 40.4200, lng = -3.7038, acc = 10.0)))
    }

    @Test fun smallRadiusAllowsUpTo100mAccuracy() {
        val small = Destination(40.4168, -3.7038, 10.0)
        assertTrue(isArrival(small, fix(lat = 40.4168, lng = -3.7038, acc = 100.0)))
        assertFalse(isArrival(small, fix(lat = 40.4168, lng = -3.7038, acc = 101.0)))
    }

    @Test fun expiryWinsOverArrival() {
        val atHere = fix(lat = 40.4168, lng = -3.7038, acc = 5.0)
        assertEquals(StopReason.EXPIRED, stopDecision(cfg(expiresAtMs = 100, dest = here), atHere, nowMs = 100))
        assertEquals(StopReason.ARRIVED, stopDecision(cfg(expiresAtMs = 100, dest = here), atHere, nowMs = 99))
        assertNull(stopDecision(cfg(expiresAtMs = 100, dest = here), null, nowMs = 99))
        assertNull(stopDecision(cfg(expiresAtMs = 100), atHere, nowMs = 99))
    }

    @Test fun stopReasonWireValues() {
        assertEquals(
            listOf("stopped", "expired", "arrived", "unauthorized", "rejected", "permissionRevoked"),
            StopReason.values().map { it.wire }
        )
    }
}

class UploadPolicyTests {
    @Test fun classifyStatus() {
        assertEquals(UploadOutcome.Success, classify(204))
        assertEquals(UploadOutcome.Stop(StopReason.UNAUTHORIZED), classify(401))
        assertEquals(UploadOutcome.Stop(StopReason.UNAUTHORIZED), classify(403))
        assertEquals(UploadOutcome.Stop(StopReason.REJECTED), classify(410))
        assertEquals(UploadOutcome.Drop, classify(422))
        assertEquals(UploadOutcome.Retry, classify(503))
        assertEquals(UploadOutcome.Retry, classify(null))
        assertEquals(UploadOutcome.Retry, classify(301))
        assertEquals(UploadOutcome.Retry, classify(100))
    }

    @Test fun backoffCapsAtSixtySeconds() {
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 60000L, 60000L), (0..5).map { backoffMs(it) })
    }

    @Test fun throttle() {
        assertTrue(shouldUploadNow(null, 0, 15000, false))
        assertFalse(shouldUploadNow(0, 14999, 15000, false))
        assertTrue(shouldUploadNow(0, 15000, 15000, false))
        assertTrue(shouldUploadNow(0, 14999, 15000, true))
    }
}

class PendingSlotTests {
    @Test fun olderSuccessDoesNotClearNewerPending() {
        val s = PendingSlot()
        val g1 = s.put(fix(ts = 1))
        s.put(fix(ts = 2))
        s.markSent(g1)
        assertEquals(2L, s.fix?.timestampMs)
    }

    @Test fun matchingSuccessClears() {
        val s = PendingSlot()
        val g = s.put(fix(ts = 1))
        s.markSent(g)
        assertNull(s.fix)
    }
}

class BodyTests {
    @Test fun fullShape() {
        val f = Fix(1.5, 2.5, 7.0, altitude = 30.0, speed = 2.0, heading = 90.0, timestampMs = 1_700_000_000_000)
        val body = JSONObject(buildBody("s1", f, Battery(0.5, true), """{"orderId":"o1","n":3}"""))
        assertEquals("s1", body.getString("sessionId"))
        val loc = body.getJSONObject("location")
        assertEquals(1.5, loc.getDouble("latitude"), 0.0)
        assertEquals(2.5, loc.getDouble("longitude"), 0.0)
        assertEquals(7.0, loc.getDouble("accuracy"), 0.0)
        assertEquals(30.0, loc.getDouble("altitude"), 0.0)
        assertEquals(2.0, loc.getDouble("speed"), 0.0)
        assertEquals(90.0, loc.getDouble("heading"), 0.0)
        assertEquals(1_700_000_000_000L, loc.getLong("timestamp"))
        assertTrue(loc.get("timestamp") is Long)
        val battery = body.getJSONObject("battery")
        assertEquals(0.5, battery.getDouble("level"), 0.0)
        assertEquals(true, battery.getBoolean("charging"))
        val extras = body.getJSONObject("extras")
        assertEquals("o1", extras.getString("orderId"))
        assertEquals(3, extras.getInt("n"))
    }

    @Test fun omitsNilOptionals() {
        val body = JSONObject(buildBody("s1", fix(lat = 1.0, lng = 2.0, acc = 3.0, ts = 4), null, "{}"))
        val loc = body.getJSONObject("location")
        val keys = loc.keys().asSequence().toSet()
        assertEquals(setOf("latitude", "longitude", "accuracy", "timestamp"), keys)
        assertFalse(body.has("battery"))
        assertEquals(0, body.getJSONObject("extras").length())
    }

    @Test fun malformedExtrasBecomeEmptyObject() {
        listOf("not json", "", "[1,2]", "42").forEach {
            val body = JSONObject(buildBody("s1", fix(), null, it))
            assertEquals(0, body.getJSONObject("extras").length())
        }
    }
}

class SessionConfigJsonTests {
    @Test fun roundTrip() {
        val c = SessionConfig(
            id = "s1",
            expiresAtMs = 1_700_000_000_000,
            destination = here,
            accuracy = "balanced",
            distanceFilterMeters = 12.5,
            uploadUrl = "https://x.dev/l",
            uploadHeaders = mapOf("Authorization" to "Bearer t", "X-A" to "b"),
            uploadExtrasJson = """{"orderId":"o1"}""",
            uploadMinIntervalMs = 15_000,
            notificationTitle = "Sharing location",
            notificationText = "Live tracking",
            notificationChannelName = "Tracking"
        )
        assertEquals(c, SessionConfig.fromJson(c.toJson()))
    }

    @Test fun roundTripWithoutDestination() {
        val c = cfg()
        assertEquals(c, SessionConfig.fromJson(c.toJson()))
    }
}
