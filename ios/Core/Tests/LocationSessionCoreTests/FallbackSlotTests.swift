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

    @Test func pickPrefersMemoryThenQueueUnlessSuperseded() {
        var s = FallbackSlot()
        #expect(s.pick(sessionId: "s1", queuedFixTs: nil) == .none)
        #expect(s.pick(sessionId: "s1", queuedFixTs: 5) == .queue)
        let g = s.put(sessionId: "s1", uuid: "a", fix: fix(10))
        #expect(s.pick(sessionId: "s1", queuedFixTs: 5) == .memory)
        #expect(s.pick(sessionId: "s2", queuedFixTs: 5) == .queue)
        s.markSent(generation: g)
        #expect(s.pick(sessionId: "s1", queuedFixTs: 5) == .none)
        #expect(s.pick(sessionId: "s1", queuedFixTs: 11) == .queue)
        s.stored()
        #expect(s.pick(sessionId: "s1", queuedFixTs: 5) == .queue)
    }

    @Test func resumedPendingFixIsQueuedOnlyWhenNewerThanTheQueuedRow() {
        #expect(FallbackSlot.resumes(pendingFixTs: 10, over: nil))
        #expect(FallbackSlot.resumes(pendingFixTs: 10, over: 5))
        #expect(!FallbackSlot.resumes(pendingFixTs: 10, over: 10))
        #expect(!FallbackSlot.resumes(pendingFixTs: 10, over: 12))
    }

    @Test func deliveredFixStaysPendingWhileASupersededRowRemains() {
        var s = FallbackSlot()
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: 5, queuedFix: fix(5), queueReadable: true) == fix(5))
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: nil, queuedFix: nil, queueReadable: true) == nil)
        let g = s.put(sessionId: "s1", uuid: "a", fix: fix(10))
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: 5, queuedFix: fix(5), queueReadable: true) == fix(10))
        s.markSent(generation: g)
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: 5, queuedFix: fix(5), queueReadable: true) == fix(10))
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: nil, queuedFix: nil, queueReadable: false) == fix(10))
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: nil, queuedFix: nil, queueReadable: true) == nil)
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: 11, queuedFix: fix(11), queueReadable: true) == fix(11))
        #expect(s.pendingFix(sessionId: "s2", queuedFixTs: 5, queuedFix: fix(5), queueReadable: true) == fix(5))
        s.stored()
        #expect(s.pendingFix(sessionId: "s1", queuedFixTs: 5, queuedFix: fix(5), queueReadable: true) == fix(5))
    }
}
