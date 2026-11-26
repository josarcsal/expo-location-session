import Foundation

public struct BoundedBuffer<T> {
    private var items: [T] = []
    private let capacity: Int

    public init(capacity: Int) {
        self.capacity = capacity
    }

    public var count: Int { items.count }

    public var isEmpty: Bool { items.isEmpty }

    public mutating func append(_ x: T) -> Bool {
        items.append(x)
        guard items.count > capacity else { return false }
        items.removeFirst(items.count - capacity)
        return true
    }

    public mutating func drain() -> [T] {
        defer { items.removeAll() }
        return items
    }

    public mutating func flush(_ insert: (T) -> Bool) -> Int {
        var inserted = 0
        while inserted < items.count, insert(items[inserted]) { inserted += 1 }
        items.removeFirst(inserted)
        return inserted
    }

    @discardableResult
    public mutating func removeAll(where match: (T) -> Bool) -> Int {
        let before = items.count
        items.removeAll(where: match)
        return before - items.count
    }
}
