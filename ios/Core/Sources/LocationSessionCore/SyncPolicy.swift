import Foundation

public enum SyncAction: Equatable {
    case deleteSent, refreshAndRetry, stop(StopReason), dropSent, dropSession, retry
}

public func syncAction(status: Int?, ended: Bool, refreshConfigured: Bool, alreadyRefreshed: Bool) -> SyncAction {
    guard let status else { return .retry }
    switch status {
    case 200..<300:
        return .deleteSent
    case 401 where refreshConfigured:
        if !alreadyRefreshed { return .refreshAndRetry }
        return ended ? .retry : .stop(.unauthorized)
    case 401, 403:
        return ended ? .dropSent : .stop(.unauthorized)
    case 410:
        return ended ? .dropSession : .stop(.rejected)
    case 400..<500:
        return .dropSent
    default:
        return .retry
    }
}

public func isExpired(fixTs: Int64, nowMs: Int64, maxAgeMs: Int64) -> Bool {
    nowMs - fixTs > maxAgeMs
}

public func trimCount(rows: Int, maxRecords: Int) -> Int {
    max(0, rows - maxRecords)
}
