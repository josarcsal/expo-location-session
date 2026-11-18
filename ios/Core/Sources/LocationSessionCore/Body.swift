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
