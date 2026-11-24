import Foundation

public func buildBody(sessionId: String, fix: Fix, battery: (level: Double, charging: Bool)?, extrasJson: String) -> Data {
    var location: [String: Any] = [
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "accuracy": fix.accuracy,
        "timestamp": fix.timestampMs,
    ]
    if let v = fix.altitude { location["altitude"] = v }
    if let v = fix.speed { location["speed"] = v }
    if let v = fix.heading { location["heading"] = v }

    var body: [String: Any] = [
        "sessionId": sessionId,
        "location": location,
        "extras": parseExtras(extrasJson),
    ]
    if let b = battery { body["battery"] = ["level": b.level, "charging": b.charging] }

    return (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
}

private func parseExtras(_ json: String) -> [String: Any] {
    guard let data = json.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return obj
}

public func batchLimit(_ s: SyncConfig) -> Int {
    s.batch?.maxSize ?? 1
}

public func buildElement(record: FixRecord, sync: SyncConfig, extrasJson: String) -> String {
    if let template = sync.templateJson {
        return renderTemplate(template, record: record, extrasJson: extrasJson)
    }
    if batchLimit(sync) > 1 {
        var element: [String: Any] = ["id": record.uuid, "location": locationObject(record.fix)]
        if let b = record.battery { element["battery"] = ["level": b.level, "charging": b.charging] }
        return serialize(element)
    }
    let v01 = buildBody(sessionId: record.sessionId, fix: record.fix, battery: record.battery.map { ($0.level, $0.charging) }, extrasJson: extrasJson)
    if sync.mode == .all, var body = (try? JSONSerialization.jsonObject(with: v01)) as? [String: Any] {
        body["id"] = record.uuid
        return serialize(body)
    }
    return String(decoding: v01, as: UTF8.self)
}

public func buildRequestBody(sessionId: String, elements: [String], sync: SyncConfig, extrasJson: String) -> Data {
    guard batchLimit(sync) > 1, let batch = sync.batch else {
        return Data((elements.first ?? "{}").utf8)
    }
    let text = "{\"sessionId\":\(serializeFragment(sessionId)),\(serializeFragment(batch.rootProperty)):[\(elements.joined(separator: ","))],\"extras\":\(serialize(parseExtras(extrasJson)))}"
    return Data(text.utf8)
}

private func locationObject(_ fix: Fix) -> [String: Any] {
    var location: [String: Any] = [
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "accuracy": fix.accuracy,
        "timestamp": fix.timestampMs,
    ]
    if let v = fix.altitude { location["altitude"] = v }
    if let v = fix.speed { location["speed"] = v }
    if let v = fix.heading { location["heading"] = v }
    return location
}

private func serialize(_ object: [String: Any]) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else { return "{}" }
    return String(decoding: data, as: UTF8.self)
}

private func serializeFragment(_ s: String) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: s, options: [.fragmentsAllowed]) else { return "\"\"" }
    return String(decoding: data, as: UTF8.self)
}
