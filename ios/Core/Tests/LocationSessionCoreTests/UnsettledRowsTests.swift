import Testing
@testable import LocationSessionCore

@Suite struct UnsettledRowsTests {
    @Test func failedDeleteIsRememberedAndRetried() {
        var u = UnsettledRows()
        #expect(!u.delete([1, 2]) { _ in false })
        #expect(u.deletes == [1, 2])
        var calls: [[Int64]] = []
        #expect(u.retry(delete: { calls.append($0); return false }, release: { _ in true }) == 0)
        #expect(u.deletes == [1, 2])
        #expect(u.retry(delete: { calls.append($0); return true }, release: { _ in true }) == 2)
        #expect(calls == [[1, 2], [1, 2]])
        #expect(u.isEmpty)
    }

    @Test func successfulDeleteSettlesEarlierFailures() {
        var u = UnsettledRows()
        u.delete([1, 2]) { _ in false }
        u.release([3]) { _ in false }
        #expect(u.delete([2, 3]) { _ in true })
        #expect(u.deletes == [1])
        #expect(u.releases.isEmpty)
    }

    @Test func rowAwaitingDeleteIsNeverReleased() {
        var u = UnsettledRows()
        u.delete([1]) { _ in false }
        var released: [[Int64]] = []
        #expect(u.release([1, 2]) { released.append($0); return false } == false)
        #expect(released == [[2]])
        #expect(u.deletes == [1])
        #expect(u.releases == [2])
        #expect(u.release([1]) { _ in Issue.record("released a sent row"); return false })
    }

    @Test func retrySettlesDeletesAndReleasesIndependently() {
        var u = UnsettledRows()
        u.delete([5]) { _ in false }
        u.release([7]) { _ in false }
        #expect(u.retry(delete: { _ in false }, release: { $0 == [7] }) == 1)
        #expect(u.deletes == [5])
        #expect(u.releases.isEmpty)
    }

    @Test func emptyOperationsDoNotCallTheStore() {
        var u = UnsettledRows()
        #expect(u.delete([]) { _ in Issue.record("called"); return false })
        #expect(u.retry(delete: { _ in Issue.record("called"); return false }, release: { _ in Issue.record("called"); return false }) == 0)
    }

    @Test func retainForgetsRowsThatNoLongerExist() {
        var u = UnsettledRows()
        u.delete([1, 2]) { _ in false }
        u.release([3, 4]) { _ in false }
        #expect(u.ids == [1, 2, 3, 4])
        u.retain([2, 4])
        #expect(u.deletes == [2])
        #expect(u.releases == [4])
    }
}
