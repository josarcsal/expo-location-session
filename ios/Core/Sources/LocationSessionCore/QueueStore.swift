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
    public var code: Int32 = SQLITE_ERROR

    public var isCorrupt: Bool {
        code & 0xFF == SQLITE_NOTADB || code & 0xFF == SQLITE_CORRUPT
    }
}

public final class QueueStore {
    private var db: OpaquePointer?
    private let lock = NSRecursiveLock()
    private var transactionError = ""

    public init(path: String) throws {
        let opened = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard opened == SQLITE_OK else {
            let message = db.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            sqlite3_close(db)
            db = nil
            throw QueueStoreError(message: message, code: opened)
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
            db = nil
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
            query("SELECT url, refresh, batch, extras, mode, max_age_ms, ended FROM targets WHERE session_id = ?", [.text(sessionId)]) { st -> QueueTarget? in
                let refresh = Self.column(st, 1).flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
                guard let mode = Self.column(st, 4).flatMap(QueueMode.init(rawValue:)) else { return nil }
                return QueueTarget(
                    sessionId: sessionId,
                    url: Self.column(st, 0) ?? "",
                    mode: mode,
                    batch: Self.column(st, 2).flatMap { try? JSONDecoder().decode(BatchConfig.self, from: Data($0.utf8)) },
                    extrasJson: Self.column(st, 3) ?? "{}",
                    maxAgeMs: sqlite3_column_int64(st, 5),
                    refreshUrl: refresh?["url"] as? String,
                    refreshBodyJson: refresh?["body"] as? String,
                    ended: sqlite3_column_int64(st, 6) != 0
                )
            }.first ?? nil
        }
    }

    public func insert(sessionId: String, uuid: String, fixTs: Int64, body: String, mode: QueueMode, maxRecords: Int) throws -> Int {
        try locked {
            var trimmed = 0
            let committed = transaction {
                if mode == .latest, run("DELETE FROM fixes WHERE session_id = ? AND state = 0", [.text(sessionId)]) < 0 { return false }
                if run("INSERT INTO fixes (uuid, session_id, fix_ts, body, state, attempts) VALUES (?, ?, ?, ?, 0, 0)",
                       [.text(uuid), .text(sessionId), .int(fixTs), .text(body)]) < 0 { return false }
                if mode == .all {
                    let total = scalar("SELECT COUNT(*) FROM fixes", []) ?? 0
                    let excess = trimCount(rows: Int(total), maxRecords: maxRecords)
                    if excess > 0 {
                        let deleted = run("DELETE FROM fixes WHERE id IN (SELECT id FROM fixes WHERE state = 0 ORDER BY id LIMIT ?)", [.int(Int64(excess))])
                        if deleted < 0 { return false }
                        trimmed = deleted
                    }
                }
                return true
            }
            if !committed { throw QueueStoreError(message: transactionError) }
            return trimmed
        }
    }

    public func claim(sessionId: String, limit: Int) -> [QueuedRow] {
        locked {
            var rows: [QueuedRow] = []
            let committed = transaction {
                rows = query("SELECT id, uuid, session_id, fix_ts, body, attempts FROM fixes WHERE session_id = ? AND state = 0 ORDER BY id LIMIT ?",
                             [.text(sessionId), .int(Int64(limit))]) { st in
                    QueuedRow(id: sqlite3_column_int64(st, 0), uuid: Self.column(st, 1) ?? "", sessionId: Self.column(st, 2) ?? "",
                              fixTs: sqlite3_column_int64(st, 3), body: Self.column(st, 4) ?? "", attempts: Int(sqlite3_column_int64(st, 5)))
                }
                for i in rows.indices {
                    if run("UPDATE fixes SET state = 1, attempts = attempts + 1 WHERE id = ?", [.int(rows[i].id)]) < 0 { return false }
                    rows[i].attempts += 1
                }
                return true
            }
            return committed ? rows : []
        }
    }

    @discardableResult
    public func delete(ids: [Int64]) -> Bool {
        locked { updateIds(ids, "DELETE FROM fixes WHERE id IN") }
    }

    @discardableResult
    public func release(ids: [Int64]) -> Bool {
        locked { updateIds(ids, "UPDATE fixes SET state = 0 WHERE id IN") }
    }

    public func existing(ids: [Int64]) -> Set<Int64> {
        locked {
            var found: Set<Int64> = []
            for start in stride(from: 0, to: ids.count, by: 500) {
                let chunk = ids[start..<min(start + 500, ids.count)]
                let marks = Array(repeating: "?", count: chunk.count).joined(separator: ",")
                found.formUnion(query("SELECT id FROM fixes WHERE id IN (\(marks))", chunk.map { Bind.int($0) }) { sqlite3_column_int64($0, 0) })
            }
            return found
        }
    }

    public func deleteSession(_ sessionId: String) -> Int {
        locked {
            var rows = 0
            let committed = transaction {
                let deleted = run("DELETE FROM fixes WHERE session_id = ?", [.text(sessionId)])
                if deleted < 0 || run("DELETE FROM targets WHERE session_id = ?", [.text(sessionId)]) < 0 { return false }
                rows = deleted
                return true
            }
            return committed ? rows : 0
        }
    }

    public func markEnded(_ sessionId: String) {
        locked { _ = run("UPDATE targets SET ended = 1 WHERE session_id = ?", [.text(sessionId)]) }
    }

