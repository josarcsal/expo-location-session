package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.QueueStatus
import com.margelo.nitro.locationsession.core.QueueTarget
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueDatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun unopenableReturnsNullThenRecoversOnceOpenable() {
        val blocker = File(ctx.filesDir, UUID.randomUUID().toString()).also { dirs.add(it) }
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
        val file = File(ctx.filesDir, "${UUID.randomUUID()}/queue.sqlite").also { dirs.add(it.parentFile!!) }
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(4096) { 0x5A })
        val opened = QueueDatabase(ctx, file).store()
        assertNotNull(opened)
        assertEquals(0, opened?.status()?.pending)
        opened?.close()
    }

    private fun newDb(): Pair<QueueDatabase, File> {
        val file = File(ctx.filesDir, "${UUID.randomUUID()}/queue.sqlite").also { dirs.add(it.parentFile!!) }
        return QueueDatabase(ctx, file) to file
    }

    private fun target(id: String, mode: QueueMode = QueueMode.ALL) = QueueTarget(id, "https://x.dev/l", mode, null, "{}", 60_000, null, null, false)

    private fun insert(id: String, uuid: String, mode: QueueMode = QueueMode.ALL) = PendingInsert(id, uuid, System.currentTimeMillis(), "{}", mode, 10_000)

    private fun <T> withRaw(file: File, body: (SQLiteDatabase) -> T): T {
        val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            return body(raw)
        } finally {
            raw.close()
        }
    }

    @Test
    fun failedInsertFallsBackByModeAndFlushesTheBufferInOrder() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.saveTarget(target("s2", QueueMode.LATEST))
        assertEquals(EnqueueResult.Stored(0), db.enqueue(insert("s1", "a")))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        assertEquals(EnqueueResult.Failed, db.enqueue(insert("s2", "x", QueueMode.LATEST)))
        assertTrue(db.failing)
        assertEquals(EnqueueResult.Buffered(false), db.enqueue(insert("s1", "b")))
        assertEquals(EnqueueResult.Buffered(false), db.enqueue(insert("s1", "c")))
        assertEquals(2, db.buffered)
        assertEquals(0, db.prepareSend())
        assertEquals(2, db.buffered)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        assertEquals(EnqueueResult.Stored(0), db.enqueue(insert("s1", "d")))
        assertFalse(db.failing)
        assertEquals(0, db.buffered)
        assertEquals(listOf("a", "b", "c", "d"), db.store()?.claim("s1", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun bufferedRowsAreInsertedBeforeTheNextSend() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.enqueue(insert("s1", "a"))
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        assertEquals(0, db.prepareSend())
        assertEquals(0, db.buffered)
        assertEquals(listOf("a"), db.store()?.claim("s1", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun failedDeleteIsHiddenFromStatusAndRetriedOnTheNextQueueOperation() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.enqueue(insert("s1", "a"))
        db.enqueue(insert("s1", "b"))
        val sent = db.store()!!.claim("s1", 1).map { it.id }
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.delete(sent)
        assertEquals(1, db.status()?.pending)
        assertEquals(2, db.store()?.status()?.pending)
        assertEquals(listOf("b"), db.store()?.claim("s1", 10)?.map { it.uuid })
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        db.prepareSend()
        assertEquals(1, db.store()?.status()?.pending)
        assertEquals(1, db.status()?.pending)
        db.store()?.close()
    }

    @Test
    fun failedReleaseIsRetried() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.enqueue(insert("s1", "a"))
        val ids = db.store()!!.claim("s1", 1).map { it.id }
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE UPDATE ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.release(ids)
        assertTrue(db.store()!!.claim("s1", 10).isEmpty())
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        db.enqueue(insert("s1", "b"))
        assertEquals(listOf("a", "b"), db.store()?.claim("s1", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun targetSavedWhileUnavailableIsStoredBeforeItsRows() {
        val blocker = File(ctx.filesDir, UUID.randomUUID().toString()).also { dirs.add(it) }
        blocker.writeText("not a directory")
        var now = 0L
        val db = QueueDatabase(ctx, File(blocker, "queue.sqlite")) { now }
        db.saveTarget(target("s1"))
        db.markEnded("s1")
        assertEquals(EnqueueResult.Buffered(false), db.enqueue(insert("s1", "a")))
        assertTrue(db.failing)
        blocker.delete()
        now = 5_000
        db.prepareSend()
        assertEquals(0, db.buffered)
        assertFalse(db.failing)
        db.purge()
        assertEquals(true, db.store()?.target("s1")?.ended)
        assertEquals(listOf("a"), db.store()?.claim("s1", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun deleteSessionDropsItsBufferedRows() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.saveTarget(target("s2"))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.enqueue(insert("s1", "a"))
        db.enqueue(insert("s2", "b"))
        db.enqueue(insert("s1", "c"))
        assertEquals(2, db.deleteSession("s1"))
        assertEquals(1, db.buffered)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        db.prepareSend()
        assertEquals(listOf("b"), db.store()?.claim("s2", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun deleteSessionForgetsItsUnsettledRows() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.enqueue(insert("s1", "a"))
        db.enqueue(insert("s1", "b"))
        val sent = db.store()!!.claim("s1", 1).map { it.id }
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.delete(sent)
        assertEquals(1, db.status()?.pending)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        assertEquals(2, db.deleteSession("s1"))
        db.enqueue(insert("s1", "c"))
        assertEquals(QueueStatus(1, db.store()?.status()?.oldestAt, 1), db.status())
        db.store()?.close()
    }

    @Test
    fun failedSessionDeleteIsRetriedOnTheNextQueueOperation() {
        val (db, file) = newDb()
        db.saveTarget(target("s1", QueueMode.LATEST))
        db.enqueue(insert("s1", "a", QueueMode.LATEST))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        assertEquals(0, db.deleteSession("s1"))
        assertNotNull(db.store()?.target("s1"))
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        db.prepareSend()
        assertNull(db.store()?.target("s1"))
        assertEquals(0, db.status()?.pending)
        db.store()?.close()
    }

    @Test
    fun probeClearsTheInsertFailureOnceWritesSucceed() {
        val (db, file) = newDb()
        db.saveTarget(target("s1", QueueMode.LATEST))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        assertEquals(EnqueueResult.Failed, db.enqueue(insert("s1", "a", QueueMode.LATEST)))
        assertTrue(db.failing)
        assertEquals(0, db.probe())
        assertTrue(db.failing)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        assertEquals(0, db.probe())
        assertFalse(db.failing)
        assertEquals(0, db.status()?.pending)
        db.store()?.close()
    }

    @Test
    fun probeFlushesTheBufferAndReportsTrims() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.enqueue(PendingInsert("s1", "a", 0, "{}", QueueMode.ALL, 1))
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.enqueue(PendingInsert("s1", "b", 0, "{}", QueueMode.ALL, 1))
        assertTrue(db.failing)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        assertEquals(1, db.probe())
        assertFalse(db.failing)
        assertEquals(listOf("b"), db.store()?.claim("s1", 10)?.map { it.uuid })
        db.store()?.close()
    }

    @Test
    fun unsettledDeletesAreTrackedPerSessionAndTheirSettlementIsAnnounced() {
        val (db, file) = newDb()
        db.saveTarget(target("s1"))
        db.saveTarget(target("s2"))
        db.enqueue(insert("s1", "a"))
        db.enqueue(insert("s2", "b"))
        var announced = 0
        db.onDeletesSettled = { announced += 1 }
        val sent = db.store()!!.claim("s1", 1).map { it.id }
        withRaw(file) { it.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END") }
        db.delete(sent)
        assertTrue(db.hasUnsettledDeletes("s1"))
        assertFalse(db.hasUnsettledDeletes("s2"))
        db.prepareSend()
        assertEquals(0, announced)
        withRaw(file) { it.execSQL("DROP TRIGGER boom") }
        db.prepareSend()
        assertEquals(1, announced)
        assertFalse(db.hasUnsettledDeletes("s1"))
        db.store()?.close()
    }

    @Test
    fun statusHidesTheGivenRows() {
        val (db, _) = newDb()
        db.saveTarget(target("s1", QueueMode.LATEST))
        db.enqueue(insert("s1", "a", QueueMode.LATEST))
        val id = db.store()!!.latestPending("s1")!!.id
        assertEquals(QueueStatus(0, null, 0), db.status(setOf(id)))
        assertEquals(1, db.status()?.pending)
        db.store()?.close()
    }
}
