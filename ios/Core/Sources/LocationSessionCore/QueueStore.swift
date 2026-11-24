import Foundation
import SQLite3

private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

public struct QueueTarget: Equatable {
    public var sessionId: String
    public var url: String
    public var mode: QueueMode
    public var batch: BatchConfig?
    public var extrasJson: String
    public var maxAgeMs: Int64
    public var refreshUrl: String?
    public var refreshBodyJson: String?
    public var ended: Bool

    public init(sessionId: String, url: String, mode: QueueMode, batch: BatchConfig?, extrasJson: String, maxAgeMs: Int64, refreshUrl: String?, refreshBodyJson: String?, ended: Bool) {
        self.sessionId = sessionId
        self.url = url
        self.mode = mode
        self.batch = batch
        self.extrasJson = extrasJson
        self.maxAgeMs = maxAgeMs
        self.refreshUrl = refreshUrl
        self.refreshBodyJson = refreshBodyJson
        self.ended = ended
    }
}

public struct QueuedRow: Equatable {
    public var id: Int64
    public var uuid: String
    public var sessionId: String
    public var fixTs: Int64
    public var body: String
    public var attempts: Int
}

public struct QueueStatus: Equatable {
    public var pending: Int
    public var oldestAt: Int64?
    public var sessions: Int

    public init(pending: Int, oldestAt: Int64?, sessions: Int) {
        self.pending = pending
        self.oldestAt = oldestAt
        self.sessions = sessions
    }
}

public struct PurgeResult: Equatable {
    public var rowsDeleted: Int
    public var targetsRemoved: [String]

    public init(rowsDeleted: Int, targetsRemoved: [String]) {
        self.rowsDeleted = rowsDeleted
        self.targetsRemoved = targetsRemoved
    }
}

public struct QueueStoreError: Error, Equatable {
    public var message: String
}

public final class QueueStore {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()

