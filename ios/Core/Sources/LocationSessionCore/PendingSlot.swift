import Foundation

public struct PendingSlot {
    public private(set) var fix: Fix?
    private var generation = 0

    public init() {}

    public mutating func put(_ f: Fix) -> Int {
        generation += 1
        fix = f
        return generation
    }

    public mutating func markSent(generation sent: Int) {
        if sent == generation { fix = nil }
    }
}
