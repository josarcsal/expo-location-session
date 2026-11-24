package com.margelo.nitro.locationsession.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackSlotTest {
    private fun fix(ts: Long) = Fix(latitude = 1.0, longitude = 2.0, accuracy = 3.0, timestampMs = ts)

    @Test
    fun newestReplacesOlder() {
        val s = FallbackSlot()
        val first = s.put("s1", "a", fix(1))
        val second = s.put("s1", "b", fix(2))
        assertEquals(first + 1, second)
        assertEquals(FallbackFix("s1", "b", fix(2), second), s.next("s1"))
        assertNull(s.next("s2"))
    }

    @Test
    fun markSentClearsOnlyTheSentGeneration() {
        val s = FallbackSlot()
        val sent = s.put("s1", "a", fix(1))
        s.put("s1", "b", fix(2))
        s.markSent(sent)
        assertEquals("b", s.next("s1")?.uuid)
        s.markSent(sent + 1)
        assertNull(s.next("s1"))
    }

    @Test
    fun queuedRowsUpToTheNewestSlotFixAreSuperseded() {
        val s = FallbackSlot()
        assertFalse(s.supersedes("s1", 5))
        val g = s.put("s1", "a", fix(10))
        s.markSent(g)
        assertTrue(s.supersedes("s1", 9))
        assertTrue(s.supersedes("s1", 10))
        assertFalse(s.supersedes("s1", 11))
        assertFalse(s.supersedes("s2", 9))
        s.put("s1", "b", fix(7))
        assertTrue(s.supersedes("s1", 10))
    }

    @Test
    fun storedFixClearsSlotAndFloor() {
        val s = FallbackSlot()
        s.put("s1", "a", fix(10))
        s.stored()
        assertNull(s.next("s1"))
        assertFalse(s.supersedes("s1", 9))
    }

    @Test
    fun keepDropsOtherSessions() {
        val s = FallbackSlot()
        s.put("s1", "a", fix(10))
        s.keep("s1")
        assertEquals("a", s.next("s1")?.uuid)
        s.keep("s2")
        assertNull(s.next("s1"))
        assertFalse(s.supersedes("s1", 9))
        s.put("s1", "b", fix(10))
        s.keep(null)
        assertNull(s.pending)
    }

    @Test
    fun pickPrefersMemoryThenQueueUnlessSuperseded() {
        val s = FallbackSlot()
        assertEquals(LatestPick.NONE, s.pick("s1", null))
        assertEquals(LatestPick.QUEUE, s.pick("s1", 5))
        val g = s.put("s1", "a", fix(10))
        assertEquals(LatestPick.MEMORY, s.pick("s1", 5))
        assertEquals(LatestPick.QUEUE, s.pick("s2", 5))
        s.markSent(g)
        assertEquals(LatestPick.NONE, s.pick("s1", 5))
        assertEquals(LatestPick.QUEUE, s.pick("s1", 11))
        s.stored()
        assertEquals(LatestPick.QUEUE, s.pick("s1", 5))
    }

    @Test
    fun resumedPendingFixIsQueuedOnlyWhenNewerThanTheQueuedRow() {
        assertTrue(FallbackSlot.resumes(10, null))
        assertTrue(FallbackSlot.resumes(10, 5))
        assertFalse(FallbackSlot.resumes(10, 10))
        assertFalse(FallbackSlot.resumes(10, 12))
    }
}
