import Foundation
import Testing
@testable import LocationSessionCore

private func opts(enabled: Bool = true, url: String? = "https://x.dev/logs", flush: Int64 = 60_000, maxBytes: Int64 = 1_000_000) -> DebugOptions {
    DebugOptions(enabled: enabled, url: url, headers: [:], flushIntervalMs: flush, maxFileBytes: maxBytes)
}

@Suite struct DebugValidationTests {
    @Test func rejectsHttpUrl() {
        let reason = validateDebugOptions(opts(url: "http://x.dev/logs"))
        #expect(reason?.contains("https") == true)
    }

    @Test func rejectsShortFlushInterval() {
        #expect(validateDebugOptions(opts(flush: 9999)) != nil)
    }

    @Test(arguments: [Int64(99_999), Int64(10_000_001)])
    func rejectsMaxFileBytesOutOfRange(_ bytes: Int64) {
        #expect(validateDebugOptions(opts(maxBytes: bytes)) != nil)
    }

    @Test func acceptsDisabledWithoutUrl() {
        #expect(validateDebugOptions(opts(enabled: false, url: nil)) == nil)
    }
}

@Suite struct DebugEncodingTests {
    @Test func dropsNaNInData() throws {
        let line = LogLine(seq: 1, t: 2, pid: 3, level: "info", tag: "debug", msg: "m", sessionId: nil, data: ["x": Double.nan, "y": 1])
        let json = encodeLogLine(line)
        let obj = try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let data = try #require(obj["data"] as? [String: Any])
        #expect(data["x"] == nil)
        #expect(data["y"] as? Int == 1)
        #expect(!json.contains("\n"))
    }

    @Test func seqLeadsTheLine() throws {
        let json = encodeLogLine(LogLine(seq: 4321, t: 2, pid: 3, level: "info", tag: "debug", msg: "m", data: ["seq": 9]))
        #expect(json.hasPrefix("{\"seq\":4321,"))
        #expect(lineSeq(Data(json.utf8)) == 4321)
        let obj = try #require(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        #expect(obj["seq"] as? Int == 4321)
        #expect(obj["msg"] as? String == "m")
    }

    @Test func lineSeqRejectsOtherShapes() {
        #expect(lineSeq(Data("{\"t\":1,\"seq\":2}".utf8)) == nil)
        #expect(lineSeq(Data("{\"seq\":12".utf8)) == nil)
        #expect(lineSeq(Data("{\"seq\":,".utf8)) == nil)
    }
}

@Suite struct DebugBatchTests {
    @Test func cursorInsideRange() {
        #expect(planBatch(fileSeqRange: 10...900, cursor: 100) == BatchPlan(fromSeq: 101, toSeq: 600, lostFrom: nil, lostTo: nil))
    }

    @Test func cursorBeforeRange() {
        #expect(planBatch(fileSeqRange: 400...900, cursor: 100) == BatchPlan(fromSeq: 400, toSeq: 899, lostFrom: 101, lostTo: 399))
    }

    @Test func nothingToSend() {
        #expect(planBatch(fileSeqRange: 10...900, cursor: 900) == nil)
    }
}
