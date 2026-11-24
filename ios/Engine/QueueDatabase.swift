import Foundation
import UIKit

struct PendingInsert {
    let sessionId: String
    let uuid: String
    let fixTs: Int64
    let body: String
    let mode: QueueMode
    let maxRecords: Int
}

final class QueueDatabase {
    static let shared = QueueDatabase()

    private static var recovered = false
    private static var rebuilt = false

    private let credentials = TargetCredentials()
    private var opened: QueueStore?
    private var buffer = BoundedBuffer<PendingInsert>(capacity: 500)
    private var loggedUnavailable = false
    private(set) var unavailable = false

    private init() {}

    func store() -> QueueStore? {
        dispatchPrecondition(condition: .onQueue(.main))
        if let opened { return opened }
        guard let s = open() else { return nil }
        opened = s
        loggedUnavailable = false
        unavailable = false
        if !Self.recovered {
            Self.recovered = true
            let count = s.recoverInFlight()
            Log.debug("queue", "recovered in-flight rows", data: ["rows": count])
        }
        purge(s)
        return s
    }

    func purge() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let s = store() else { return }
        purge(s)
    }

    private func purge(_ s: QueueStore) {
        let purged = s.purge(nowMs: nowMs())
        purged.targetsRemoved.forEach { credentials.remove(sessionId: $0) }
        if purged.rowsDeleted > 0 || !purged.targetsRemoved.isEmpty {
            Log.debug("queue", "purged", data: ["rows": purged.rowsDeleted, "targets": purged.targetsRemoved.count])
        }
    }

    func enqueue(_ p: PendingInsert) -> Int {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let s = store() else { return bufferInsert(p) }
        drainBuffer(into: s)
        do {
            let trimmed = try s.insert(sessionId: p.sessionId, uuid: p.uuid, fixTs: p.fixTs, body: p.body, mode: p.mode, maxRecords: p.maxRecords)
            Log.debug("queue", "inserted", data: ["mode": p.mode.rawValue, "fixTimestamp": p.fixTs, "trimmed": trimmed], sessionId: p.sessionId)
            if trimmed > 0 {
                Log.debug("queue", "trimmed", data: ["rows": trimmed, "maxRecords": p.maxRecords], sessionId: p.sessionId, level: "warn")
            }
            return trimmed
        } catch {
            Log.debug("queue", "insert failed", data: ["error": "\(error)"], sessionId: p.sessionId, level: "warn")
            return bufferInsert(p)
        }
    }

    func onProtectedDataAvailable() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard buffer.count > 0, let s = store() else { return }
        drainBuffer(into: s)
    }

    private func bufferInsert(_ p: PendingInsert) -> Int {
        let dropped = buffer.append(p)
        Log.debug("queue", "buffered", data: ["buffered": buffer.count, "dropped": dropped], sessionId: p.sessionId, level: dropped ? "warn" : "debug")
        return dropped ? 1 : 0
    }

    private func drainBuffer(into s: QueueStore) {
        guard buffer.count > 0 else { return }
        let pending = buffer.drain()
        var trimmed = 0
        var failed = 0
        for p in pending {
            do {
                trimmed += try s.insert(sessionId: p.sessionId, uuid: p.uuid, fixTs: p.fixTs, body: p.body, mode: p.mode, maxRecords: p.maxRecords)
            } catch {
                failed += 1
            }
        }
        Log.debug("queue", "buffer drained", data: ["rows": pending.count, "trimmed": trimmed, "failed": failed], level: failed > 0 ? "warn" : "debug")
    }

    private func open() -> QueueStore? {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("expo-location-session", isDirectory: true)
        let path = dir.appendingPathComponent("queue.sqlite").path
        do {
            if !fm.fileExists(atPath: dir.path) {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            }
            var excluded = dir
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try excluded.setResourceValues(values)
            do {
                return try QueueStore(path: path)
            } catch let error as QueueStoreError where error.isCorrupt && !Self.rebuilt && UIApplication.shared.isProtectedDataAvailable {
                Self.rebuilt = true
                for suffix in ["", "-wal", "-shm"] where fm.fileExists(atPath: path + suffix) {
                    try fm.removeItem(atPath: path + suffix)
                }
                Log.debug("queue", "corrupt database deleted and recreated", data: ["error": error.message, "code": Int(error.code)], level: "warn")
                return try QueueStore(path: path)
            }
        } catch {
            unavailable = UIApplication.shared.isProtectedDataAvailable
            if !loggedUnavailable {
                loggedUnavailable = true
                Log.debug("queue", "database unavailable", data: ["error": "\(error)"], level: "warn")
            }
            return nil
        }
    }
}
