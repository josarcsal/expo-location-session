package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyAssemblyTest {
    private val full = Fix(latitude = 1.5, longitude = 2.5, accuracy = 7.5, altitude = 30.5, timestampMs = 1_700_000_000_000)
    private fun rec(uuid: String = "u-1", battery: Battery? = Battery(0.5, true)) =
        FixRecord(uuid = uuid, sessionId = "s1", fix = full, battery = battery)

    private fun keys(o: JSONObject) = o.keys().asSequence().toSet()

    @Test
    fun defaultConfigMatchesV01Body() {
        for (extras in listOf("""{"orderId":"o1"}""", "{}", "not json")) {
            for (battery in listOf(Battery(0.5, true), null)) {
                val element = buildElement(rec(battery = battery), SyncConfig(), extras)
                val v01 = buildBody("s1", full, battery, extras)
                assertEquals(v01, element)
                assertEquals(v01, buildRequestBody("s1", listOf(element), SyncConfig(), extras))
            }
        }
    }

    @Test
    fun defaultConfigGoldenBody() {
        val element = buildElement(rec(), SyncConfig(), """{"orderId":"o1"}""")
        assertEquals(buildBody("s1", full, Battery(0.5, true), """{"orderId":"o1"}"""), element)
        val o = JSONObject(element)
        assertEquals(setOf("sessionId", "location", "extras", "battery"), keys(o))
        assertEquals("s1", o.getString("sessionId"))
        val loc = o.getJSONObject("location")
        assertEquals(setOf("latitude", "longitude", "accuracy", "timestamp", "altitude"), keys(loc))
        assertEquals(1.5, loc.getDouble("latitude"), 0.0)
        assertEquals(2.5, loc.getDouble("longitude"), 0.0)
        assertEquals(7.5, loc.getDouble("accuracy"), 0.0)
        assertEquals(1_700_000_000_000L, loc.getLong("timestamp"))
        assertEquals(30.5, loc.getDouble("altitude"), 0.0)
        assertEquals("o1", o.getJSONObject("extras").getString("orderId"))
        assertEquals(1, o.getJSONObject("extras").length())
        assertEquals(0.5, o.getJSONObject("battery").getDouble("level"), 0.0)
        assertEquals(true, o.getJSONObject("battery").getBoolean("charging"))
    }

    @Test
    fun batchLimitFollowsMaxSize() {
        assertEquals(1, batchLimit(SyncConfig()))
        assertEquals(1, batchLimit(SyncConfig(mode = QueueMode.ALL)))
        assertEquals(1, batchLimit(SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(1))))
        assertEquals(20, batchLimit(SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(20))))
    }

    @Test
    fun maxSizeOneSendsOneElementWithId() {
        val sync = SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(1))
        val body = JSONObject(buildRequestBody("s1", listOf(buildElement(rec(), sync, "{}")), sync, "{}"))
        assertEquals(setOf("id", "sessionId", "location", "battery", "extras"), keys(body))
        assertEquals("u-1", body.getString("id"))
    }

    @Test
    fun allModeSingleFixIsV01BodyPlusId() {
        val sync = SyncConfig(mode = QueueMode.ALL)
        val body = JSONObject(buildRequestBody("s1", listOf(buildElement(rec(), sync, "{}")), sync, "{}"))
        assertEquals(setOf("id", "sessionId", "location", "battery", "extras"), keys(body))
        assertEquals("u-1", body.getString("id"))
    }

    @Test
    fun batchAlwaysUsesEnvelopeEvenForOneFix() {
        val sync = SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(maxSize = 50))
        val body = JSONObject(buildRequestBody("s1", listOf(buildElement(rec(), sync, "{}")), sync, """{"n":3}"""))
        assertEquals(setOf("sessionId", "locations", "extras"), keys(body))
        val list = body.getJSONArray("locations")
        assertEquals(1, list.length())
        assertEquals(setOf("id", "location", "battery"), keys(list.getJSONObject(0)))
        assertEquals(3, body.getJSONObject("extras").getInt("n"))
    }

    @Test
    fun batchKeepsOrderAndCustomRoot() {
        val sync = SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(maxSize = 50, rootProperty = "points"))
        val els = listOf("u-1", "u-2", "u-3").map { buildElement(rec(it), sync, "{}") }
        val list = JSONObject(buildRequestBody("s1", els, sync, "not json")).getJSONArray("points")
        assertEquals(listOf("u-1", "u-2", "u-3"), (0 until list.length()).map { list.getJSONObject(it).getString("id") })
    }

    @Test
    fun templateReplacesElementInBothModes() {
        val t = """{"lat":"{{latitude}}"}"""
        val latest = SyncConfig(templateJson = t)
        val one = JSONObject(buildRequestBody("s1", listOf(buildElement(rec(), latest, "{}")), latest, "{}"))
        assertEquals(1, one.length())
        assertEquals(1.5, one.getDouble("lat"), 0.0)
        val batched = SyncConfig(mode = QueueMode.ALL, batch = BatchConfig(maxSize = 5), templateJson = t)
        val env = JSONObject(buildRequestBody("s1", listOf(buildElement(rec(), batched, "{}")), batched, "{}"))
        assertEquals(1.5, env.getJSONArray("locations").getJSONObject(0).getDouble("lat"), 0.0)
        assertTrue(env.getJSONObject("extras").length() == 0)
    }
}
