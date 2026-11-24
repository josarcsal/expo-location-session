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

public enum StopQueueAction: Equatable {
    case deleteRows, deleteRowsDropped, markEnded
}

public func stopQueueAction(mode: QueueMode, reason: StopReason) -> StopQueueAction {
    guard mode == .all else { return .deleteRows }
    return reason == .rejected ? .deleteRowsDropped : .markEnded
}

public func isExpired(fixTs: Int64, nowMs: Int64, maxAgeMs: Int64) -> Bool {
    nowMs - fixTs > maxAgeMs
}

public func trimCount(rows: Int, maxRecords: Int) -> Int {
    max(0, rows - maxRecords)
}

public struct RefreshResult: Equatable {
    public var headers: [String: String]
    public var refreshHeaders: [String: String]?

    public init(headers: [String: String], refreshHeaders: [String: String]?) {
        self.headers = headers
        self.refreshHeaders = refreshHeaders
    }
}

public func parseRefreshResponse(status: Int?, body: Data) -> RefreshResult? {
    guard let status, (200..<300).contains(status),
          let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
          let headers = object["headers"] as? [String: Any], !headers.isEmpty,
          let stringHeaders = headers as? [String: String]
    else { return nil }
    var refreshHeaders: [String: String]?
    if let raw = object["refreshHeaders"] {
        guard let parsed = raw as? [String: String] else { return nil }
        refreshHeaders = parsed
    }
    return RefreshResult(headers: stringHeaders, refreshHeaders: refreshHeaders)
}

public func refreshRequestBody(bodyJson: String?, sessionId: String) -> Data {
    if let bodyJson { return Data(bodyJson.utf8) }
    return (try? JSONSerialization.data(withJSONObject: ["sessionId": sessionId])) ?? Data()
}

public struct ConnectivityEdge {
    private var satisfied: Bool?

    public init() {}

    public mutating func update(satisfied now: Bool) -> Bool {
        defer { satisfied = now }
        return satisfied == false && now
    }
}
