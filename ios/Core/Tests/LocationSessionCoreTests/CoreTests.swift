import Foundation
import Testing
@testable import LocationSessionCore

private func cfg(
    id: String = "s1",
    expiresAtMs: Int64 = 10_000,
    dest: Destination? = nil,
    accuracy: String = "high",
    distanceFilter: Double = 10,
    url: String = "https://x.dev/l",
    minInterval: Int64 = 15_000,
    extras: String = "{}"
) -> SessionConfig {
    SessionConfig(id: id, expiresAtMs: expiresAtMs, destination: dest, accuracy: accuracy, distanceFilterMeters: distanceFilter, uploadUrl: url, uploadHeaders: [:], uploadExtrasJson: extras, uploadMinIntervalMs: minInterval)
}

private func fix(lat: Double = 0, lng: Double = 0, acc: Double = 5, ts: Int64 = 0) -> Fix {
    Fix(latitude: lat, longitude: lng, accuracy: acc, timestampMs: ts)
}

private let here = Destination(latitude: 40.4168, longitude: -3.7038, radiusMeters: 50)

@Suite struct ValidationTests {
    @Test func rejectsAlreadyRunning() {
        #expect(validate(cfg(), nowMs: 0, running: true) == "ALREADY_RUNNING")
    }

    @Test func rejectsExpired() {
        #expect(validate(cfg(expiresAtMs: 999), nowMs: 1000, running: false) == "EXPIRED")
        #expect(validate(cfg(expiresAtMs: 1000), nowMs: 1000, running: false) == "EXPIRED")
    }

    @Test func acceptsValid() {
        #expect(validate(cfg(), nowMs: 0, running: false) == nil)
        #expect(validate(cfg(dest: here), nowMs: 0, running: false) == nil)
    }

    @Test func orderIsRunningThenInvalidThenExpired() {
        #expect(validate(cfg(id: ""), nowMs: 0, running: true) == "ALREADY_RUNNING")
        #expect(validate(cfg(id: "", expiresAtMs: 1), nowMs: 1000, running: false) == "INVALID_CONFIG")
    }

    @Test(arguments: [
        cfg(url: "http://x.dev/l"),
        cfg(id: ""),
        cfg(url: "not a url"),
        cfg(url: ""),
        cfg(dest: Destination(latitude: 1, longitude: 1, radiusMeters: 0)),
        cfg(dest: Destination(latitude: 1, longitude: 1, radiusMeters: -5)),
        cfg(distanceFilter: -1),
        cfg(minInterval: -1),
        cfg(accuracy: "best"),
    ])
    func rejectsInvalidConfig(config: SessionConfig) {
        #expect(validate(config, nowMs: 0, running: false) == "INVALID_CONFIG")
    }

    @Test(arguments: ["high", "balanced", "low"])
    func acceptsKnownAccuracy(accuracy: String) {
        #expect(validate(cfg(accuracy: accuracy), nowMs: 0, running: false) == nil)
    }
}

@Suite struct StopPolicyTests {
    @Test func acceptFixDropsCachedFixFromBeforeStart() {
        #expect(!acceptFix(fix(ts: 999), startedAtMs: 1000))
        #expect(acceptFix(fix(ts: 1000), startedAtMs: 1000))
    }

    @Test func duplicateFixIsSamePositionWithinFiveSeconds() {
        #expect(!isDuplicateFix(fix(lat: 1, lng: 2, ts: 1_000), of: nil))
        #expect(isDuplicateFix(fix(lat: 1, lng: 2, ts: 1_024), of: fix(lat: 1, lng: 2, ts: 1_000)))
        #expect(isDuplicateFix(fix(lat: 1, lng: 2, ts: 6_000), of: fix(lat: 1, lng: 2, ts: 1_000)))
        #expect(!isDuplicateFix(fix(lat: 1, lng: 2, ts: 6_001), of: fix(lat: 1, lng: 2, ts: 1_000)))
        #expect(!isDuplicateFix(fix(lat: 1, lng: 2.00001, ts: 1_024), of: fix(lat: 1, lng: 2, ts: 1_000)))
    }

    @Test func arrivalNeedsGoodAccuracy() {
        #expect(isArrival(here, fix(lat: 40.4168, lng: -3.7038, acc: 20)))
        #expect(!isArrival(here, fix(lat: 40.4168, lng: -3.7038, acc: 500)))
        #expect(!isArrival(here, fix(lat: 40.4200, lng: -3.7038, acc: 10)))
    }

    @Test func smallRadiusAllowsUpTo100mAccuracy() {
        let small = Destination(latitude: 40.4168, longitude: -3.7038, radiusMeters: 10)
        #expect(isArrival(small, fix(lat: 40.4168, lng: -3.7038, acc: 100)))
        #expect(!isArrival(small, fix(lat: 40.4168, lng: -3.7038, acc: 101)))
    }

