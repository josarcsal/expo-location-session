import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct SyncConfigTests {
    @Test func defaultIsV01() {
        #expect(SyncConfig.default == SyncConfig(mode: .latest, maxRecords: 10_000, maxAgeMs: 86_400_000))
        #expect(validateSync(.default, extrasJson: "{}") == nil)
    }

    @Test(arguments: [
        (SyncConfig(batch: BatchConfig(maxSize: 10)), "batch requires queue.mode 'all'"),
        (SyncConfig(mode: .all, batch: BatchConfig(maxSize: 0)), "batch.maxSize must be between 1 and 500"),
        (SyncConfig(mode: .all, batch: BatchConfig(maxSize: 501)), "batch.maxSize must be between 1 and 500"),
        (SyncConfig(mode: .all, batch: BatchConfig(maxSize: 5, rootProperty: "")), "batch.rootProperty must not be empty"),
        (SyncConfig(maxRecords: 0), "queue.maxRecords must be between 1 and 100000"),
        (SyncConfig(maxRecords: 100_001), "queue.maxRecords must be between 1 and 100000"),
        (SyncConfig(maxAgeMs: 59_999), "queue.maxAgeMs must be between 60000 and 2592000000"),
        (SyncConfig(maxAgeMs: 2_592_000_001), "queue.maxAgeMs must be between 60000 and 2592000000"),
        (SyncConfig(refresh: RefreshConfig(url: "http://x.dev/r")), "auth.refresh.url must be an https URL"),
        (SyncConfig(refresh: RefreshConfig(url: "nope")), "auth.refresh.url must be an https URL"),
        (SyncConfig(refresh: RefreshConfig(url: "https://x.dev/r", bodyJson: "[1]")), "auth.refresh.body must be a JSON object"),
    ])
    func rejects(sync: SyncConfig, reason: String) {
        #expect(validateSync(sync, extrasJson: "{}") == reason)
    }

    @Test(arguments: [
        SyncConfig(mode: .all, batch: BatchConfig(maxSize: 1)), SyncConfig(mode: .all, batch: BatchConfig(maxSize: 500)),
        SyncConfig(maxRecords: 1), SyncConfig(maxRecords: 100_000), SyncConfig(maxAgeMs: 60_000), SyncConfig(maxAgeMs: 2_592_000_000),
        SyncConfig(refresh: RefreshConfig(url: "https://x.dev/r", headers: ["X-R": "1"], bodyJson: #"{"k":"v"}"#)),
    ])
    func acceptsBoundaries(sync: SyncConfig) {
        #expect(validateSync(sync, extrasJson: "{}") == nil)
    }

    @Test func invalidSyncMakesValidateReturnInvalidConfig() {
        var c = SessionConfig(id: "s1", expiresAtMs: 10_000, destination: nil, accuracy: "high", distanceFilterMeters: 10, uploadUrl: "https://x.dev/l", uploadHeaders: [:], uploadExtrasJson: "{}", uploadMinIntervalMs: 15_000)
        #expect(validate(c, nowMs: 0, running: false) == nil)
        c.sync = SyncConfig(maxRecords: 0)
        #expect(validate(c, nowMs: 0, running: false) == "INVALID_CONFIG")
    }

    @Test func decodesV01PersistedConfigWithDefaultSync() throws {
        let v01 = #"{"id":"s1","expiresAtMs":10000,"accuracy":"high","distanceFilterMeters":10,"uploadUrl":"https://x.dev/l","uploadHeaders":{},"uploadExtrasJson":"{}","uploadMinIntervalMs":15000}"#
        let c = try JSONDecoder().decode(SessionConfig.self, from: Data(v01.utf8))
        #expect(c.id == "s1")
        #expect(c.sync == .default)
    }

    @Test func roundTripsSync() throws {
        var c = SessionConfig(id: "s1", expiresAtMs: 1, destination: nil, accuracy: "low", distanceFilterMeters: 0, uploadUrl: "https://x.dev/l", uploadHeaders: [:], uploadExtrasJson: "{}", uploadMinIntervalMs: 0)
        c.sync = SyncConfig(mode: .all, maxRecords: 5, maxAgeMs: 60_000, batch: BatchConfig(maxSize: 20, rootProperty: "pts"), templateJson: #"{"a":"{{id}}"}"#, refresh: RefreshConfig(url: "https://x.dev/r", headers: ["X": "y"], bodyJson: nil))
        #expect(try JSONDecoder().decode(SessionConfig.self, from: JSONEncoder().encode(c)) == c)
    }
}
