package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SanitizeTest {
    private val full = Fix(latitude = 40.4168, longitude = -3.7038, accuracy = 12.5, altitude = 650.2, speed = 1.4, heading = 270.0, timestampMs = 1_700_000_000_000)

    @Test
    fun finiteFixIsUnchanged() {
        assertEquals(full, sanitize(full))
        assertEquals(
            buildBody("s1", full, Battery(0.5, true), """{"k":"v"}"""),
            buildBody("s1", sanitize(full)!!, Battery(0.5, true), """{"k":"v"}""")
        )
    }

    @Test
    fun dropsUnusableFix() {
        listOf(
            Fix(latitude = Double.NaN, longitude = 0.0, accuracy = 5.0, timestampMs = 1),
            Fix(latitude = 0.0, longitude = Double.POSITIVE_INFINITY, accuracy = 5.0, timestampMs = 1),
            Fix(latitude = Double.NEGATIVE_INFINITY, longitude = 0.0, accuracy = 5.0, timestampMs = 1),
            Fix(latitude = 0.0, longitude = 0.0, accuracy = Double.NaN, timestampMs = 1),
            Fix(latitude = 0.0, longitude = 0.0, accuracy = Double.POSITIVE_INFINITY, timestampMs = 1),
            Fix(latitude = 0.0, longitude = 0.0, accuracy = -1.0, timestampMs = 1),
            Fix(latitude = 90.5, longitude = 0.0, accuracy = 5.0, timestampMs = 1),
            Fix(latitude = 0.0, longitude = -180.5, accuracy = 5.0, timestampMs = 1)
        ).forEach { assertNull("$it", sanitize(it)) }
    }

    @Test
    fun nonFiniteOptionalsBecomeAbsent() {
        val fix = Fix(1.0, 2.0, 3.0, altitude = Double.NaN, speed = Double.POSITIVE_INFINITY, heading = Double.NEGATIVE_INFINITY, timestampMs = 4)
        assertEquals(Fix(1.0, 2.0, 3.0, timestampMs = 4), sanitize(fix))
        val location = JSONObject(buildBody("s1", sanitize(fix)!!, null, "{}")).getJSONObject("location")
        assertEquals(listOf("accuracy", "latitude", "longitude", "timestamp"), location.keys().asSequence().sorted().toList())
    }

    @Test
    fun negativeSpeedAndHeadingBecomeAbsent() {
        val fix = Fix(1.0, 2.0, 3.0, altitude = -10.0, speed = -1.0, heading = -1.0, timestampMs = 4)
        assertEquals(Fix(1.0, 2.0, 3.0, altitude = -10.0, timestampMs = 4), sanitize(fix))
    }
}
