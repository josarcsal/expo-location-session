package com.margelo.nitro.locationsession.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UnsettledRowsTest {
    @Test
    fun failedDeleteIsRememberedAndRetried() {
        val u = UnsettledRows()
        assertFalse(u.delete(listOf(1L, 2L)) { false })
        assertEquals(setOf(1L, 2L), u.deletes)
        val calls = mutableListOf<List<Long>>()
        assertEquals(0, u.retry({ calls.add(it); false }, { true }))
        assertEquals(setOf(1L, 2L), u.deletes)
        assertEquals(2, u.retry({ calls.add(it); true }, { true }))
        assertEquals(listOf(listOf(1L, 2L), listOf(1L, 2L)), calls)
        assertTrue(u.isEmpty)
    }

    @Test
    fun successfulDeleteSettlesEarlierFailures() {
        val u = UnsettledRows()
        u.delete(listOf(1L, 2L)) { false }
        u.release(listOf(3L)) { false }
        assertTrue(u.delete(listOf(2L, 3L)) { true })
        assertEquals(setOf(1L), u.deletes)
        assertTrue(u.releases.isEmpty())
    }

    @Test
    fun rowAwaitingDeleteIsNeverReleased() {
        val u = UnsettledRows()
        u.delete(listOf(1L)) { false }
        val released = mutableListOf<List<Long>>()
        assertFalse(u.release(listOf(1L, 2L)) { released.add(it); false })
        assertEquals(listOf(listOf(2L)), released)
        assertEquals(setOf(1L), u.deletes)
        assertEquals(setOf(2L), u.releases)
        assertTrue(u.release(listOf(1L)) { fail("released a sent row"); false })
    }

    @Test
    fun retrySettlesDeletesAndReleasesIndependently() {
        val u = UnsettledRows()
        u.delete(listOf(5L)) { false }
        u.release(listOf(7L)) { false }
        assertEquals(1, u.retry({ false }, { it == listOf(7L) }))
        assertEquals(setOf(5L), u.deletes)
        assertTrue(u.releases.isEmpty())
    }

    @Test
    fun emptyOperationsDoNotCallTheStore() {
        val u = UnsettledRows()
        assertTrue(u.delete(emptyList()) { fail("called"); false })
        assertEquals(0, u.retry({ fail("called"); false }, { fail("called"); false }))
    }

    @Test
    fun retainForgetsRowsThatNoLongerExist() {
        val u = UnsettledRows()
        u.delete(listOf(1L, 2L)) { false }
        u.release(listOf(3L, 4L)) { false }
        assertEquals(listOf(1L, 2L, 3L, 4L), u.ids)
        u.retain(setOf(2L, 4L))
        assertEquals(setOf(2L), u.deletes)
        assertEquals(setOf(4L), u.releases)
    }
}
