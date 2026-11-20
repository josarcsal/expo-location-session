package com.margelo.nitro.locationsession.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun opts(
    enabled: Boolean = true,
    url: String? = "https://x.dev/logs",
    flush: Long = 60_000,
    maxBytes: Long = 1_000_000
) = DebugOptions(enabled = enabled, url = url, headers = emptyMap(), flushIntervalMs = flush, maxFileBytes = maxBytes)

class DebugValidationTests {
    @Test fun rejectsHttpUrl() {
        val reason = validateDebugOptions(opts(url = "http://x.dev/logs"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("https"))
    }

    @Test fun rejectsShortFlushInterval() {
        assertNotNull(validateDebugOptions(opts(flush = 9999)))
    }

    @Test fun rejectsMaxFileBytesOutOfRange() {
        assertNotNull(validateDebugOptions(opts(maxBytes = 99_999)))
        assertNotNull(validateDebugOptions(opts(maxBytes = 10_000_001)))
    }

    @Test fun acceptsDisabledWithoutUrl() {
        assertNull(validateDebugOptions(opts(enabled = false, url = null)))
    }
}

class DebugEncodingTests {
    @Test fun dropsNaNInData() {
        val line = LogLine(seq = 1, t = 2, pid = 3, level = "info", tag = "debug", msg = "m", data = mapOf("x" to Double.NaN, "y" to 1))
        val json = encodeLogLine(line)
        val data = JSONObject(json).getJSONObject("data")
        assertFalse(data.has("x"))
        assertEquals(1, data.getInt("y"))
        assertFalse(json.contains("\n"))
    }
}

class DebugBatchTests {
    @Test fun cursorInsideRange() {
        assertEquals(BatchPlan(fromSeq = 101, toSeq = 600), planBatch(10L..900L, cursor = 100))
    }

    @Test fun cursorBeforeRange() {
        assertEquals(BatchPlan(fromSeq = 400, toSeq = 899, lostFrom = 101, lostTo = 399), planBatch(400L..900L, cursor = 100))
    }

    @Test fun nothingToSend() {
        assertNull(planBatch(10L..900L, cursor = 900))
    }
}
