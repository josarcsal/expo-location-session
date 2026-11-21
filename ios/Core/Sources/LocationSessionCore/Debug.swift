import Foundation

public struct DebugOptions: Equatable {
    public var enabled: Bool
    public var url: String?
    public var headers: [String: String]
    public var flushIntervalMs: Int64
    public var maxFileBytes: Int64

    public init(enabled: Bool, url: String?, headers: [String: String], flushIntervalMs: Int64, maxFileBytes: Int64) {
        self.enabled = enabled
        self.url = url
        self.headers = headers
        self.flushIntervalMs = flushIntervalMs
        self.maxFileBytes = maxFileBytes
    }
}

public struct LogLine {
    public var seq: Int64
    public var t: Int64
    public var pid: Int32
    public var level: String
    public var tag: String
    public var msg: String
    public var sessionId: String?
    public var data: [String: Any]?

    public init(seq: Int64, t: Int64, pid: Int32, level: String, tag: String, msg: String, sessionId: String? = nil, data: [String: Any]? = nil) {
        self.seq = seq
        self.t = t
        self.pid = pid
        self.level = level
        self.tag = tag
        self.msg = msg
        self.sessionId = sessionId
        self.data = data
    }
}

public struct BatchPlan: Equatable {
    public var fromSeq: Int64
    public var toSeq: Int64
    public var lostFrom: Int64?
    public var lostTo: Int64?

    public init(fromSeq: Int64, toSeq: Int64, lostFrom: Int64? = nil, lostTo: Int64? = nil) {
        self.fromSeq = fromSeq
        self.toSeq = toSeq
        self.lostFrom = lostFrom
        self.lostTo = lostTo
    }
}

public let debugMinFlushIntervalMs: Int64 = 10_000
public let debugMaxFileBytesRange: ClosedRange<Int64> = 100_000...10_000_000

public func validateDebugOptions(_ o: DebugOptions) -> String? {
    if let raw = o.url {
        guard let url = URL(string: raw), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else {
            return "debug url must be an https URL"
        }
    }
    if o.flushIntervalMs < debugMinFlushIntervalMs {
        return "flushIntervalMs must be at least \(debugMinFlushIntervalMs)"
    }
    if !debugMaxFileBytesRange.contains(o.maxFileBytes) {
        return "maxFileBytes must be between \(debugMaxFileBytesRange.lowerBound) and \(debugMaxFileBytesRange.upperBound)"
    }
    return nil
}

public func encodeLogLine(_ line: LogLine) -> String {
    var obj: [String: Any] = [
        "t": line.t,
        "pid": line.pid,
        "level": line.level,
        "tag": line.tag,
        "msg": line.msg,
    ]
    if let s = line.sessionId { obj["sessionId"] = s }
    if let d = line.data, let clean = sanitize(d) { obj["data"] = clean }
    let body: String
    if let data = try? JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes]),
       let json = String(data: data, encoding: .utf8) {
        body = json
    } else {
        obj.removeValue(forKey: "data")
        let fallback = (try? JSONSerialization.data(withJSONObject: obj)) ?? Data("{}".utf8)
        body = String(decoding: fallback, as: UTF8.self)
    }
    let rest = body.dropFirst()
    return "{\"seq\":\(line.seq)" + (rest == "}" ? "" : ",") + rest
}

private let seqPrefix = Array("{\"seq\":".utf8)

public func lineSeq<C: BidirectionalCollection>(_ bytes: C) -> Int64? where C.Element == UInt8 {
    guard bytes.count > seqPrefix.count + 2, bytes.starts(with: seqPrefix) else { return nil }
    var index = bytes.index(bytes.startIndex, offsetBy: seqPrefix.count)
    var value: Int64 = 0
    var digits = 0
    while index != bytes.endIndex, bytes[index] >= 0x30, bytes[index] <= 0x39, digits < 18 {
        value = value * 10 + Int64(bytes[index] - 0x30)
        digits += 1
        index = bytes.index(after: index)
    }
    guard digits > 0, index != bytes.endIndex, bytes[index] == UInt8(ascii: ","), bytes.last == UInt8(ascii: "}") else { return nil }
    return value
}

public func planBatch(fileSeqRange: ClosedRange<Int64>, cursor: Int64, max: Int = 500) -> BatchPlan? {
    let next = cursor + 1
    guard next <= fileSeqRange.upperBound, max > 0 else { return nil }
    let from = Swift.max(next, fileSeqRange.lowerBound)
    let to = Swift.min(fileSeqRange.upperBound, from + Int64(max) - 1)
    if next < fileSeqRange.lowerBound {
        return BatchPlan(fromSeq: from, toSeq: to, lostFrom: next, lostTo: fileSeqRange.lowerBound - 1)
    }
    return BatchPlan(fromSeq: from, toSeq: to)
}

private func sanitize(_ value: Any) -> Any? {
    switch value {
    case let s as String:
        return s
    case let n as NSNumber:
        return n.doubleValue.isFinite ? n : nil
    case let d as [String: Any]:
        var out: [String: Any] = [:]
        for (k, v) in d {
            if let clean = sanitize(v) { out[k] = clean }
        }
        return out
    case let a as [Any]:
        return a.compactMap(sanitize)
    case is NSNull:
        return NSNull()
    default:
        return String(describing: value)
    }
}
