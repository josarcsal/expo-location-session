package com.margelo.nitro.locationsession.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedBufferTest {
    @Test
    fun dropsOldestBeyondCapacity() {
        val b = BoundedBuffer<Int>(500)
        for (i in 0 until 500) assertFalse(b.append(i))
        assertTrue(b.append(500))
        val all = b.drain()
        assertEquals(500, all.size)
        assertEquals(1, all.first())
        assertEquals(500, all.last())
        assertEquals(0, b.count)
    }

    @Test
    fun flushInsertsOldestFirstAndKeepsTheRestAfterAFailure() {
        val b = BoundedBuffer<Int>(500)
        for (i in 1..4) b.append(i)
        val seen = mutableListOf<Int>()
        assertEquals(2, b.flush { seen.add(it); it < 3 })
        assertEquals(listOf(1, 2, 3), seen)
        assertEquals(2, b.count)
        assertEquals(2, b.flush { true })
        assertTrue(b.isEmpty)
    }

    @Test
    fun removeAllWhereDropsMatchingItems() {
        val b = BoundedBuffer<Int>(500)
        for (i in 1..4) b.append(i)
        assertEquals(2, b.removeAll { it % 2 == 0 })
        assertEquals(listOf(1, 3), b.drain())
    }
}
