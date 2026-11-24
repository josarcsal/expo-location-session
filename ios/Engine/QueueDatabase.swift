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

enum EnqueueResult {
    case stored(trimmed: Int)
    case buffered(dropped: Bool)
    case failed
}

final class QueueDatabase {
    static let shared = QueueDatabase()

    private static var recovered = false
    private static var rebuilt = false
    private static let reopenCooldownMs: Int64 = 5_000

    private let credentials = TargetCredentials()
    private var opened: QueueStore?
    private var failedAtMs: Int64?
    private var buffer = BoundedBuffer<PendingInsert>(capacity: 500)
    private var unsettled = UnsettledRows()
    private var unsavedTargets: [String: QueueTarget] = [:]
    private var undeletedSessions: Set<String> = []
    private var loggedUnavailable = false
    private var insertFailing = false
    private(set) var unavailable = false

    var onDeletesSettled: (() -> Void)?

    var isOpen: Bool { opened != nil }
    var failing: Bool { unavailable || insertFailing }
    var buffered: Int { buffer.count }

    private init() {}

    func store() -> QueueStore? {
        dispatchPrecondition(condition: .onQueue(.main))
        if let opened { return opened }
        if let failedAtMs, nowMs() - failedAtMs < Self.reopenCooldownMs { return nil }
        let openedAt = DispatchTime.now().uptimeNanoseconds
        let recovering = !Self.recovered
        guard let s = open() else {
            failedAtMs = nowMs()
            return nil
        }
        if recovering {
            let count = s.recoverInFlight()
            guard count >= 0 else {
                failedAtMs = nowMs()
                unavailable = UIApplication.shared.isProtectedDataAvailable
                if !loggedUnavailable {
                    loggedUnavailable = true
                    Log.debug("queue", "in-flight recovery failed; database unavailable", level: "warn")
                }
                return nil
            }
            Self.recovered = true
            Log.debug("queue", "recovered in-flight rows", data: ["rows": count])
        }
        opened = s
        failedAtMs = nil
        loggedUnavailable = false
        unavailable = false
        retrySessionDeletes(s)
        purge(s)
        if recovering {
            let durationMs = Int64((DispatchTime.now().uptimeNanoseconds - openedAt) / 1_000_000)
            Log.debug("queue", "queue opened in \(durationMs)ms", data: ["durationMs": durationMs])
        }
        return s
    }