    @Test func expiryWinsOverArrival() {
        let atHere = fix(lat: 40.4168, lng: -3.7038, acc: 5)
        #expect(stopDecision(cfg(expiresAtMs: 100, dest: here), fix: atHere, nowMs: 100) == .expired)
        #expect(stopDecision(cfg(expiresAtMs: 100, dest: here), fix: atHere, nowMs: 99) == .arrived)
        #expect(stopDecision(cfg(expiresAtMs: 100, dest: here), fix: nil, nowMs: 99) == nil)
        #expect(stopDecision(cfg(expiresAtMs: 100), fix: atHere, nowMs: 99) == nil)
    }
}

@Suite struct UploadPolicyTests {
    @Test func classifyStatus() {
        #expect(classify(status: 204) == .success)
        #expect(classify(status: 401) == .stop(.unauthorized))
        #expect(classify(status: 403) == .stop(.unauthorized))
        #expect(classify(status: 410) == .stop(.rejected))
        #expect(classify(status: 422) == .drop)
        #expect(classify(status: 503) == .retry)
        #expect(classify(status: nil) == .retry)
        #expect(classify(status: 301) == .retry)
        #expect(classify(status: 100) == .retry)
    }

    @Test func backoffCapsAtSixtySeconds() {
        #expect((0...5).map(backoffMs) == [5000, 10000, 20000, 40000, 60000, 60000])
    }

    @Test func throttle() {
        #expect(shouldUploadNow(lastSuccessMs: nil, nowMs: 0, minIntervalMs: 15000, bypass: false))
        #expect(!shouldUploadNow(lastSuccessMs: 0, nowMs: 14999, minIntervalMs: 15000, bypass: false))
        #expect(shouldUploadNow(lastSuccessMs: 0, nowMs: 15000, minIntervalMs: 15000, bypass: false))
        #expect(shouldUploadNow(lastSuccessMs: 0, nowMs: 14999, minIntervalMs: 15000, bypass: true))
    }
}

@Suite struct PendingSlotTests {
    @Test func olderSuccessDoesNotClearNewerPending() {
        var s = PendingSlot()
        let g1 = s.put(fix(ts: 1))
        _ = s.put(fix(ts: 2))
        s.markSent(generation: g1)
        #expect(s.fix?.timestampMs == 2)
    }

    @Test func matchingSuccessClears() {
        var s = PendingSlot()
        let g = s.put(fix(ts: 1))
        s.markSent(generation: g)
        #expect(s.fix == nil)
    }
}

@Suite struct BodyTests {
    private func decode(_ data: Data) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test func fullShape() throws {
        let f = Fix(latitude: 1.5, longitude: 2.5, accuracy: 7, altitude: 30, speed: 2, heading: 90, timestampMs: 1_700_000_000_000)
        let body = try decode(buildBody(sessionId: "s1", fix: f, battery: (level: 0.5, charging: true), extrasJson: #"{"orderId":"o1","n":3}"#))
        #expect(body["sessionId"] as? String == "s1")
        let loc = try #require(body["location"] as? [String: Any])
        #expect(loc["latitude"] as? Double == 1.5)
        #expect(loc["longitude"] as? Double == 2.5)
        #expect(loc["accuracy"] as? Double == 7)
        #expect(loc["altitude"] as? Double == 30)
        #expect(loc["speed"] as? Double == 2)
        #expect(loc["heading"] as? Double == 90)
        #expect(loc["timestamp"] as? Int64 == 1_700_000_000_000)
        let battery = try #require(body["battery"] as? [String: Any])
        #expect(battery["level"] as? Double == 0.5)
        #expect(battery["charging"] as? Bool == true)
        let extras = try #require(body["extras"] as? [String: Any])
        #expect(extras["orderId"] as? String == "o1")
        #expect(extras["n"] as? Int == 3)
    }

    @Test func omitsNilOptionals() throws {
        let body = try decode(buildBody(sessionId: "s1", fix: fix(lat: 1, lng: 2, acc: 3, ts: 4), battery: nil, extrasJson: "{}"))
        let loc = try #require(body["location"] as? [String: Any])
        #expect(Set(loc.keys) == ["latitude", "longitude", "accuracy", "timestamp"])
        #expect(body["battery"] == nil)
        #expect((body["extras"] as? [String: Any])?.isEmpty == true)
    }

    @Test(arguments: ["not json", "", "[1,2]", "42"])
    func malformedExtrasBecomeEmptyObject(extras: String) throws {
        let body = try decode(buildBody(sessionId: "s1", fix: fix(), battery: nil, extrasJson: extras))
        #expect((body["extras"] as? [String: Any])?.isEmpty == true)
    }
}
