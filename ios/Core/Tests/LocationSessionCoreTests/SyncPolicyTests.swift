import Testing
@testable import LocationSessionCore

@Suite struct SyncPolicyTests {
    private static let statuses: [Int?] = [nil, 100, 200, 204, 299, 301, 400, 401, 403, 404, 409, 410, 413, 422, 429, 500, 503]

    @Test(arguments: statuses)
    func matchesV01ClassifyWhenRunningWithoutRefresh(status: Int?) {
        let expected: SyncAction
        switch classify(status: status) {
        case .success: expected = .deleteSent
        case .stop(let r): expected = .stop(r)
        case .drop: expected = .dropSent
        case .retry: expected = .retry
        }
        #expect(syncAction(status: status, ended: false, refreshConfigured: false, alreadyRefreshed: false) == expected)
    }

    @Test func refreshRows() {
        #expect(syncAction(status: 401, ended: false, refreshConfigured: true, alreadyRefreshed: false) == .refreshAndRetry)
        #expect(syncAction(status: 401, ended: true, refreshConfigured: true, alreadyRefreshed: false) == .refreshAndRetry)
        #expect(syncAction(status: 401, ended: false, refreshConfigured: true, alreadyRefreshed: true) == .stop(.unauthorized))
        #expect(syncAction(status: 401, ended: true, refreshConfigured: true, alreadyRefreshed: true) == .retry)
        #expect(syncAction(status: 403, ended: false, refreshConfigured: true, alreadyRefreshed: false) == .stop(.unauthorized))
    }

    @Test func endedColumn() {
        let a = { (s: Int?) in syncAction(status: s, ended: true, refreshConfigured: false, alreadyRefreshed: false) }
        #expect(a(204) == .deleteSent)
        #expect(a(401) == .dropSent)
        #expect(a(403) == .dropSent)
        #expect(a(410) == .dropSession)
        #expect(a(413) == .dropSent)
        #expect(a(422) == .dropSent)
        #expect(a(503) == .retry)
        #expect(a(nil) == .retry)
    }

    @Test(arguments: [300, 301, 302, 303, 304, 307, 308])
    func redirectsRetryInBothColumns(status: Int) {
        #expect(syncAction(status: status, ended: false, refreshConfigured: true, alreadyRefreshed: false) == .retry)
        #expect(syncAction(status: status, ended: true, refreshConfigured: false, alreadyRefreshed: false) == .retry)
    }

    @Test func retention() {
        #expect(!isExpired(fixTs: 0, nowMs: 86_400_000, maxAgeMs: 86_400_000))
        #expect(isExpired(fixTs: 0, nowMs: 86_400_001, maxAgeMs: 86_400_000))
        #expect(trimCount(rows: 10_001, maxRecords: 10_000) == 1)
        #expect(trimCount(rows: 10_000, maxRecords: 10_000) == 0)
        #expect(trimCount(rows: 0, maxRecords: 1) == 0)
    }
}
