import Testing
@testable import LocationSessionCore

@Suite struct FallbackSlotTests {
    private func fix(_ ts: Int64) -> Fix { Fix(latitude: 1, longitude: 2, accuracy: 3, timestampMs: ts) }

    @Test func newestReplacesOlder() {
        var s = FallbackSlot()
        let first = s.put(sessionId: "s1", uuid: "a", fix: fix(1))
        let second = s.put(sessionId: "s1", uuid: "b", fix: fix(2))
        #expect(second == first + 1)
        #expect(s.next(sessionId: "s1") == FallbackFix(sessionId: "s1", uuid: "b", fix: fix(2), generation: second))
        #expect(s.next(sessionId: "s2") == nil)
    }

    @Test func markSentClearsOnlyTheSentGeneration() {
        var s = FallbackSlot()
        let sent = s.put(sessionId: "s1", uuid: "a", fix: fix(1))
        s.put(sessionId: "s1", uuid: "b", fix: fix(2))
        s.markSent(generation: sent)
        #expect(s.next(sessionId: "s1")?.uuid == "b")
        s.markSent(generation: sent + 1)
        #expect(s.next(sessionId: "s1") == nil)
    }

    @Test func queuedRowsUpToTheNewestSlotFixAreSuperseded() {
        var s = FallbackSlot()
        #expect(!s.supersedes(sessionId: "s1", fixTs: 5))
        let g = s.put(sessionId: "s1", uuid: "a", fix: fix(10))
        s.markSent(generation: g)
        #expect(s.supersedes(sessionId: "s1", fixTs: 9))
        #expect(s.supersedes(sessionId: "s1", fixTs: 10))
        #expect(!s.supersedes(sessionId: "s1", fixTs: 11))
        #expect(!s.supersedes(sessionId: "s2", fixTs: 9))
        s.put(sessionId: "s1", uuid: "b", fix: fix(7))
        #expect(s.supersedes(sessionId: "s1", fixTs: 10))
    }

    @Test func storedFixClearsSlotAndFloor() {
        var s = FallbackSlot()
        s.put(sessionId: "s1", uuid: "a", fix: fix(10))
        s.stored()
        #expect(s.next(sessionId: "s1") == nil)
        #expect(!s.supersedes(sessionId: "s1", fixTs: 9))
    }

    @Test func keepDropsOtherSessions() {
        var s = FallbackSlot()
        s.put(sessionId: "s1", uuid: "a", fix: fix(10))
        s.keep(sessionId: "s1")
        #expect(s.next(sessionId: "s1")?.uuid == "a")
        s.keep(sessionId: "s2")
        #expect(s.next(sessionId: "s1") == nil)
        #expect(!s.supersedes(sessionId: "s1", fixTs: 9))
        s.put(sessionId: "s1", uuid: "b", fix: fix(10))
        s.keep(sessionId: nil)
        #expect(s.pending == nil)
    }
}
