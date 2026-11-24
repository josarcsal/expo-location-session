import Foundation
import SQLite3
import Testing
@testable import LocationSessionCore

@Suite final class QueueStoreTests {
    private var paths: [String] = []

    deinit {
        for path in paths {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
    }

    private func tempPath() -> String {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        paths.append(path)
        return path
    }

    private func open(_ path: String? = nil) throws -> (QueueStore, String) {
        let path = path ?? tempPath()
        return (try QueueStore(path: path), path)
    }

    private func raw(_ path: String, _ sql: String) {
        var db: OpaquePointer?
        #expect(sqlite3_open(path, &db) == SQLITE_OK)
        #expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK)
        sqlite3_close(db)
    }
    private func target(_ id: String, _ mode: QueueMode = .all, maxAgeMs: Int64 = 60_000, ended: Bool = false) -> QueueTarget {
        QueueTarget(sessionId: id, url: "https://x.dev/l", mode: mode, batch: nil, extrasJson: "{}", maxAgeMs: maxAgeMs, refreshUrl: nil, refreshBodyJson: nil, ended: ended)
    }
    private func put(_ s: QueueStore, _ id: String, _ uuid: String, ts: Int64 = 0, mode: QueueMode = .all, max: Int = 10_000) throws -> Int {
        try s.insert(sessionId: id, uuid: uuid, fixTs: ts, body: "{}", mode: mode, maxRecords: max)
    }

    @Test func latestInsertKeepsInFlightRow() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1", .latest))
        _ = try put(s, "s1", "a", mode: .latest)
        let a = try #require(s.claim(sessionId: "s1", limit: 1).first)
        _ = try put(s, "s1", "b", mode: .latest); _ = try put(s, "s1", "c", mode: .latest)
        #expect(s.latestPending(sessionId: "s1")?.uuid == "c")
        #expect(s.status().pending == 2)
        s.delete(ids: [a.id])
        #expect(s.claim(sessionId: "s1", limit: 10).map(\.uuid) == ["c"])
    }

    @Test func trimSkipsInFlightRows() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1"))
        for u in ["r1", "r2", "r3"] { #expect(try put(s, "s1", u, max: 3) == 0) }
        _ = s.claim(sessionId: "s1", limit: 1)
        #expect(try put(s, "s1", "r4", max: 3) == 1)
        s.release(ids: s.claim(sessionId: "s1", limit: 10).map(\.id))
        _ = s.recoverInFlight()
        #expect(s.claim(sessionId: "s1", limit: 10).map(\.uuid) == ["r1", "r3", "r4"])
    }

