import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct SanitizeTests {
    private let full = Fix(latitude: 40.4168, longitude: -3.7038, accuracy: 12.5, altitude: 650.2, speed: 1.4, heading: 270, timestampMs: 1_700_000_000_000)

    @Test func finiteFixIsUnchanged() throws {
        #expect(sanitize(full) == full)
        let sanitized = buildBody(sessionId: "s1", fix: sanitize(full)!, battery: (0.5, true), extrasJson: #"{"k":"v"}"#)
        let original = buildBody(sessionId: "s1", fix: full, battery: (0.5, true), extrasJson: #"{"k":"v"}"#)
        #expect(try JSONSerialization.jsonObject(with: sanitized) as? NSDictionary == JSONSerialization.jsonObject(with: original) as? NSDictionary)
    }

    @Test(arguments: [
        Fix(latitude: .nan, longitude: 0, accuracy: 5, timestampMs: 1),
        Fix(latitude: 0, longitude: .infinity, accuracy: 5, timestampMs: 1),
        Fix(latitude: -.infinity, longitude: 0, accuracy: 5, timestampMs: 1),
        Fix(latitude: 0, longitude: 0, accuracy: .nan, timestampMs: 1),
        Fix(latitude: 0, longitude: 0, accuracy: -1, timestampMs: 1),
        Fix(latitude: 90.5, longitude: 0, accuracy: 5, timestampMs: 1),
        Fix(latitude: 0, longitude: -180.5, accuracy: 5, timestampMs: 1),
    ])
    func dropsUnusableFix(fix: Fix) {
        #expect(sanitize(fix) == nil)
    }

    @Test func nonFiniteOptionalsBecomeAbsent() {
        let fix = Fix(latitude: 1, longitude: 2, accuracy: 3, altitude: .nan, speed: .infinity, heading: -.infinity, timestampMs: 4)
        #expect(sanitize(fix) == Fix(latitude: 1, longitude: 2, accuracy: 3, timestampMs: 4))
        let body = buildBody(sessionId: "s1", fix: sanitize(fix)!, battery: nil, extrasJson: "{}")
        let location = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])?["location"] as? [String: Any]
        #expect(location?.keys.sorted() == ["accuracy", "latitude", "longitude", "timestamp"])
    }

    @Test func negativeSpeedAndHeadingBecomeAbsent() {
        let fix = Fix(latitude: 1, longitude: 2, accuracy: 3, altitude: -10, speed: -1, heading: -1, timestampMs: 4)
        #expect(sanitize(fix) == Fix(latitude: 1, longitude: 2, accuracy: 3, altitude: -10, timestampMs: 4))
    }
}