    public init(path: String) throws {
        guard sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(db)
            throw QueueStoreError(message: message)
        }
        sqlite3_busy_timeout(db, 5_000)
        do {
            try exec("PRAGMA journal_mode=WAL")
            try exec("""
            CREATE TABLE IF NOT EXISTS fixes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                session_id TEXT NOT NULL,
                fix_ts INTEGER NOT NULL,
                body TEXT NOT NULL,
                state INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0
            )
            """)
            try exec("CREATE INDEX IF NOT EXISTS fixes_state_id ON fixes (state, id)")
            try exec("""
            CREATE TABLE IF NOT EXISTS targets (
                session_id TEXT PRIMARY KEY,
                url TEXT NOT NULL,
                headers TEXT NOT NULL,
                refresh TEXT,
                batch TEXT,
                extras TEXT NOT NULL,
                mode TEXT NOT NULL,
                max_age_ms INTEGER NOT NULL,
                ended INTEGER NOT NULL DEFAULT 0
            )
            """)
        } catch {
            sqlite3_close(db)
            throw error
        }
    }

    deinit { sqlite3_close(db) }

    public func recoverInFlight() -> Int {
        locked { run("UPDATE fixes SET state = 0 WHERE state = 1", []) }
    }

    public func saveTarget(_ t: QueueTarget) throws {
        try locked {
            let batch = try t.batch.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
            let refresh = try t.refreshUrl.map { url -> String in
                var object: [String: Any] = ["url": url]
                if let body = t.refreshBodyJson { object["body"] = body }
                return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
            }
            let changed = run("""
            INSERT INTO targets (session_id, url, headers, refresh, batch, extras, mode, max_age_ms, ended)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET url = excluded.url, headers = excluded.headers, refresh = excluded.refresh,
                batch = excluded.batch, extras = excluded.extras, mode = excluded.mode, max_age_ms = excluded.max_age_ms, ended = excluded.ended
            """, [.text(t.sessionId), .text(t.url), .text("target.\(t.sessionId)"), refresh.map(Bind.text) ?? .null, batch.map(Bind.text) ?? .null,
                  .text(t.extrasJson), .text(t.mode.rawValue), .int(t.maxAgeMs), .int(t.ended ? 1 : 0)])
            if changed < 0 { throw QueueStoreError(message: lastError()) }
        }
    }

    public func target(_ sessionId: String) -> QueueTarget? {
        locked {
            query("SELECT url, refresh, batch, extras, mode, max_age_ms, ended FROM targets WHERE session_id = ?", [.text(sessionId)]) { st in
                let refresh = Self.column(st, 1).flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
                return QueueTarget(
                    sessionId: sessionId,
                    url: Self.column(st, 0) ?? "",
                    mode: Self.column(st, 4).flatMap(QueueMode.init(rawValue:)) ?? .latest,
                    batch: Self.column(st, 2).flatMap { try? JSONDecoder().decode(BatchConfig.self, from: Data($0.utf8)) },
                    extrasJson: Self.column(st, 3) ?? "{}",
                    maxAgeMs: sqlite3_column_int64(st, 5),
                    refreshUrl: refresh?["url"] as? String,
                    refreshBodyJson: refresh?["body"] as? String,
                    ended: sqlite3_column_int64(st, 6) != 0
                )
            }.first
        }
    }

    public func insert(sessionId: String, uuid: String, fixTs: Int64, body: String, mode: QueueMode, maxRecords: Int) throws -> Int {
        try locked {
            guard run("BEGIN IMMEDIATE", []) >= 0 else { throw QueueStoreError(message: lastError()) }
            var trimmed = 0
            if mode == .latest {
                _ = run("DELETE FROM fixes WHERE session_id = ? AND state = 0", [.text(sessionId)])
            }
            let inserted = run("INSERT INTO fixes (uuid, session_id, fix_ts, body, state, attempts) VALUES (?, ?, ?, ?, 0, 0)",
                               [.text(uuid), .text(sessionId), .int(fixTs), .text(body)])
            if inserted < 0 {
                let message = lastError()
                _ = run("ROLLBACK", [])
                throw QueueStoreError(message: message)
            }
            if mode == .all {
                let total = scalar("SELECT COUNT(*) FROM fixes", []) ?? 0
                let excess = trimCount(rows: Int(total), maxRecords: maxRecords)
                if excess > 0 {
                    trimmed = max(0, run("DELETE FROM fixes WHERE id IN (SELECT id FROM fixes WHERE state = 0 ORDER BY id LIMIT ?)", [.int(Int64(excess))]))
                }
            }
            guard run("COMMIT", []) >= 0 else {
                let message = lastError()
                _ = run("ROLLBACK", [])
                throw QueueStoreError(message: message)
            }
            return trimmed
        }
    }

    public func claim(sessionId: String, limit: Int) -> [QueuedRow] {
        locked {
            guard run("BEGIN IMMEDIATE", []) >= 0 else { return [] }
            var rows = query("SELECT id, uuid, session_id, fix_ts, body, attempts FROM fixes WHERE session_id = ? AND state = 0 ORDER BY id LIMIT ?",
                             [.text(sessionId), .int(Int64(limit))]) { st in
                QueuedRow(id: sqlite3_column_int64(st, 0), uuid: Self.column(st, 1) ?? "", sessionId: Self.column(st, 2) ?? "",
                          fixTs: sqlite3_column_int64(st, 3), body: Self.column(st, 4) ?? "", attempts: Int(sqlite3_column_int64(st, 5)))
            }
            for i in rows.indices {
                _ = run("UPDATE fixes SET state = 1, attempts = attempts + 1 WHERE id = ?", [.int(rows[i].id)])
                rows[i].attempts += 1
            }
            if run("COMMIT", []) < 0 {
                _ = run("ROLLBACK", [])
                return []
            }
            return rows
        }
    }

    public func delete(ids: [Int64]) {
        locked { forEach(ids, "DELETE FROM fixes WHERE id = ?") }
    }

    public func release(ids: [Int64]) {
        locked { forEach(ids, "UPDATE fixes SET state = 0 WHERE id = ?") }
    }

    public func deleteSession(_ sessionId: String) -> Int {
        locked {
            _ = run("BEGIN IMMEDIATE", [])
            let rows = max(0, run("DELETE FROM fixes WHERE session_id = ?", [.text(sessionId)]))
            _ = run("DELETE FROM targets WHERE session_id = ?", [.text(sessionId)])
            _ = run("COMMIT", [])
            return rows
        }
    }

    public func markEnded(_ sessionId: String) {
        locked { _ = run("UPDATE targets SET ended = 1 WHERE session_id = ?", [.text(sessionId)]) }
    }

    public func purge(nowMs: Int64) -> PurgeResult {
        locked {
            _ = run("BEGIN IMMEDIATE", [])
            let aged = max(0, run("""
            DELETE FROM fixes WHERE state = 0 AND session_id IN (SELECT session_id FROM targets WHERE mode = 'all')
            AND ? - fix_ts > (SELECT max_age_ms FROM targets WHERE targets.session_id = fixes.session_id)
            """, [.int(nowMs)]))
            let orphans = max(0, run("DELETE FROM fixes WHERE state = 0 AND session_id NOT IN (SELECT session_id FROM targets)", []))
            let removed = query("SELECT session_id FROM targets WHERE ended = 1 AND session_id NOT IN (SELECT session_id FROM fixes) ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
            for id in removed { _ = run("DELETE FROM targets WHERE session_id = ?", [.text(id)]) }
            _ = run("COMMIT", [])
            return PurgeResult(rowsDeleted: aged + orphans, targetsRemoved: removed)
        }
    }

    public func nextSession(running: String?) -> String? {
        locked {
            if let running, (scalar("SELECT COUNT(*) FROM fixes WHERE session_id = ? AND state = 0", [.text(running)]) ?? 0) > 0 {
                return running
            }
            return query("SELECT session_id FROM fixes WHERE state = 0 ORDER BY id LIMIT 1", []) { Self.column($0, 0) ?? "" }.first
        }
    }

    public func latestPending(sessionId: String) -> QueuedRow? {
        locked {
            query("SELECT id, uuid, session_id, fix_ts, body, attempts FROM fixes WHERE session_id = ? AND state = 0 ORDER BY id DESC LIMIT 1", [.text(sessionId)]) { st in
                QueuedRow(id: sqlite3_column_int64(st, 0), uuid: Self.column(st, 1) ?? "", sessionId: Self.column(st, 2) ?? "",
                          fixTs: sqlite3_column_int64(st, 3), body: Self.column(st, 4) ?? "", attempts: Int(sqlite3_column_int64(st, 5)))
            }.first
        }
    }

    public func status() -> QueueStatus {
        locked {
            query("SELECT COUNT(*), MIN(fix_ts), COUNT(DISTINCT session_id) FROM fixes", []) { st in
                QueueStatus(pending: Int(sqlite3_column_int64(st, 0)),
                            oldestAt: sqlite3_column_type(st, 1) == SQLITE_NULL ? nil : sqlite3_column_int64(st, 1),
                            sessions: Int(sqlite3_column_int64(st, 2)))
            }.first ?? QueueStatus(pending: 0, oldestAt: nil, sessions: 0)
        }
    }

    public func clear() -> [String] {
        locked {
            _ = run("BEGIN IMMEDIATE", [])
            _ = run("DELETE FROM fixes", [])
            let removed = query("SELECT session_id FROM targets WHERE ended = 1 ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
            _ = run("DELETE FROM targets WHERE ended = 1", [])
            _ = run("COMMIT", [])
            return removed
        }
    }

    private enum Bind {
        case text(String)
        case int(Int64)
        case null
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private func lastError() -> String { String(cString: sqlite3_errmsg(db)) }

    private func exec(_ sql: String) throws {
        var err: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(db, sql, nil, nil, &err) == SQLITE_OK else {
            let message = err.map { String(cString: $0) } ?? "exec failed"
            sqlite3_free(err)
            throw QueueStoreError(message: message)
        }
    }

    private func prepare(_ sql: String, _ binds: [Bind]) -> OpaquePointer? {
        var st: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK else { return nil }
        for (i, b) in binds.enumerated() {
            let idx = Int32(i + 1)
            switch b {
            case .text(let s): sqlite3_bind_text(st, idx, s, -1, transient)
            case .int(let v): sqlite3_bind_int64(st, idx, v)
            case .null: sqlite3_bind_null(st, idx)
            }
        }
        return st
    }

    @discardableResult
    private func run(_ sql: String, _ binds: [Bind]) -> Int {
        guard let st = prepare(sql, binds) else { return -1 }
        defer { sqlite3_finalize(st) }
        guard sqlite3_step(st) == SQLITE_DONE else { return -1 }
        return Int(sqlite3_changes(db))
    }

    private func forEach(_ ids: [Int64], _ sql: String) {
        guard !ids.isEmpty else { return }
        _ = run("BEGIN IMMEDIATE", [])
        for id in ids { _ = run(sql, [.int(id)]) }
        _ = run("COMMIT", [])
    }

    private func query<T>(_ sql: String, _ binds: [Bind], _ map: (OpaquePointer) -> T) -> [T] {
        guard let st = prepare(sql, binds) else { return [] }
        defer { sqlite3_finalize(st) }
        var out: [T] = []
        while sqlite3_step(st) == SQLITE_ROW { out.append(map(st)) }
        return out
    }

    private func scalar(_ sql: String, _ binds: [Bind]) -> Int64? {
        query(sql, binds) { sqlite3_column_int64($0, 0) }.first
    }

    private static func column(_ st: OpaquePointer, _ i: Int32) -> String? {
        sqlite3_column_text(st, i).map { String(cString: $0) }
    }
}
