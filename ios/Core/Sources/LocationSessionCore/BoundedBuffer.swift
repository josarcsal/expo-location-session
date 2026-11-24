import Foundation

public struct BoundedBuffer<T> {
    private var items: [T] = []
    private let capacity: Int

    public init(capacity: Int) {
        self.capacity = capacity
    }

    public var count: Int { items.count }

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
}
