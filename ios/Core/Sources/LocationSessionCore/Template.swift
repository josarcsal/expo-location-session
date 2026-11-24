import Foundation

public struct FixRecord: Equatable {
    public var uuid: String
    public var sessionId: String
    public var fix: Fix
    public var battery: Battery?

    public init(uuid: String, sessionId: String, fix: Fix, battery: Battery?) {
        self.uuid = uuid
        self.sessionId = sessionId
        self.fix = fix
        self.battery = battery
    }
}

private enum Segment {
    case text(String)
    case placeholder(String)
}

private let templateFields: Set<String> = [
    "id", "sessionId", "latitude", "longitude", "accuracy", "altitude", "speed", "heading",
    "timestamp", "timestampIso", "batteryLevel", "batteryCharging",
]

private let maxTemplateBytes = 8192

private func segments(of s: String) -> [Segment]? {
    var out: [Segment] = []
    var text = ""
    var rest = Substring(s)
    while let open = rest.range(of: "{{") {
        text += rest[rest.startIndex..<open.lowerBound]
        let after = rest[open.upperBound...]
        guard let close = after.range(of: "}}") else { return nil }
        let name = String(after[after.startIndex..<close.lowerBound])
        if name.isEmpty || name.contains("{") { return nil }
        if !text.isEmpty { out.append(.text(text)); text = "" }
        out.append(.placeholder(name))
        rest = after[close.upperBound...]
    }
    text += rest
    if !text.isEmpty { out.append(.text(text)) }
    return out
}

private func parseObject(_ json: String) -> [String: Any]? {
    guard let data = json.data(using: .utf8),
          let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
    return parsed
}

private func checkNode(_ node: Any, extras: [String: Any]) -> String? {
    if let dict = node as? [String: Any] {
        for v in dict.values { if let r = checkNode(v, extras: extras) { return r } }
    } else if let arr = node as? [Any] {
        for v in arr { if let r = checkNode(v, extras: extras) { return r } }
    } else if let s = node as? String {
        guard let segs = segments(of: s) else { return "malformed placeholder in \"\(s)\"" }
        for seg in segs {
            guard case .placeholder(let name) = seg else { continue }
            if name.hasPrefix("extras.") {
                let key = String(name.dropFirst("extras.".count))
                if extras[key] == nil { return "unknown extras key \(key)" }
            } else if !templateFields.contains(name) {
                return "unknown field \(name)"
            }
        }
    }
    return nil
}

public func validateTemplate(_ json: String, extrasJson: String) -> String? {
    guard let root = parseObject(json) else { return "must be a JSON object" }
    if json.utf8.count > maxTemplateBytes { return "larger than 8 KB" }
    return checkNode(root, extras: parseObject(extrasJson) ?? [:])
}

private func isoString(_ ms: Int64) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    f.timeZone = TimeZone(identifier: "UTC")
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

private func lookup(_ name: String, _ record: FixRecord, _ extras: [String: Any]) -> Any? {
    if name.hasPrefix("extras.") {
        let value = extras[String(name.dropFirst("extras.".count))]
        return value is NSNull ? nil : value
    }
    guard let value = rawLookup(name, record) else { return nil }
    if let d = value as? Double, !d.isFinite { return NSNull() }
    return value
}

private func rawLookup(_ name: String, _ record: FixRecord) -> Any? {
    switch name {
    case "id": return record.uuid
    case "sessionId": return record.sessionId
    case "latitude": return record.fix.latitude
    case "longitude": return record.fix.longitude
    case "accuracy": return record.fix.accuracy
    case "altitude": return record.fix.altitude
    case "speed": return record.fix.speed
    case "heading": return record.fix.heading
    case "timestamp": return NSNumber(value: record.fix.timestampMs)
    case "timestampIso": return isoString(record.fix.timestampMs)
    case "batteryLevel": return record.battery?.level
    case "batteryCharging": return record.battery?.charging
    default: return nil
    }
}

private func text(of value: Any) -> String {
    if let s = value as? String { return s }
    if let n = value as? NSNumber {
        if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
        switch String(cString: n.objCType) {
        case "d", "f": return "\(n.doubleValue)"
        default: return "\(n.int64Value)"
        }
    }
    if let d = value as? Double { return "\(d)" }
    if JSONSerialization.isValidJSONObject(value),
       let data = try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]),
       let s = String(data: data, encoding: .utf8) { return s }
    return ""
}

private func render(_ node: Any, _ record: FixRecord, _ extras: [String: Any]) -> Any? {
    if let dict = node as? [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in dict { if let r = render(v, record, extras) { out[k] = r } }
        return out
    }
    if let arr = node as? [Any] {
        return arr.map { render($0, record, extras) ?? NSNull() }
    }
    if let s = node as? String, let segs = segments(of: s) {
        if segs.count == 1, case .placeholder(let name) = segs[0] {
            return lookup(name, record, extras)
        }
        var out = ""
        for seg in segs {
            switch seg {
            case .text(let t): out += t
            case .placeholder(let name): out += lookup(name, record, extras).map(text(of:)) ?? ""
            }
        }
        return out
    }
    return node
}

public func renderTemplate(_ json: String, record: FixRecord, extrasJson: String) -> String {
    guard let root = parseObject(json),
          let rendered = render(root, record, parseObject(extrasJson) ?? [:]),
          JSONSerialization.isValidJSONObject(rendered),
          let data = try? JSONSerialization.data(withJSONObject: rendered, options: [.withoutEscapingSlashes]),
          let out = String(data: data, encoding: .utf8) else { return "{}" }
    return out
}
