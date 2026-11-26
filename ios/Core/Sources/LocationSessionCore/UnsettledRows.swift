import Foundation

public struct UnsettledRows {
    public private(set) var deletes: Set<Int64> = []
    public private(set) var releases: Set<Int64> = []

    public init() {}

    public var isEmpty: Bool { deletes.isEmpty && releases.isEmpty }

    @discardableResult
    public mutating func delete(_ ids: [Int64], _ op: ([Int64]) -> Bool) -> Bool {
        guard !ids.isEmpty else { return true }
        let ok = op(ids)
        if ok { deletes.subtract(ids) } else { deletes.formUnion(ids) }
        releases.subtract(ids)
        return ok
    }

    @discardableResult
    public mutating func release(_ ids: [Int64], _ op: ([Int64]) -> Bool) -> Bool {
        let open = ids.filter { !deletes.contains($0) }
        guard !open.isEmpty else { return true }
        let ok = op(open)
        if ok { releases.subtract(open) } else { releases.formUnion(open) }
        return ok
    }

    public mutating func retain(_ existing: Set<Int64>) {
        deletes.formIntersection(existing)
        releases.formIntersection(existing)
    }

    public var ids: [Int64] { deletes.union(releases).sorted() }

    public mutating func retry(delete: ([Int64]) -> Bool, release: ([Int64]) -> Bool) -> Int {
        var settled = 0
        if !deletes.isEmpty, delete(deletes.sorted()) {
            settled += deletes.count
            deletes.removeAll()
        }
        if !releases.isEmpty, release(releases.sorted()) {
            settled += releases.count
            releases.removeAll()
        }
        return settled
    }
}
