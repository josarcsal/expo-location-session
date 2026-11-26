import Testing
@testable import LocationSessionCore

@Suite struct BoundedBufferTests {
    @Test func dropsOldestBeyondCapacity() {
        var b = BoundedBuffer<Int>(capacity: 500)
        for i in 0..<500 {
            let dropped = b.append(i)
            #expect(!dropped)
        }
        let dropped = b.append(500)
        #expect(dropped)
        let all = b.drain()
        #expect(all.count == 500 && all.first == 1 && all.last == 500 && b.count == 0)
    }
}

@Suite struct BoundedBufferFlushTests {
    @Test func flushInsertsOldestFirstAndKeepsTheRestAfterAFailure() {
        var b = BoundedBuffer<Int>(capacity: 500)
        for i in 1...4 { _ = b.append(i) }
        var seen: [Int] = []
        #expect(b.flush { seen.append($0); return $0 < 3 } == 2)
        #expect(seen == [1, 2, 3])
        #expect(b.count == 2)
        #expect(b.flush { _ in true } == 2)
        #expect(b.isEmpty)
    }

    @Test func removeAllWhereDropsMatchingItems() {
        var b = BoundedBuffer<Int>(capacity: 500)
        for i in 1...4 { _ = b.append(i) }
        #expect(b.removeAll { $0 % 2 == 0 } == 2)
        #expect(b.drain() == [1, 3])
    }
}
