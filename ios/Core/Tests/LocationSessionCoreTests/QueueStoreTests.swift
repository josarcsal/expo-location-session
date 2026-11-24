import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct QueueStoreTests {
    private func open(_ path: String = NSTemporaryDirectory() + UUID().uuidString + ".sqlite") throws -> (QueueStore, String) { (try QueueStore(path: path), path) }
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

    @Test func purgeDropsOrphanRows() throws {
        let (s, _) = try open(); try s.saveTarget(target("A"))
        _ = try put(s, "A", "a", ts: 100_000); _ = try put(s, "X", "x1", ts: 100_000); _ = try put(s, "X", "x2", ts: 100_000)
        _ = s.claim(sessionId: "X", limit: 1)
        #expect(s.purge(nowMs: 120_000) == PurgeResult(rowsDeleted: 1, targetsRemoved: []))
        #expect(s.status().pending == 2 && s.claim(sessionId: "X", limit: 10).isEmpty)
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
}
