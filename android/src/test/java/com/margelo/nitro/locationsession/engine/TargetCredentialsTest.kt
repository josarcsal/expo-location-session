package com.margelo.nitro.locationsession.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetCredentialsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun roundTripPerSessionAndRemove() {
        val c = TargetCredentials(ctx)
        c.save("s1", mapOf("Authorization" to "Bearer a"), mapOf("X-R" to "r"))
        c.save("s2", mapOf("Authorization" to "Bearer b"), emptyMap())
        assertEquals(Credentials(mapOf("Authorization" to "Bearer a"), mapOf("X-R" to "r")), TargetCredentials(ctx).load("s1"))
        c.remove("s1")
        assertNull(c.load("s1"))
        assertEquals(mapOf("Authorization" to "Bearer b"), c.load("s2")?.headers)
    }
}
