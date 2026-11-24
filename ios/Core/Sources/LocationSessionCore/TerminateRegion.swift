import Foundation

public let terminateRadiusFloorMeters = 150.0
public let terminateRadiusCapMeters = 1000.0

public func terminateRadius(_ fix: Fix, nowMs: Int64) -> Double {
    var radius = terminateRadiusFloorMeters
    if fix.accuracy.isFinite, fix.accuracy >= terminateRadiusFloorMeters {
        radius += fix.accuracy
    }
    if let speed = fix.speed, speed.isFinite, speed > 0 {
        let seconds = Double(max(0, nowMs - fix.timestampMs)) / 1000
        radius += speed * seconds
    }
    return min(radius, terminateRadiusCapMeters)
}
