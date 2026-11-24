import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct TemplateTests {
    private let rec = FixRecord(uuid: "u-1", sessionId: "s1",
        fix: Fix(latitude: 1.5, longitude: 2.5, accuracy: 7, altitude: 30, timestampMs: 1_700_000_000_000),
        battery: Battery(level: 0.5, charging: true))
    private let extras = #"{"orderId":"o1","n":3,"vip":true}"#
    private func obj(_ s: String) throws -> [String: Any] { try #require(JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any]) }

    @Test func wholePlaceholderKeepsJsonType() throws {
        let t = #"{"lat":"{{latitude}}","ts":"{{timestamp}}","c":"{{batteryCharging}}","id":"{{id}}","s":"{{sessionId}}","n":"{{extras.n}}","vip":"{{extras.vip}}","o":"{{extras.orderId}}"}"#
        let o = try obj(renderTemplate(t, record: rec, extrasJson: extras))
        #expect(o["lat"] as? Double == 1.5)
        #expect(o["ts"] as? Int64 == 1_700_000_000_000)
        #expect(o["c"] as? Bool == true)
        #expect(o["id"] as? String == "u-1")
        #expect(o["s"] as? String == "s1")
        #expect(o["n"] as? Int == 3)
        #expect(o["vip"] as? Bool == true)
        #expect(o["o"] as? String == "o1")
    }

    @Test func timestampRendersAsInteger() {
        let out = renderTemplate(#"{"ts":"{{timestamp}}"}"#, record: rec, extrasJson: "{}")
        #expect(out.contains("1700000000000"))
        #expect(!out.contains("1700000000000.") && !out.lowercased().contains("e+") && !out.contains("E12"))
    }

    @Test func missingOptionalOmitsKeyAndNullsArrayElement() throws {
        let o = try obj(renderTemplate(#"{"speed":"{{speed}}","arr":["{{heading}}","{{altitude}}"]}"#, record: rec, extrasJson: "{}"))
        #expect(o["speed"] == nil)
        let arr = try #require(o["arr"] as? [Any])
        #expect(arr.count == 2 && arr[0] is NSNull && arr[1] as? Double == 30)
    }

    @Test func missingFieldInInterpolationIsEmpty() throws {
        #expect(try obj(renderTemplate(#"{"s":"v={{speed}}."}"#, record: rec, extrasJson: "{}"))["s"] as? String == "v=.")
    }

    @Test func interpolationEscapesAndDoesNotRescanValues() throws {
        let tricky = #"{"note":"say \"hi\"\\\n{{latitude}}"}"#
        let o = try obj(renderTemplate(#"{"label":"x {{extras.note}} y {{latitude}}"}"#, record: rec, extrasJson: tricky))
        #expect(o["label"] as? String == "x say \"hi\"\\\n{{latitude}} y 1.5")
    }

    @Test func isoAndNestingAndLiterals() throws {
        let o = try obj(renderTemplate(#"{"a":{"b":["{{timestampIso}}",5,true,null,"lit }} {"]}}"#, record: rec, extrasJson: "{}"))
        let b = try #require((o["a"] as? [String: Any])?["b"] as? [Any])
        #expect(b[0] as? String == "2023-11-14T22:13:20.000Z")
        #expect(b[1] as? Int == 5 && b[2] as? Bool == true && b[3] is NSNull && b[4] as? String == "lit }} {")
    }

    @Test(arguments: [
        ("[1]", "{}", "must be a JSON object"),
        ("not json", "{}", "must be a JSON object"),
        (#"{"a":"{{altitud}}"}"#, "{}", "unknown field altitud"),
        (#"{"a":"{{ latitude }}"}"#, "{}", "unknown field  latitude "),
        (#"{"a":"{{extras.missing}}"}"#, #"{"n":3}"#, "unknown extras key missing"),
        (#"{"a":[{"b":"{{nope}}"}]}"#, "{}", "unknown field nope"),
        (#"{"a":"{{latitude"}"#, "{}", #"malformed placeholder in "{{latitude""#),
        (#"{"a":"{{}}"}"#, "{}", #"malformed placeholder in "{{}}""#),
    ])
    func rejects(template: String, extras: String, reason: String) {
        #expect(validateTemplate(template, extrasJson: extras) == reason)
    }

    @Test func sizeLimitIs8192Bytes() {
        let pad = { (n: Int) in #"{"a":""# + String(repeating: "x", count: n) + #""}"# }
        #expect(validateTemplate(pad(8192 - 8), extrasJson: "{}") == nil)
        #expect(validateTemplate(pad(8193 - 8), extrasJson: "{}") == "larger than 8 KB")
    }

    @Test func validateSyncPrefixesTemplateReasonAndAllowsLatestMode() {
        #expect(validateSync(SyncConfig(templateJson: "[1]"), extrasJson: "{}") == "template: must be a JSON object")
        #expect(validateSync(SyncConfig(mode: .latest, templateJson: #"{"a":"{{id}}"}"#), extrasJson: "{}") == nil)
    }

    @Test func nonFiniteValueRendersAsNull() throws {
        let bad = FixRecord(uuid: "u-1", sessionId: "s1", fix: Fix(latitude: 1.5, longitude: 2.5, accuracy: 7, speed: .nan, timestampMs: 1), battery: nil)
        let o = try obj(renderTemplate(#"{"speed":"{{speed}}","lat":"{{latitude}}","s":"v={{speed}}"}"#, record: bad, extrasJson: "{}"))
        #expect(o["speed"] is NSNull)
        #expect(o["lat"] as? Double == 1.5)
        #expect(o["s"] as? String == "v=")
    }

    @Test func trailingInputIsRejected() {
        #expect(validateTemplate(#"{"a":1} xyz"#, extrasJson: "{}") == "must be a JSON object")
    }

    @Test(arguments: [
        #"{"a":1} // x"#, #"/*c*/{"a":1}"#, #"{'a':1}"#, #"{a:1}"#, #"{"a":01}"#, #"{"a":NaN}"#, #"{"a":Infinity}"#,
        #"{"a":"x\q"}"#, #"{"a":+1}"#, #"{"a":.5}"#, #"{"a":1.}"#, #"{"a":0x1}"#, "{\"a\":\"tab\there\"}", #"{"a":1}#"#,
    ])
    func nonJsonIsRejected(_ json: String) {
        #expect(validateTemplate(json, extrasJson: "{}") == "must be a JSON object")
    }

    @Test func strictJsonIsAccepted() {
        #expect(validateTemplate(#" {"a":[true,false,null,-0.5e+3,1E2,0,"\n\"é","é",{},[]]} "#, extrasJson: "{}") == nil)
    }

    @Test(arguments: [
        #"{"a":1,}"#, #"{"a":[1,2,],}"#, "\u{FEFF}{\"a\":1}", "\u{FEFF} {\"a\":[1,]}",
    ])
    func trailingCommasAndLeadingBomAreAccepted(_ json: String) {
        #expect(validateTemplate(json, extrasJson: "{}") == nil)
    }

    @Test(arguments: [
        #"{,}"#, #"{"a":[,]}"#, #"{"a":1,,}"#, #"{"a":[1,,]}"#, #"[1,]"#, " \u{FEFF}{\"a\":1}", "{\"a\":1}\u{FEFF}", "\u{FEFF}\u{FEFF}{}",
    ])
    func misplacedCommasAndBomAreRejected(_ json: String) {
        #expect(validateTemplate(json, extrasJson: "{}") == "must be a JSON object")
    }

    @Test func trailingCommasAreDroppedWhenRendering() throws {
        let o = try obj(renderTemplate("\u{FEFF}{\"a\":[1,],\"s\":\"{{sessionId}}\",}", record: rec, extrasJson: #"{"k":1,}"#))
        #expect(o["a"] as? [Int] == [1])
        #expect(o["s"] as? String == "s1")
        #expect(o.count == 2)
    }

    @Test func emptyTemplateIsRequired() {
        #expect(validateSync(SyncConfig(templateJson: ""), extrasJson: "{}") == "body.template is required")
    }

    @Test func timestampIsoAndExtrasNumbersInterpolate() throws {
        let o = try obj(renderTemplate(#"{"at":"at {{timestampIso}}","n":"n={{extras.n}} f={{extras.f}}"}"#, record: rec, extrasJson: #"{"n":3,"f":2.5}"#))
        #expect(o["at"] as? String == "at 2023-11-14T22:13:20.000Z")
        #expect(o["n"] as? String == "n=3 f=2.5")
    }
}
