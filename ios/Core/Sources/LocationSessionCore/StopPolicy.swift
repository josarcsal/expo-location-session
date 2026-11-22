import Foundation

public func acceptFix(_ f: Fix, startedAtMs: Int64) -> Bool {
    f.timestampMs >= startedAtMs
}

public let duplicateFixWindowMs: Int64 = 5_000

public func isDuplicateFix(_ f: Fix, of previous: Fix?) -> Bool {
    guard let p = previous else { return false }
    return f.latitude == p.latitude && f.longitude == p.longitude && f.accuracy >= p.accuracy
        && abs(f.timestampMs - p.timestampMs) <= duplicateFixWindowMs
}

public func distanceMeters(latitude lat1: Double, longitude lon1: Double, latitude lat2: Double, longitude lon2: Double) -> Double {
    let r = 6_371_000.0
    let p1 = lat1 * .pi / 180
    let p2 = lat2 * .pi / 180
    let dp = (lat2 - lat1) * .pi / 180
    let dl = (lon2 - lon1) * .pi / 180
    let a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(min(1, sqrt(a)))
}

public func isArrival(_ d: Destination, _ f: Fix) -> Bool {
    let distance = distanceMeters(latitude: d.latitude, longitude: d.longitude, latitude: f.latitude, longitude: f.longitude)
    return distance <= d.radiusMeters && f.accuracy <= max(d.radiusMeters, 100)
}

public func stopDecision(_ c: SessionConfig, fix: Fix?, nowMs: Int64) -> StopReason? {
    if nowMs >= c.expiresAtMs { return .expired }
    if let d = c.destination, let f = fix, isArrival(d, f) { return .arrived }
    return nil
}