    @Test func recoverInFlightAfterReopen() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a"); _ = try put(s, "s1", "b")
        #expect(s.claim(sessionId: "s1", limit: 10).count == 2)
        let reopened = try QueueStore(path: path)
        #expect(reopened.recoverInFlight() == 2)
        #expect(reopened.claim(sessionId: "s1", limit: 10).map(\.attempts) == [2, 2])
    }

    @Test func purgeAgesOnlyAllModeAndDropsEmptyEndedTargets() throws {
        let (s, _) = try open()
        try s.saveTarget(target("A", .all, maxAgeMs: 60_000)); try s.saveTarget(target("L", .latest, maxAgeMs: 60_000))
        _ = try put(s, "A", "old", ts: 0); _ = try put(s, "A", "new", ts: 100_000); _ = try put(s, "L", "l", ts: 0, mode: .latest)
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 1, targetsRemoved: []))
        s.markEnded("A"); s.delete(ids: s.claim(sessionId: "A", limit: 10).map(\.id))
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 0, targetsRemoved: ["A"]))
        #expect(s.target("A") == nil && s.target("L") != nil)
    }

    @Test func endLeftoversDeletesLatestAndEndsAll() throws {
        let (s, _) = try open()
        try s.saveTarget(target("A")); try s.saveTarget(target("B", ended: true)); try s.saveTarget(target("L", .latest)); try s.saveTarget(target("M", .latest, ended: true))
        _ = try put(s, "A", "a"); _ = try put(s, "L", "l", mode: .latest); _ = try put(s, "M", "m", mode: .latest)
        #expect(s.endLeftovers() == ["L"])
        #expect(s.target("L") == nil && s.latestPending(sessionId: "L") == nil)
        #expect(s.target("A")?.ended == true && s.target("B")?.ended == true && s.target("M")?.ended == true)
        #expect(s.status().pending == 2)
        #expect(s.purge(nowMs: 0) == PurgeResult(rowsDeleted: 0, targetsRemoved: ["B"]))
    }

    @Test func garbageFileIsReportedCorrupt() throws {
        let path = tempPath()
        try Data(repeating: 0x5A, count: 4096).write(to: URL(fileURLWithPath: path))
        let error = #expect(throws: QueueStoreError.self) { try QueueStore(path: path) }
        #expect(error?.isCorrupt == true)
        try FileManager.default.removeItem(atPath: path)
        #expect(try QueueStore(path: path).status().pending == 0)
    }

    @Test func purgeDropsOrphanRows() throws {
        let (s, _) = try open(); try s.saveTarget(target("A"))
        _ = try put(s, "A", "a", ts: 100_000); _ = try put(s, "X", "x1", ts: 100_000); _ = try put(s, "X", "x2", ts: 100_000)
        _ = s.claim(sessionId: "X", limit: 1)
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 1, targetsRemoved: []))
        #expect(s.status().pending == 2 && s.claim(sessionId: "X", limit: 10).isEmpty)
    }

    @Test func unknownModeTargetIsUnreadableAndPurged() throws {
        let (s, path) = try open(); try s.saveTarget(target("A")); try s.saveTarget(target("U", ended: true))
        _ = try put(s, "A", "a", ts: 100_000); _ = try put(s, "U", "u1", ts: 100_000); _ = try put(s, "U", "u2", ts: 100_000)
        var raw: OpaquePointer?
        #expect(sqlite3_open(path, &raw) == SQLITE_OK)
        #expect(sqlite3_exec(raw, "UPDATE targets SET mode = 'bogus' WHERE session_id = 'U'", nil, nil, nil) == SQLITE_OK)
        sqlite3_close(raw)
        #expect(s.target("U") == nil && s.target("A") != nil)
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 2, targetsRemoved: ["U"]))
        #expect(s.status().pending == 1 && s.nextSession(running: nil) == "A")
    }

    @Test func nextSessionPrefersRunningThenOldest() throws {
        let (s, _) = try open()
        for id in ["e1", "e2", "r"] { try s.saveTarget(target(id, ended: id != "r")); _ = try put(s, id, id + "-1") }
        #expect(s.nextSession(running: "r") == "r")
        s.delete(ids: s.claim(sessionId: "r", limit: 10).map(\.id))
        #expect(s.nextSession(running: "r") == "e1")
        #expect(s.nextSession(running: nil) == "e1")
    }

    @Test func deleteSessionRemovesRowsAndTarget() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a"); _ = try put(s, "s1", "b")
        #expect(s.deleteSession("s1") == 2)
        #expect(s.target("s1") == nil && s.status().pending == 0)
    }

    @Test func statusAndClear() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1")); try s.saveTarget(target("s2", ended: true))
        _ = try put(s, "s1", "a", ts: 5); _ = try put(s, "s1", "b", ts: 3); _ = try put(s, "s2", "c", ts: 9)
        #expect(s.status() == QueueStatus(pending: 3, oldestAt: 3, sessions: 2))
        #expect(s.clear() == ["s2"])
        #expect(s.status() == QueueStatus(pending: 0, oldestAt: nil, sessions: 0) && s.target("s1") != nil)
    }

    @Test func targetUpsertRoundTrip() throws {
        let (s, _) = try open()
        var t = target("s1"); t.batch = BatchConfig(maxSize: 20, rootProperty: "pts"); t.refreshUrl = "https://x.dev/r"; t.refreshBodyJson = #"{"k":"v"}"#
        try s.saveTarget(t); #expect(s.target("s1") == t)
        t.url = "https://y.dev/l"; try s.saveTarget(t); #expect(s.target("s1") == t)
    }

    @Test func openFailureThrows() {
        let path = NSTemporaryDirectory() + UUID().uuidString + "/missing/queue.sqlite"
        #expect(throws: QueueStoreError.self) { try QueueStore(path: path) }
    }

    @Test func failedStatementRollsBackAndStoreStaysUsable() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1", .latest))
        _ = try put(s, "s1", "a", mode: .latest)
        var raw: OpaquePointer?
        #expect(sqlite3_open(path, &raw) == SQLITE_OK)
        defer { sqlite3_close(raw) }
        #expect(sqlite3_exec(raw, "CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END", nil, nil, nil) == SQLITE_OK)
        #expect(throws: QueueStoreError.self) { try put(s, "s1", "b", mode: .latest) }
        #expect(s.status().pending == 1)
        #expect(sqlite3_exec(raw, "DROP TRIGGER boom; CREATE TRIGGER boom BEFORE UPDATE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END", nil, nil, nil) == SQLITE_OK)
        #expect(s.claim(sessionId: "s1", limit: 10).isEmpty)
        #expect(sqlite3_exec(raw, "DROP TRIGGER boom", nil, nil, nil) == SQLITE_OK)
        _ = try put(s, "s1", "c", mode: .latest)
        #expect(s.claim(sessionId: "s1", limit: 10).map(\.attempts) == [1])
    }

    @Test func deleteAndReleaseReportFailure() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a"); _ = try put(s, "s1", "b")
        let ids = s.claim(sessionId: "s1", limit: 10).map(\.id)
        var raw: OpaquePointer?
        #expect(sqlite3_open(path, &raw) == SQLITE_OK)
        defer { sqlite3_close(raw) }
        #expect(sqlite3_exec(raw, "CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END", nil, nil, nil) == SQLITE_OK)
        #expect(!s.delete(ids: ids))
        #expect(s.release(ids: [ids[1]]))
        #expect(sqlite3_exec(raw, "DROP TRIGGER boom; CREATE TRIGGER boom BEFORE UPDATE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END", nil, nil, nil) == SQLITE_OK)
        #expect(!s.release(ids: [ids[0]]))
        #expect(s.delete(ids: ids))
        #expect(s.delete(ids: []) && s.release(ids: []))
        #expect(s.status().pending == 0)
    }

    @Test func statusExcludesRowsAwaitingDelete() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1")); try s.saveTarget(target("s2"))
        _ = try put(s, "s1", "a", ts: 10); _ = try put(s, "s1", "b", ts: 20); _ = try put(s, "s2", "c", ts: 30)
        let sent = s.claim(sessionId: "s1", limit: 1).map(\.id)
        let inFlight = s.claim(sessionId: "s2", limit: 1).map(\.id)
        #expect(s.status(excluding: []) == QueueStatus(pending: 3, oldestAt: 10, sessions: 2))
        #expect(s.status(excluding: Set(sent)) == QueueStatus(pending: 2, oldestAt: 20, sessions: 2))
        #expect(s.status(excluding: Set(sent + inFlight)) == QueueStatus(pending: 1, oldestAt: 20, sessions: 1))
        #expect(s.status(excluding: [999]) == s.status())
    }

    @Test func existingReportsOnlyStoredIds() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a"); _ = try put(s, "s1", "b")
        let ids = s.claim(sessionId: "s1", limit: 10).map(\.id)
        s.delete(ids: [ids[0]])
        #expect(s.existing(ids: ids + [999]) == [ids[1]])
        #expect(s.existing(ids: []).isEmpty)
    }

    @Test func existingKeepsIdsWhenTheReadFails() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a")
        var raw: OpaquePointer?
        #expect(sqlite3_open(path, &raw) == SQLITE_OK)
        defer { sqlite3_close(raw) }
        #expect(sqlite3_exec(raw, "ALTER TABLE fixes RENAME TO fixes_off", nil, nil, nil) == SQLITE_OK)
        #expect(s.existing(ids: [1, 999]) == [1, 999])
        #expect(sqlite3_exec(raw, "ALTER TABLE fixes_off RENAME TO fixes", nil, nil, nil) == SQLITE_OK)
        #expect(s.existing(ids: [1, 999]) == [1])
    }

    @Test func newestPendingThrowsWhenTheReadFails() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1", .latest))
        _ = try put(s, "s1", "a", mode: .latest)
        raw(path, "ALTER TABLE fixes RENAME TO fixes_off")
        #expect(throws: QueueStoreError.self) { try s.newestPending(sessionId: "s1") }
        #expect(s.latestPending(sessionId: "s1") == nil)
        raw(path, "ALTER TABLE fixes_off RENAME TO fixes")
        #expect(try s.newestPending(sessionId: "s1")?.uuid == "a")
        #expect(try s.newestPending(sessionId: "s2") == nil)
    }

    @Test func stepErrorInAReadIsReported() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a", ts: 1); _ = try put(s, "s1", "b", ts: 2)
        raw(path, """
        ALTER TABLE fixes RENAME TO fixes_real;
        CREATE VIEW fixes AS SELECT id, uuid, session_id, CASE WHEN id = 2 THEN abs(-9223372036854775808) ELSE fix_ts END AS fix_ts, body, state, attempts FROM fixes_real
        """)
        #expect(throws: QueueStoreError.self) { try s.newestPending(sessionId: "s1") }
        #expect(s.claim(sessionId: "s1", limit: 10).isEmpty)
        raw(path, "DROP VIEW fixes; ALTER TABLE fixes_real RENAME TO fixes")
        #expect(s.claim(sessionId: "s1", limit: 10).map(\.uuid) == ["a", "b"])
    }

    @Test func readTargetThrowsWhenTheReadFails() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        raw(path, "ALTER TABLE targets RENAME TO targets_off")
        #expect(throws: QueueStoreError.self) { try s.readTarget("s1") }
        #expect(s.target("s1") == nil)
        raw(path, "ALTER TABLE targets_off RENAME TO targets")
        #expect(try s.readTarget("s1") == target("s1"))
        #expect(try s.readTarget("s2") == nil)
    }

    @Test func undecodableBatchTargetIsUnreadableAndPurged() throws {
        let (s, path) = try open(); try s.saveTarget(target("A")); try s.saveTarget(target("B", ended: true))
        _ = try put(s, "A", "a", ts: 100_000); _ = try put(s, "B", "b", ts: 100_000)
        raw(path, #"UPDATE targets SET batch = '{"maxSize":5' WHERE session_id = 'B'"#)
        #expect(try s.readTarget("B") == nil)
        #expect(s.target("A") != nil)
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 1, targetsRemoved: ["B"]))
        #expect(s.status().pending == 1 && s.nextSession(running: nil) == "A")
    }

    @Test func endLeftoversKeepsTheRunningSession() throws {
        let (s, _) = try open()
        try s.saveTarget(target("L", .latest)); try s.saveTarget(target("R", .latest)); try s.saveTarget(target("A"))
        _ = try put(s, "L", "l", mode: .latest); _ = try put(s, "R", "r", mode: .latest)
        #expect(s.endLeftovers(except: "R") == ["L"])
        #expect(s.target("R")?.ended == false && s.latestPending(sessionId: "R")?.uuid == "r")
        #expect(s.target("A")?.ended == true && s.target("L") == nil)
    }

    @Test func existingFiltersBySession() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1")); try s.saveTarget(target("s2"))
        _ = try put(s, "s1", "a"); _ = try put(s, "s2", "b")
        let ids = s.claim(sessionId: "s1", limit: 1).map(\.id) + s.claim(sessionId: "s2", limit: 1).map(\.id)
        #expect(s.existing(ids: ids, sessionId: "s2") == [ids[1]])
        #expect(s.existing(ids: ids, sessionId: "s3").isEmpty)
    }

    @Test func statusExcludesPendingRows() throws {
        let (s, _) = try open(); try s.saveTarget(target("s1")); try s.saveTarget(target("s2"))
        _ = try put(s, "s1", "a", ts: 10); _ = try put(s, "s2", "b", ts: 20)
        let hidden = try #require(s.latestPending(sessionId: "s1")).id
        #expect(s.status(excluding: [hidden]) == QueueStatus(pending: 1, oldestAt: 20, sessions: 1))
    }

    @Test func deleteSessionReportsFailure() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a")
        raw(path, "CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
        #expect(s.deleteSession("s1") == -1)
        #expect(s.target("s1") != nil && s.status().pending == 1)
    }

    @Test func probeWriteLeavesNoRowAndReportsFailure() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        #expect(s.probeWrite())
        #expect(s.status().pending == 0)
        raw(path, "CREATE TRIGGER boom BEFORE INSERT ON fixes BEGIN SELECT RAISE(ABORT, 'full'); END")
        #expect(!s.probeWrite())
        raw(path, "DROP TRIGGER boom")
        _ = try put(s, "s1", "a")
        #expect(s.status().pending == 1)
    }

    @Test func trimFailureUndoesTheInsert() throws {
        let (s, path) = try open(); try s.saveTarget(target("s1"))
        _ = try put(s, "s1", "a", max: 1)
        raw(path, "CREATE TRIGGER boom BEFORE DELETE ON fixes BEGIN SELECT RAISE(ABORT, 'boom'); END")
        #expect(throws: QueueStoreError.self) { try put(s, "s1", "b", max: 1) }
        raw(path, "DROP TRIGGER boom")
        #expect(s.claim(sessionId: "s1", limit: 10).map(\.uuid) == ["a"])
    }
}
