import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct BodyAssemblyTests {
    private let full = Fix(latitude: 1.5, longitude: 2.5, accuracy: 7.5, altitude: 30.5, timestampMs: 1_700_000_000_000)
    private func rec(_ uuid: String = "u-1", battery: Battery? = Battery(level: 0.5, charging: true)) -> FixRecord {
        FixRecord(uuid: uuid, sessionId: "s1", fix: full, battery: battery)
    }
    private func obj(_ d: Data) throws -> [String: Any] { try #require(JSONSerialization.jsonObject(with: d) as? [String: Any]) }

    @Test(arguments: [#"{"orderId":"o1"}"#, "{}", "not json"])
    func defaultConfigRendersByteIdenticalV01Body(extras: String) throws {
        for battery in [Battery(level: 0.5, charging: true), nil] {
            let element = buildElement(record: rec(battery: battery), sync: .default, extrasJson: extras)
            let v01 = buildBody(sessionId: "s1", fix: full, battery: battery.map { ($0.level, $0.charging) }, extrasJson: extras)
            let expected = try obj(v01) as NSDictionary
            #expect(try obj(Data(element.utf8)) as NSDictionary == expected)
            #expect(try obj(buildRequestBody(sessionId: "s1", elements: [element], sync: .default, extrasJson: extras)) as NSDictionary == expected)
        }
    }

    @Test func allModeSingleFixIsV01BodyPlusId() throws {
        let sync = SyncConfig(mode: .all)
        let body = try obj(buildRequestBody(sessionId: "s1", elements: [buildElement(record: rec(), sync: sync, extrasJson: "{}")], sync: sync, extrasJson: "{}"))
        #expect(Set(body.keys) == ["id", "sessionId", "location", "battery", "extras"])
        #expect(body["id"] as? String == "u-1")
    }

    @Test func batchAlwaysUsesEnvelopeEvenForOneFix() throws {
        let sync = SyncConfig(mode: .all, batch: BatchConfig(maxSize: 50))
        let body = try obj(buildRequestBody(sessionId: "s1", elements: [buildElement(record: rec(), sync: sync, extrasJson: "{}")], sync: sync, extrasJson: #"{"n":3}"#))
        #expect(Set(body.keys) == ["sessionId", "locations", "extras"])
        let list = try #require(body["locations"] as? [[String: Any]])
        #expect(list.count == 1 && Set(list[0].keys) == ["id", "location", "battery"])
        #expect((body["extras"] as? [String: Any])?["n"] as? Int == 3)
    }

    @Test func batchKeepsOrderAndCustomRoot() throws {
        let sync = SyncConfig(mode: .all, batch: BatchConfig(maxSize: 50, rootProperty: "points"))
        let els = ["u-1", "u-2", "u-3"].map { buildElement(record: rec($0), sync: sync, extrasJson: "{}") }
        let list = try #require(try obj(buildRequestBody(sessionId: "s1", elements: els, sync: sync, extrasJson: "not json"))["points"] as? [[String: Any]])
        #expect(list.map { $0["id"] as? String } == ["u-1", "u-2", "u-3"])
    }

    @Test func templateReplacesElementInBothModes() throws {
        let t = #"{"lat":"{{latitude}}"}"#
        let latest = SyncConfig(templateJson: t)
        let one = try obj(buildRequestBody(sessionId: "s1", elements: [buildElement(record: rec(), sync: latest, extrasJson: "{}")], sync: latest, extrasJson: "{}"))
        #expect(one.count == 1 && one["lat"] as? Double == 1.5)
        let batched = SyncConfig(mode: .all, batch: BatchConfig(maxSize: 5), templateJson: t)
        let env = try obj(buildRequestBody(sessionId: "s1", elements: [buildElement(record: rec(), sync: batched, extrasJson: "{}")], sync: batched, extrasJson: "{}"))
        #expect(((env["locations"] as? [[String: Any]])?[0])?["lat"] as? Double == 1.5)
        #expect((env["extras"] as? [String: Any])?.isEmpty == true)
    }
}
