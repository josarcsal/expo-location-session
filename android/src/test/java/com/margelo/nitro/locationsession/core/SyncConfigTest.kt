package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun baseCfg(sync: SyncConfig = SyncConfig()) = SessionConfig(
    id = "s1",
    expiresAtMs = 10_000,
    destination = null,
    accuracy = "high",
    distanceFilterMeters = 10.0,
    uploadUrl = "https://x.dev/l",
    uploadHeaders = emptyMap(),
    uploadExtrasJson = "{}",
    uploadMinIntervalMs = 15_000,
    notificationTitle = "t",
    notificationText = "x",
    notificationChannelName = "c",
    sync = sync
)

class SyncConfigTest {
    @Test
    fun defaultIsV01() {
        assertEquals(SyncConfig(QueueMode.LATEST, 10_000, 86_400_000), SyncConfig())
        assertNull(validateSync(SyncConfig(), "{}"))
    }

    @Test
    fun rejects() {
        val cases = listOf(
            SyncConfig(batch = BatchConfig(10)) to "batch requires queue.mode 'all'",
            SyncConfig(QueueMode.ALL, batch = BatchConfig(0)) to "batch.maxSize must be between 1 and 500",
            SyncConfig(QueueMode.ALL, batch = BatchConfig(501)) to "batch.maxSize must be between 1 and 500",
            SyncConfig(QueueMode.ALL, batch = BatchConfig(5, "")) to "batch.rootProperty must not be empty",
            SyncConfig(maxRecords = 0) to "queue.maxRecords must be between 1 and 100000",
            SyncConfig(maxRecords = 100_001) to "queue.maxRecords must be between 1 and 100000",
            SyncConfig(maxAgeMs = 59_999) to "queue.maxAgeMs must be between 60000 and 2592000000",
            SyncConfig(maxAgeMs = 2_592_000_001) to "queue.maxAgeMs must be between 60000 and 2592000000",
            SyncConfig(refresh = RefreshConfig("http://x.dev/r")) to "auth.refresh.url must be an https URL",
            SyncConfig(refresh = RefreshConfig("nope")) to "auth.refresh.url must be an https URL",
            SyncConfig(refresh = RefreshConfig("https://x.dev/r", bodyJson = "[1]")) to "auth.refresh.body must be a JSON object",
            SyncConfig(refresh = RefreshConfig("https://x.dev/r", bodyJson = """{"k":"v"} junk""")) to "auth.refresh.body must be a JSON object"
        )
        cases.forEach { (sync, reason) -> assertEquals(reason, validateSync(sync, "{}")) }
    }

    @Test
    fun acceptsBoundaries() {
        listOf(
            SyncConfig(QueueMode.ALL, batch = BatchConfig(1)),
            SyncConfig(QueueMode.ALL, batch = BatchConfig(500)),
            SyncConfig(maxRecords = 1),
            SyncConfig(maxRecords = 100_000),
            SyncConfig(maxAgeMs = 60_000),
            SyncConfig(maxAgeMs = 2_592_000_000),
            SyncConfig(refresh = RefreshConfig("https://x.dev/r", mapOf("X-R" to "1"), """{"k":"v"}"""))
        ).forEach { assertNull(validateSync(it, "{}")) }
    }

    @Test
    fun invalidSyncMakesValidateReturnInvalidConfig() {
        assertNull(validate(baseCfg(), 0, false))
        assertEquals("INVALID_CONFIG", validate(baseCfg(SyncConfig(maxRecords = 0)), 0, false))
    }

    @Test
    fun baseConfigReasonComesBeforeSyncReason() {
        val c = baseCfg(SyncConfig(maxRecords = 0))
        assertEquals("queue.maxRecords must be between 1 and 100000", syncInvalidReason(c))
        val invalidBase = c.copy(id = "")
        assertEquals("INVALID_CONFIG", validate(invalidBase, 0, false))
        assertNull(syncInvalidReason(invalidBase))
        assertNull(syncInvalidReason(invalidBase.copy(sync = SyncConfig())))
    }

    @Test
    fun unknownQueueModeIsRejectedOnDecode() {
        val json = JSONObject(baseCfg().toJson()).apply { getJSONObject("sync").put("mode", "bogus") }.toString()
        assertTrue(runCatching { SessionConfig.fromJson(json) }.isFailure)
    }

    @Test
    fun fromJsonWithoutSyncUsesDefaults() {
        val v01 = """{"id":"s1","expiresAtMs":10000,"accuracy":"high","distanceFilterMeters":10,"uploadUrl":"https://x.dev/l","uploadHeaders":{},""" +
            """"uploadExtrasJson":"{}","uploadMinIntervalMs":15000,"notificationTitle":"t","notificationText":"x","notificationChannelName":"c"}"""
        val c = SessionConfig.fromJson(v01)
        assertEquals("s1", c.id)
        assertEquals(SyncConfig(), c.sync)
    }

    @Test
    fun roundTripsSync() {
        val sync = SyncConfig(
            QueueMode.ALL, 5, 60_000, BatchConfig(20, "pts"), """{"a":"{{id}}"}""",
            RefreshConfig("https://x.dev/r", mapOf("X" to "y"), null)
        )
        val c = baseCfg(sync)
        assertEquals(c, SessionConfig.fromJson(c.toJson()))
    }

    @Test
    fun makeSyncConfigMapsNativeFields() {
        assertEquals(SyncConfig(), makeSyncConfig(QueueMode.LATEST, 10_000.0, 86_400_000.0, null, null, null, null, null, null))
        assertEquals(
            SyncConfig(QueueMode.ALL, 5, 60_000, BatchConfig(20, "locations"), "{}", RefreshConfig("https://x.dev/r", emptyMap(), """{"k":"v"}""")),
            makeSyncConfig(QueueMode.ALL, 5.0, 60_000.0, 20.0, null, "{}", "https://x.dev/r", null, """{"k":"v"}""")
        )
        val rooted = makeSyncConfig(QueueMode.ALL, 5.0, 60_000.0, 3.0, "pts", null, "https://x.dev/r", mapOf("X" to "y"), null)
        assertEquals(BatchConfig(3, "pts"), rooted.batch)
        assertEquals(mapOf("X" to "y"), rooted.refresh?.headers)
    }

    @Test
    fun makeSyncConfigRejectsNonIntegers() {
        val cases = listOf(
            Triple(1.5, 60_000.0, null) to "queue.maxRecords must be an integer",
            Triple(Double.NaN, 60_000.0, null) to "queue.maxRecords must be an integer",
            Triple(10.0, Double.POSITIVE_INFINITY, null) to "queue.maxAgeMs must be an integer",
            Triple(10.0, 9.0e15, null) to "queue.maxAgeMs must be an integer",
            Triple(10.0, 60_000.0, 2.5) to "batch.maxSize must be an integer"
        )
        for ((input, reason) in cases) {
            try {
                makeSyncConfig(QueueMode.ALL, input.first, input.second, input.third, null, null, null, null, null)
                fail("expected $reason")
            } catch (e: InvalidConfigException) {
                assertEquals(reason, e.reason)
            }
        }
    }
}
