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