    public func endLeftovers() -> [String] {
        locked {
            var removed: [String] = []
            let committed = transaction {
                removed = query("SELECT session_id FROM targets WHERE ended = 0 AND mode = 'latest' ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
                for id in removed {
                    if run("DELETE FROM fixes WHERE session_id = ?", [.text(id)]) < 0 || run("DELETE FROM targets WHERE session_id = ?", [.text(id)]) < 0 { return false }
                }
                return run("UPDATE targets SET ended = 1 WHERE ended = 0", []) >= 0
            }
            return committed ? removed : []
        }
    }

    public func purge(nowMs: Int64) -> PurgeResult {
        locked {
            var result = PurgeResult(rowsDeleted: 0, targetsRemoved: [])
            let committed = transaction {
                let aged = run("""
                DELETE FROM fixes WHERE state = 0 AND session_id IN (SELECT session_id FROM targets WHERE mode = 'all')
                AND ? - fix_ts > (SELECT max_age_ms FROM targets WHERE targets.session_id = fixes.session_id)
                """, [.int(nowMs)])
                let unreadable = query("SELECT session_id FROM targets WHERE mode NOT IN ('latest', 'all') ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
                for id in unreadable where run("DELETE FROM targets WHERE session_id = ?", [.text(id)]) < 0 { return false }
                let orphans = run("DELETE FROM fixes WHERE state = 0 AND session_id NOT IN (SELECT session_id FROM targets)", [])
                if aged < 0 || orphans < 0 { return false }
                let removed = query("SELECT session_id FROM targets WHERE ended = 1 AND session_id NOT IN (SELECT session_id FROM fixes) ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
                for id in removed where run("DELETE FROM targets WHERE session_id = ?", [.text(id)]) < 0 { return false }
                result = PurgeResult(rowsDeleted: aged + orphans, targetsRemoved: unreadable + removed)
                return true
            }
            return committed ? result : PurgeResult(rowsDeleted: 0, targetsRemoved: [])
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

    public func status(excluding: Set<Int64> = []) -> QueueStatus {
        locked {
            guard !excluding.isEmpty else {
                return query("SELECT COUNT(*), MIN(fix_ts), COUNT(DISTINCT session_id) FROM fixes", []) { st in
                    QueueStatus(pending: Int(sqlite3_column_int64(st, 0)),
                                oldestAt: sqlite3_column_type(st, 1) == SQLITE_NULL ? nil : sqlite3_column_int64(st, 1),
                                sessions: Int(sqlite3_column_int64(st, 2)))
                }.first ?? QueueStatus(pending: 0, oldestAt: nil, sessions: 0)
            }
            var status = query("SELECT COUNT(*), MIN(fix_ts) FROM fixes WHERE state = 0", []) { st in
                QueueStatus(pending: Int(sqlite3_column_int64(st, 0)),
                            oldestAt: sqlite3_column_type(st, 1) == SQLITE_NULL ? nil : sqlite3_column_int64(st, 1),
                            sessions: 0)
            }.first ?? QueueStatus(pending: 0, oldestAt: nil, sessions: 0)
            var sessions = Set(query("SELECT DISTINCT session_id FROM fixes WHERE state = 0", []) { Self.column($0, 0) ?? "" })
            let inFlight = query("SELECT id, fix_ts, session_id FROM fixes WHERE state = 1", []) { st in
                (id: sqlite3_column_int64(st, 0), fixTs: sqlite3_column_int64(st, 1), sessionId: Self.column(st, 2) ?? "")
            }
            for row in inFlight where !excluding.contains(row.id) {
                status.pending += 1
                status.oldestAt = min(status.oldestAt ?? row.fixTs, row.fixTs)
                sessions.insert(row.sessionId)
            }
            status.sessions = sessions.count
            return status
        }
    }

    public func clear() -> [String] {
        locked {
            var removed: [String] = []
            let committed = transaction {
                if run("DELETE FROM fixes", []) < 0 { return false }
                removed = query("SELECT session_id FROM targets WHERE ended = 1 ORDER BY session_id", []) { Self.column($0, 0) ?? "" }
                return run("DELETE FROM targets WHERE ended = 1", []) >= 0
            }
            return committed ? removed : []
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
        let result = sqlite3_exec(db, sql, nil, nil, &err)
        guard result == SQLITE_OK else {
            let message = err.map { String(cString: $0) } ?? "exec failed"
            sqlite3_free(err)
            throw QueueStoreError(message: message, code: result)
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

    private func transaction(_ body: () -> Bool) -> Bool {
        guard run("BEGIN IMMEDIATE", []) >= 0 else {
            transactionError = lastError()
            return false
        }
        if body() {
            if run("COMMIT", []) >= 0 { return true }
            transactionError = lastError()
        } else {
            transactionError = lastError()
        }
        _ = run("ROLLBACK", [])
        return false
    }

    private func updateIds(_ ids: [Int64], _ prefix: String) -> Bool {
        guard !ids.isEmpty else { return true }
        return transaction {
            for start in stride(from: 0, to: ids.count, by: 500) {
                let chunk = ids[start..<min(start + 500, ids.count)]
                let marks = Array(repeating: "?", count: chunk.count).joined(separator: ",")
                if run("\(prefix) (\(marks))", chunk.map { Bind.int($0) }) < 0 { return false }
            }
            return true
        }
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
