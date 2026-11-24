package com.margelo.nitro.locationsession.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueDatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun unopenableReturnsNullThenRecoversOnceOpenable() {
        val blocker = File(ctx.filesDir, UUID.randomUUID().toString())
        blocker.writeText("not a directory")
        val db = QueueDatabase(ctx, File(blocker, "queue.sqlite"))
        assertNull(db.store())
        assertNull(db.store())
        blocker.delete()
        val opened = db.store()
        assertNotNull(opened)
        assertSame(opened, db.store())
        opened?.close()
    }
}
