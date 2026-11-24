package com.margelo.nitro.locationsession.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestExecutorTest {
    @Test
    fun abandonedRequestDoesNotBlockTheNextOne() {
        abandonThenRun(interrupt = true)
        abandonThenRun(interrupt = false)
    }

    private fun abandonThenRun(interrupt: Boolean) {
        val requests = RequestExecutor("test.request")
        val started = CountDownLatch(1)
        val hung = CountDownLatch(1)
        val next = CountDownLatch(1)
        requests.execute {
            started.countDown()
            while (true) {
                try {
                    hung.await()
                    break
                } catch (e: InterruptedException) {
                    continue
                }
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        requests.abandon(interrupt)
        requests.execute { next.countDown() }
        assertTrue(next.await(1, TimeUnit.SECONDS))
        assertEquals(1L, hung.count)
        hung.countDown()
    }
}
