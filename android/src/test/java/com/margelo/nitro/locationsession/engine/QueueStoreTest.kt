package com.margelo.nitro.locationsession.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.margelo.nitro.locationsession.core.BatchConfig
import com.margelo.nitro.locationsession.core.PurgeResult
import com.margelo.nitro.locationsession.core.QueueMode
import com.margelo.nitro.locationsession.core.QueueStatus
import com.margelo.nitro.locationsession.core.QueueTarget
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newFile() = File(context.filesDir, "${UUID.randomUUID()}.sqlite")

    private fun open(file: File = newFile()) = QueueStore(context, file)

    private fun target(id: String, mode: QueueMode = QueueMode.ALL, maxAgeMs: Long = 60_000, ended: Boolean = false) =
        QueueTarget(id, "https://x.dev/l", mode, null, "{}", maxAgeMs, null, null, ended)

    private fun put(s: QueueStore, id: String, uuid: String, ts: Long = 0, mode: QueueMode = QueueMode.ALL, max: Int = 10_000): Int =
        s.insert(id, uuid, ts, "{}", mode, max)

    @Test
    fun latestInsertKeepsInFlightRow() {
        val s = open()
        s.saveTarget(target("s1", QueueMode.LATEST))
        put(s, "s1", "a", mode = QueueMode.LATEST)
        val a = s.claim("s1", 1).first()
        put(s, "s1", "b", mode = QueueMode.LATEST)
        put(s, "s1", "c", mode = QueueMode.LATEST)
        assertEquals("c", s.latestPending("s1")?.uuid)
        assertEquals(2, s.status().pending)
        s.delete(listOf(a.id))
        assertEquals(listOf("c"), s.claim("s1", 10).map { it.uuid })
    }

    @Test
    fun trimSkipsInFlightRows() {
        val s = open()
        s.saveTarget(target("s1"))
        for (u in listOf("r1", "r2", "r3")) assertEquals(0, put(s, "s1", u, max = 3))
        s.claim("s1", 1)
        assertEquals(1, put(s, "s1", "r4", max = 3))
        s.release(s.claim("s1", 10).map { it.id })
        s.recoverInFlight()
        assertEquals(listOf("r1", "r3", "r4"), s.claim("s1", 10).map { it.uuid })
    }

    @Test
    fun recoverInFlightAfterReopen() {
        val file = newFile()
        val s = open(file)
        s.saveTarget(target("s1"))
        put(s, "s1", "a")
        put(s, "s1", "b")
        assertEquals(2, s.claim("s1", 10).size)
        s.close()
        val reopened = open(file)
        assertEquals(2, reopened.recoverInFlight())
        assertEquals(listOf(2, 2), reopened.claim("s1", 10).map { it.attempts })
    }

    @Test
    fun purgeAgesOnlyAllModeAndDropsEmptyEndedTargets() {
        val s = open()
        s.saveTarget(target("A", QueueMode.ALL, 60_000))
        s.saveTarget(target("L", QueueMode.LATEST, 60_000))
        put(s, "A", "old", ts = 0)
        put(s, "A", "new", ts = 100_000)
        put(s, "L", "l", ts = 0, mode = QueueMode.LATEST)
        assertEquals(PurgeResult(1, emptyList()), s.purge(120_000))
        s.markEnded("A")
        s.delete(s.claim("A", 10).map { it.id })
        assertEquals(PurgeResult(0, listOf("A")), s.purge(120_000))
        assertNull(s.target("A"))
        assertNotNull(s.target("L"))
    }

    @Test
    fun endLeftoversDeletesLatestAndEndsAll() {
        val s = open()
        s.saveTarget(target("A"))
        s.saveTarget(target("B", ended = true))
        s.saveTarget(target("L", QueueMode.LATEST))
        s.saveTarget(target("M", QueueMode.LATEST, ended = true))
        put(s, "A", "a")
        put(s, "L", "l", mode = QueueMode.LATEST)
        put(s, "M", "m", mode = QueueMode.LATEST)
        assertEquals(listOf("L"), s.endLeftovers())
        assertNull(s.target("L"))
        assertNull(s.latestPending("L"))
        assertTrue(s.target("A")!!.ended && s.target("B")!!.ended && s.target("M")!!.ended)
        assertEquals(2, s.status().pending)
        assertEquals(PurgeResult(0, listOf("B")), s.purge(0))
    }

    @Test
    fun purgeDropsOrphanRows() {
        val s = open()
        s.saveTarget(target("A"))
        put(s, "A", "a", ts = 100_000)
        put(s, "X", "x1", ts = 100_000)
        put(s, "X", "x2", ts = 100_000)
        s.claim("X", 1)
        assertEquals(PurgeResult(1, emptyList()), s.purge(120_000))
        assertEquals(2, s.status().pending)
        assertTrue(s.claim("X", 10).isEmpty())
    }

    @Test
    fun unknownModeTargetIsUnreadableAndPurged() {
        val file = newFile()
        val s = open(file)
        s.saveTarget(target("A"))
        s.saveTarget(target("U", ended = true))
        put(s, "A", "a", ts = 100_000)
        put(s, "U", "u1", ts = 100_000)
        put(s, "U", "u2", ts = 100_000)
        val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("UPDATE targets SET mode = 'bogus' WHERE session_id = 'U'")
        raw.close()
        assertNull(s.target("U"))
        assertNotNull(s.target("A"))
        assertEquals(PurgeResult(2, listOf("U")), s.purge(120_000))
        assertEquals(1, s.status().pending)
        assertEquals("A", s.nextSession(null))
    }

    @Test
    fun nextSessionPrefersRunningThenOldest() {
        val s = open()
        for (id in listOf("e1", "e2", "r")) {
            s.saveTarget(target(id, ended = id != "r"))
            put(s, id, "$id-1")
        }
        assertEquals("r", s.nextSession("r"))
        s.delete(s.claim("r", 10).map { it.id })
        assertEquals("e1", s.nextSession("r"))
        assertEquals("e1", s.nextSession(null))
    }

    @Test
    fun deleteSessionRemovesRowsAndTarget() {
        val s = open()
        s.saveTarget(target("s1"))
        put(s, "s1", "a")
        put(s, "s1", "b")
        assertEquals(2, s.deleteSession("s1"))
        assertNull(s.target("s1"))
        assertEquals(0, s.status().pending)
    }

    @Test
    fun statusAndClear() {
        val s = open()
        s.saveTarget(target("s1"))
        s.saveTarget(target("s2", ended = true))
        put(s, "s1", "a", ts = 5)
        put(s, "s1", "b", ts = 3)
        put(s, "s2", "c", ts = 9)
        assertEquals(QueueStatus(3, 3, 2), s.status())
        assertEquals(listOf("s2"), s.clear())
        assertEquals(QueueStatus(0, null, 0), s.status())
        assertNotNull(s.target("s1"))
    }

    @Test
    fun targetUpsertRoundTrip() {
        val s = open()
        var t = target("s1").copy(batch = BatchConfig(20, "pts"), refreshUrl = "https://x.dev/r", refreshBodyJson = """{"k":"v"}""")
        s.saveTarget(t)
        assertEquals(t, s.target("s1"))
        t = t.copy(url = "https://y.dev/l")
        s.saveTarget(t)
        assertEquals(t, s.target("s1"))
    }

    @Test
    fun openFailureThrows() {
        val file = File(context.filesDir, "${UUID.randomUUID()}/missing/queue.sqlite")
        try {
            QueueStore(context, file)
            fail("expected open to throw")
        } catch (_: QueueStoreException) {
        }
    }

    @Test
    fun failedStatementRollsBackAndStoreStaysUsable() {
        val file = newFile()
        val s = open(file)
        s.saveTarget(target("s1", QueueMode.LATEST))
        put(s, "s1", "a", mode = QueueMode.LATEST)
        val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
            try {
                put(s, "s1", "b", mode = QueueMode.LATEST)
                fail("expected insert to throw")
            } catch (_: Exception) {
            }
            assertEquals(1, s.status().pending)
            raw.execSQL("DROP TRIGGER boom")
            raw.execSQL("CREATE TRIGGER boom BEFORE UPDATE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
            assertTrue(s.claim("s1", 10).isEmpty())
            assertEquals(1, s.status().pending)
            assertEquals("a", s.latestPending("s1")?.uuid)
            assertEquals(0, s.latestPending("s1")?.attempts)
            raw.execSQL("DROP TRIGGER boom")
        } finally {
            raw.close()
        }
        put(s, "s1", "c", mode = QueueMode.LATEST)
        assertEquals(listOf(1), s.claim("s1", 10).map { it.attempts })
    }

    @Test
    fun closedStoreFailsGracefully() {
        val s = open()
        s.saveTarget(target("s1"))
        put(s, "s1", "a")
        s.close()
        assertTrue(s.claim("s1", 10).isEmpty())
        try {
            put(s, "s1", "b")
            fail("expected insert to throw")
        } catch (_: QueueStoreException) {
        }
    }

    @Test
    fun deleteAndReleaseReportFailure() {
        val file = newFile()
        val s = open(file)
        s.saveTarget(target("s1"))
        put(s, "s1", "a")
        put(s, "s1", "b")
        val ids = s.claim("s1", 10).map { it.id }
        val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL("CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
            assertFalse(s.delete(ids))
            assertTrue(s.release(listOf(ids[1])))
            raw.execSQL("DROP TRIGGER boom")
            raw.execSQL("CREATE TRIGGER boom BEFORE UPDATE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
            assertFalse(s.release(listOf(ids[0])))
            assertTrue(s.delete(ids))
            assertTrue(s.delete(emptyList()) && s.release(emptyList()))
            assertEquals(0, s.status().pending)
            raw.execSQL("DROP TRIGGER boom")
        } finally {
            raw.close()
        }
    }

    @Test
    fun statusExcludesRowsAwaitingDelete() {
        val s = open()
        s.saveTarget(target("s1"))
        s.saveTarget(target("s2"))
        put(s, "s1", "a", ts = 10)
        put(s, "s1", "b", ts = 20)
        put(s, "s2", "c", ts = 30)
        val sent = s.claim("s1", 1).map { it.id }
        val inFlight = s.claim("s2", 1).map { it.id }
        assertEquals(QueueStatus(3, 10, 2), s.status(emptySet()))
        assertEquals(QueueStatus(2, 20, 2), s.status(sent.toSet()))
        assertEquals(QueueStatus(1, 20, 1), s.status((sent + inFlight).toSet()))
        assertEquals(s.status(), s.status(setOf(999L)))
    }
}
