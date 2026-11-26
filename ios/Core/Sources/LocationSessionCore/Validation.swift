import Foundation

public func validate(_ c: SessionConfig, nowMs: Int64, running: Bool) -> String? {
    if running { return "ALREADY_RUNNING" }
    if isInvalid(c) { return "INVALID_CONFIG" }
    if c.expiresAtMs <= nowMs { return "EXPIRED" }
    return nil
}

public func syncInvalidReason(_ c: SessionConfig) -> String? {
    isInvalidBase(c) ? nil : validateSync(c.sync, extrasJson: c.uploadExtrasJson)
}

private func isInvalid(_ c: SessionConfig) -> Bool {
    isInvalidBase(c) || validateSync(c.sync, extrasJson: c.uploadExtrasJson) != nil
}

private func isInvalidBase(_ c: SessionConfig) -> Bool {
    if c.id.isEmpty { return true }
    guard let url = URL(string: c.uploadUrl), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else { return true }
    if let d = c.destination, !(d.radiusMeters > 0) { return true }
    if !(c.distanceFilterMeters >= 0) { return true }
    if c.uploadMinIntervalMs < 0 { return true }
    return !["high", "balanced", "low"].contains(c.accuracy)
}
