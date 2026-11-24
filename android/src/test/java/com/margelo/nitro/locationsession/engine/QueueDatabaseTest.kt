package com.margelo.nitro.locationsession.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        var now = 0L
        val db = QueueDatabase(ctx, File(blocker, "queue.sqlite")) { now }
        assertNull(db.store())
        assertTrue(db.unavailable)
        assertFalse(db.isOpen)
        blocker.delete()
        now = 4_999
        assertNull(db.store())
        now = 5_000
        val opened = db.store()
        assertNotNull(opened)
        assertTrue(db.isOpen)
        assertFalse(db.unavailable)
        assertSame(opened, db.store())
        opened?.close()
    }

    @Test
    fun corruptFileIsRecreated() {
        val file = File(ctx.filesDir, "${UUID.randomUUID()}/queue.sqlite")
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4096) { 0x5A })
        val opened = QueueDatabase(ctx, file).store()
        assertNotNull(opened)
        assertEquals(0, opened?.status()?.pending)
        opened?.close()
    }
}
