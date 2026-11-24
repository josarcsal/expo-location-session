package com.margelo.nitro.locationsession.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    private val statuses: List<Int?> = listOf(null, 100, 200, 204, 299, 301, 400, 401, 403, 404, 409, 410, 413, 422, 429, 500, 503)

    @Test
    fun matchesV01ClassifyWhenRunningWithoutRefresh() {
        for (status in statuses) {
            val expected: SyncAction = when (val o = classify(status)) {
                is UploadOutcome.Success -> SyncAction.DeleteSent
                is UploadOutcome.Stop -> SyncAction.Stop(o.reason)
                is UploadOutcome.Drop -> SyncAction.DropSent
                is UploadOutcome.Retry -> SyncAction.Retry
            }
            assertEquals("status $status", expected, syncAction(status, ended = false, refreshConfigured = false, alreadyRefreshed = false))
        }
    }

    @Test
    fun refreshRows() {
        assertEquals(SyncAction.RefreshAndRetry, syncAction(401, ended = false, refreshConfigured = true, alreadyRefreshed = false))
        assertEquals(SyncAction.RefreshAndRetry, syncAction(401, ended = true, refreshConfigured = true, alreadyRefreshed = false))
        assertEquals(SyncAction.Stop(StopReason.UNAUTHORIZED), syncAction(401, ended = false, refreshConfigured = true, alreadyRefreshed = true))
        assertEquals(SyncAction.Retry, syncAction(401, ended = true, refreshConfigured = true, alreadyRefreshed = true))
        assertEquals(SyncAction.Stop(StopReason.UNAUTHORIZED), syncAction(403, ended = false, refreshConfigured = true, alreadyRefreshed = false))
    }

    @Test
    fun endedColumn() {
        fun a(s: Int?) = syncAction(s, ended = true, refreshConfigured = false, alreadyRefreshed = false)
        assertEquals(SyncAction.DeleteSent, a(204))
        assertEquals(SyncAction.DropSent, a(401))
        assertEquals(SyncAction.DropSent, a(403))
        assertEquals(SyncAction.DropSession, a(410))
        assertEquals(SyncAction.DropSent, a(413))
        assertEquals(SyncAction.DropSent, a(422))
        assertEquals(SyncAction.Retry, a(503))
        assertEquals(SyncAction.Retry, a(null))
    }

    @Test
    fun redirectsRetryInBothColumns() {
        for (status in listOf(300, 301, 302, 303, 304, 307, 308)) {
            assertEquals(SyncAction.Retry, syncAction(status, ended = false, refreshConfigured = true, alreadyRefreshed = false))
            assertEquals(SyncAction.Retry, syncAction(status, ended = true, refreshConfigured = false, alreadyRefreshed = false))
        }
    }

    @Test
    fun retention() {
        assertFalse(isExpired(0, 86_400_000, 86_400_000))
        assertTrue(isExpired(0, 86_400_001, 86_400_000))
        assertEquals(1, trimCount(10_001, 10_000))
        assertEquals(0, trimCount(10_000, 10_000))
        assertEquals(0, trimCount(0, 1))
    }
}

class RefreshContractTest {
    private fun parse(s: Int?, b: String) = parseRefreshResponse(s, b)

    @Test
    fun parsesHeadersAndRotation() {
        assertEquals(
            RefreshResult(mapOf("Authorization" to "Bearer n"), mapOf("X-Refresh" to "r2")),
            parse(200, """{"headers":{"Authorization":"Bearer n"},"refreshHeaders":{"X-Refresh":"r2"},"expiresIn":60}"""),
        )
        assertEquals(
            RefreshResult(mapOf("Authorization" to "Bearer n"), null),
            parse(201, """{"headers":{"Authorization":"Bearer n"}}"""),
        )
    }

    @Test
    fun rejects() {
        val cases = listOf(
            200 to "not json", 200 to "[]", 200 to "{}", 200 to """{"headers":{}}""", 200 to """{"headers":{"A":1}}""",
            200 to """{"headers":"x"}""", 200 to """{"headers":{"A":"b"},"refreshHeaders":{"B":2}}""",
            401 to """{"headers":{"A":"b"}}""", 500 to """{"headers":{"A":"b"}}""", 204 to "",
            200 to """{"headers":{"A":"b"}} junk""", 200 to """{"headers":{"A":"b"}}{}""",
        )
        for ((status, body) in cases) assertEquals("$status $body", null, parse(status, body))
    }

    @Test
    fun rejectsNoResponse() {
        assertEquals(null, parse(null, """{"headers":{"A":"b"}}"""))
    }

    @Test
    fun requestBody() {
        assertEquals("""{"sessionId":"s1"}""", refreshRequestBody(null, "s1"))
        assertEquals("""{"k":"v"}""", refreshRequestBody("""{"k":"v"}""", "s1"))
    }
}

class ConnectivityEdgeTest {
    @Test
    fun firesOnlyOnRegain() {
        val cases = listOf(
            listOf(true) to listOf(false),
            listOf(false, true) to listOf(false, true),
            listOf(true, false, true, true) to listOf(false, false, true, false),
            listOf(false, false) to listOf(false, false)
        )
        for ((inputs, expected) in cases) {
            val e = ConnectivityEdge()
            assertEquals("$inputs", expected, inputs.map { e.update(it) })
        }
    }
}

class StopQueueActionTest {
    @Test
    fun latestAlwaysDeletes() {
        for (reason in StopReason.values()) {
            assertEquals("$reason", StopQueueAction.DELETE_ROWS, stopQueueAction(QueueMode.LATEST, reason))
        }
    }

    @Test
    fun allKeepsTrailUnlessRejected() {
        for (r in listOf(StopReason.STOPPED, StopReason.EXPIRED, StopReason.ARRIVED, StopReason.UNAUTHORIZED, StopReason.PERMISSION_REVOKED)) {
            assertEquals("$r", StopQueueAction.MARK_ENDED, stopQueueAction(QueueMode.ALL, r))
        }
        assertEquals(StopQueueAction.DELETE_ROWS_DROPPED, stopQueueAction(QueueMode.ALL, StopReason.REJECTED))
    }
}

class BackgroundSyncGateTest {
    @Test
    fun gate() {
        assertTrue(shouldScheduleSync(true, 1, false))
        assertFalse(shouldScheduleSync(false, 1, false))
        assertFalse(shouldScheduleSync(true, 0, false))
        assertFalse(shouldScheduleSync(true, 5, true))
    }
}
