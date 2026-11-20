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

    @Test fun seqLeadsTheLine() {
        val json = encodeLogLine(LogLine(seq = 4321, t = 2, pid = 3, level = "info", tag = "debug", msg = "m", data = mapOf("seq" to 9)))
        assertTrue(json.startsWith("{\"seq\":4321,"))
        assertEquals(4321L, lineSeq(json.toByteArray()))
        val obj = JSONObject(json)
        assertEquals(4321L, obj.getLong("seq"))
        assertEquals("m", obj.getString("msg"))
    }

    @Test fun lineSeqRejectsOtherShapes() {
        assertNull(lineSeq("{\"t\":1,\"seq\":2}".toByteArray()))
        assertNull(lineSeq("{\"seq\":12".toByteArray()))
        assertNull(lineSeq("{\"seq\":,".toByteArray()))
    }

    @Test fun lineSeqReadsASlice() {
        val bytes = "xx{\"seq\":77,\"t\":1}yy".toByteArray()
        assertEquals(77L, lineSeq(bytes, 2, bytes.size - 2))
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
