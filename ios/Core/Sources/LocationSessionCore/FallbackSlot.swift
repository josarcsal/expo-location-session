import Foundation

public struct FallbackFix: Equatable {
    public let sessionId: String
    public let uuid: String
    public let fix: Fix
    public let generation: Int
}

public enum LatestPick: Equatable {
    case memory, queue, none
}

public struct FallbackSlot {
    public private(set) var pending: FallbackFix?
    private var generation = 0
    private var floor: (sessionId: String, fixTs: Int64)?

    public init() {}

    @discardableResult
    public mutating func put(sessionId: String, uuid: String, fix: Fix) -> Int {
        generation += 1
        pending = FallbackFix(sessionId: sessionId, uuid: uuid, fix: fix, generation: generation)
        let previous = floor?.sessionId == sessionId ? floor?.fixTs : nil
        floor = (sessionId, max(previous ?? fix.timestampMs, fix.timestampMs))
        return generation
    }

    public func next(sessionId: String) -> FallbackFix? {
        pending?.sessionId == sessionId ? pending : nil
    }

    public func pick(sessionId: String, queuedFixTs: Int64?) -> LatestPick {
        if next(sessionId: sessionId) != nil { return .memory }
        guard let queuedFixTs, !supersedes(sessionId: sessionId, fixTs: queuedFixTs) else { return .none }
        return .queue
    }

    public static func resumes(pendingFixTs: Int64, over queuedFixTs: Int64?) -> Bool {
        queuedFixTs.map { pendingFixTs > $0 } ?? true
    }

    public mutating func markSent(generation sent: Int) {
        if pending?.generation == sent { pending = nil }
    }

    public mutating func stored() {
        pending = nil
        floor = nil
    }

    public func supersedes(sessionId: String, fixTs: Int64) -> Bool {
        guard let floor, floor.sessionId == sessionId else { return false }
        return fixTs <= floor.fixTs
    }

    public mutating func keep(sessionId: String?) {
        if pending?.sessionId != sessionId { pending = nil }
        if floor?.sessionId != sessionId { floor = nil }
    }
}