    func purge() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let s = store() else { return }
        purge(s)
    }

    private func purge(_ s: QueueStore) {
        let purged = s.purge(nowMs: nowMs())
        if purged.rowsDeleted > 0 { prune(s) }
        purged.targetsRemoved.forEach { credentials.remove(sessionId: $0) }
        if purged.rowsDeleted > 0 || !purged.targetsRemoved.isEmpty {
            Log.debug("queue", "purged", data: ["rows": purged.rowsDeleted, "targets": purged.targetsRemoved.count])
        }
    }

    func enqueue(_ p: PendingInsert) -> EnqueueResult {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let s = store() else { return keep(p) }
        settle(s)
        let flushed = flushBuffer(into: s)
        guard flushed.done, saveTargets(s) else {
            insertFailing = true
            return keep(p)
        }
        do {
            let trimmed = try s.insert(sessionId: p.sessionId, uuid: p.uuid, fixTs: p.fixTs, body: p.body, mode: p.mode, maxRecords: p.maxRecords)
            insertFailing = false
            Log.debug("queue", "inserted", data: ["mode": p.mode.rawValue, "fixTimestamp": p.fixTs, "trimmed": trimmed], sessionId: p.sessionId)
            if trimmed > 0 {
                Log.debug("queue", "trimmed", data: ["rows": trimmed, "maxRecords": p.maxRecords], sessionId: p.sessionId, level: "warn")
            }
            return .stored(trimmed: flushed.trimmed + trimmed)
        } catch {
            if !insertFailing {
                insertFailing = true
                Log.debug("queue", "insert failed", data: ["error": "\(error)"], sessionId: p.sessionId, level: "warn")
            }
            return keep(p)
        }
    }

    func prepareSend() -> Int {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let s = store() else { return 0 }
        settle(s)
        return flushBuffer(into: s).trimmed
    }

    func onProtectedDataAvailable() -> Int {
        dispatchPrecondition(condition: .onQueue(.main))
        failedAtMs = nil
        guard !buffer.isEmpty, let s = store() else { return 0 }
        return flushBuffer(into: s).trimmed
    }

    func probe() -> Int {
        dispatchPrecondition(condition: .onQueue(.main))
        guard failing, let s = store() else { return 0 }
        settle(s)
        return buffer.isEmpty ? 0 : flushBuffer(into: s).trimmed
    }

    func status(hiding hidden: Set<Int64> = []) -> QueueStatus? {
        dispatchPrecondition(condition: .onQueue(.main))
        return store()?.status(excluding: unsettled.deletes.union(hidden))
    }

    func hasUnsettledDeletes(sessionId: String) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !unsettled.deletes.isEmpty else { return false }
        guard let s = store() else { return true }
        return !s.existing(ids: unsettled.deletes.sorted(), sessionId: sessionId).isEmpty
    }

    func delete(_ ids: [Int64]) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !ids.isEmpty else { return }
        let s = store()
        if !unsettled.delete(ids, { s?.delete(ids: $0) ?? false }) {
            Log.debug("queue", "delete failed; rows kept as sent", data: ["rows": ids.count, "unsettled": unsettled.deletes.count], level: "warn")
        }
    }

    func release(_ ids: [Int64]) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard !ids.isEmpty else { return }
        let s = store()
        if !unsettled.release(ids, { s?.release(ids: $0) ?? false }) {
            Log.debug("queue", "release failed; rows kept in flight", data: ["rows": ids.count, "unsettled": unsettled.releases.count], level: "warn")
        }
    }

    func saveTarget(_ t: QueueTarget) {
        dispatchPrecondition(condition: .onQueue(.main))
        unsavedTargets[t.sessionId] = t
        if store().map(saveTargets) != true {
            Log.debug("queue", "target save deferred", sessionId: t.sessionId, level: "warn")
        }
    }

    func markEnded(_ sessionId: String) {
        dispatchPrecondition(condition: .onQueue(.main))
        unsavedTargets[sessionId]?.ended = true
        store()?.markEnded(sessionId)
    }

    func deleteSession(_ sessionId: String) -> Int {
        dispatchPrecondition(condition: .onQueue(.main))
        unsavedTargets[sessionId] = nil
        let buffered = buffer.removeAll { $0.sessionId == sessionId }
        guard let s = store() else {
            keepUndeleted(sessionId)
            return buffered
        }
        let deleted = s.deleteSession(sessionId)
        guard deleted >= 0 else {
            keepUndeleted(sessionId)
            return buffered
        }
        undeletedSessions.remove(sessionId)
        prune(s)
        return buffered + deleted
    }

    func clear() -> [String]? {
        dispatchPrecondition(condition: .onQueue(.main))
        _ = buffer.drain()
        unsavedTargets = unsavedTargets.filter { !$0.value.ended }
        guard let s = store() else { return nil }
        unsettled = UnsettledRows()
        return s.clear()
    }

    private func keepUndeleted(_ sessionId: String) {
        undeletedSessions.insert(sessionId)
        Log.debug("queue", "session delete failed; retried when the database is available", sessionId: sessionId, level: "warn")
    }

    private func retrySessionDeletes(_ s: QueueStore) {
        guard !undeletedSessions.isEmpty else { return }
        for id in undeletedSessions.sorted() where s.deleteSession(id) >= 0 {
            undeletedSessions.remove(id)
            Log.debug("queue", "session rows deleted on retry", sessionId: id)
        }
        prune(s)
    }

    private func keep(_ p: PendingInsert) -> EnqueueResult {
        guard p.mode == .all else { return .failed }
        let dropped = buffer.append(p)
        Log.debug("queue", "buffered", data: ["buffered": buffer.count, "dropped": dropped], sessionId: p.sessionId, level: dropped ? "warn" : "debug")
        return .buffered(dropped: dropped)
    }

    private func prune(_ s: QueueStore) {
        guard !unsettled.isEmpty else { return }
        let deletes = unsettled.deletes.count
        unsettled.retain(s.existing(ids: unsettled.ids))
        if unsettled.deletes.count < deletes { onDeletesSettled?() }
    }

    private func settle(_ s: QueueStore) {
        retrySessionDeletes(s)
        guard !unsettled.isEmpty else { return }
        let deletes = unsettled.deletes.count
        let settled = unsettled.retry(delete: { s.delete(ids: $0) }, release: { s.release(ids: $0) })
        if settled > 0 {
            Log.debug("queue", "unsettled rows settled", data: ["rows": settled, "unsettled": unsettled.deletes.count + unsettled.releases.count])
        }
        if unsettled.deletes.count < deletes { onDeletesSettled?() }
    }

    private func saveTargets(_ s: QueueStore) -> Bool {
        for id in unsavedTargets.keys.sorted() {
            guard let t = unsavedTargets[id] else { continue }
            do {
                try s.saveTarget(t)
                unsavedTargets[id] = nil
            } catch {
                return false
            }
        }
        return true
    }

    private func flushBuffer(into s: QueueStore) -> (done: Bool, trimmed: Int) {
        guard !buffer.isEmpty else { return (true, 0) }
        guard saveTargets(s) else {
            insertFailing = true
            return (false, 0)
        }
        var trimmed = 0
        let inserted = buffer.flush { p in
            guard let t = try? s.insert(sessionId: p.sessionId, uuid: p.uuid, fixTs: p.fixTs, body: p.body, mode: p.mode, maxRecords: p.maxRecords) else { return false }
            trimmed += t
            return true
        }
        insertFailing = !buffer.isEmpty
        if inserted > 0 {
            Log.debug("queue", "buffer drained", data: ["rows": inserted, "trimmed": trimmed, "remaining": buffer.count], level: buffer.isEmpty ? "debug" : "warn")
        }
        return (buffer.isEmpty, trimmed)
    }

    private func removeOrphanCredentials() {
        let running: String?
        switch SessionStore().load(protectedDataAvailable: true) {
        case .none: running = nil
        case let .session(c, _): running = c.id
        case .unavailable, .corrupt: return
        }
        let removed = credentials.removeAll(except: running)
        if removed > 0 {
            Log.debug("queue", "credentials of lost sessions removed", data: ["targets": removed])
        }
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
                let rebuiltStore = try QueueStore(path: path)
                removeOrphanCredentials()
                return rebuiltStore
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
