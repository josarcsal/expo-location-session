package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            SyncConfig(refresh = RefreshConfig("https://x.dev/r", bodyJson = "[1]")) to "auth.refresh.body must be a JSON object"
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
    fun fromJsonWithoutSyncUsesDefaults() {
        val v01 = JSONObject(baseCfg().toJson()).apply { remove("sync") }.toString()
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
}
