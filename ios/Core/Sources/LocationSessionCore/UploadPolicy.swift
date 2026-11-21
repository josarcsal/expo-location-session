import Foundation

public enum UploadOutcome: Equatable {
    case success, stop(StopReason), drop, retry
}

public func classify(status: Int?) -> UploadOutcome {
    guard let status else { return .retry }
    switch status {
    case 200..<300: return .success
    case 401, 403: return .stop(.unauthorized)
    case 410: return .stop(.rejected)
    case 400..<500: return .drop
    default: return .retry
    }
}

public func backoffMs(attempt: Int) -> Int64 {
    let steps: [Int64] = [5000, 10000, 20000, 40000]
    guard attempt >= 0 else { return steps[0] }
    return attempt < steps.count ? steps[attempt] : 60000
}

public let finalUploadBudgetMs: Int64 = 30_000

public func shouldUploadNow(lastSuccessMs: Int64?, nowMs: Int64, minIntervalMs: Int64, bypass: Bool) -> Bool {
    if bypass { return true }
    guard let last = lastSuccessMs else { return true }
    return nowMs - last >= minIntervalMs
}
