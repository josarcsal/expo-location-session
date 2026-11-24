import Foundation
import UIKit

func nowMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

final class Uploader {
    var onStop: ((StopReason) -> Void)?
    var processKeptAlive: (() -> Bool)?

    private let store: SessionStore
    private let events: EventBus
    private let db: QueueDatabase
    private let credentials: TargetCredentials
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 20
        configuration.urlCache = nil
        let queue = OperationQueue()
        queue.name = "expo-location-session.upload"
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: RefuseRedirects(), delegateQueue: queue)
    }()

    private var config: SessionConfig?
    private(set) var lastSuccessMs: Int64?
    private var attempt = 0
    private var flushId = 0
    private var inFlight: URLSessionDataTask?
    private var inFlightRows: [Int64] = []
    private var refreshing = false
    private var refreshed: Set<String> = []
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var timer: Timer?
    private var watchdog: Timer?
    private var flushDeadline: Timer?
    private var flushCompletion: (() -> Void)?
    private var ownsBatteryMonitoring = false

    init(store: SessionStore, events: EventBus, db: QueueDatabase, credentials: TargetCredentials) {
        self.store = store
        self.events = events
        self.db = db
        self.credentials = credentials
    }

    func begin(_ c: SessionConfig, pending: Fix?, lastSuccessMs: Int64?) {
        dispatchPrecondition(condition: .onQueue(.main))
        reset()
        config = c
        self.lastSuccessMs = lastSuccessMs
        if !UIDevice.current.isBatteryMonitoringEnabled {
            UIDevice.current.isBatteryMonitoringEnabled = true
            ownsBatteryMonitoring = true
        }
        guard c.sync.mode == .latest, let fix = pending.flatMap(sanitized) else { return }
        if let q = db.store(), q.latestPending(sessionId: c.id) != nil { return }
        enqueue(fix, c)
    }

    func reset() {
        dispatchPrecondition(condition: .onQueue(.main))
        flushId += 1
        if !inFlightRows.isEmpty, let c = config { giveBack(inFlightRows, c) }
        inFlightRows = []
        inFlight?.cancel()
        inFlight = nil
        refreshing = false
        refreshed = []
        timer?.invalidate()
        timer = nil
        watchdog?.invalidate()
        watchdog = nil
        flushDeadline?.invalidate()
        flushDeadline = nil
        endBackgroundTask()
        config = nil
        lastSuccessMs = nil
        attempt = 0
        flushCompletion = nil
        if ownsBatteryMonitoring {
            UIDevice.current.isBatteryMonitoringEnabled = false
            ownsBatteryMonitoring = false
        }
    }

    func put(_ fix: Fix) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config else { return }
        let trimmed = enqueue(fix, c)
        store.pendingFix = fix
        if c.sync.mode == .all, trimmed > 0 {
            events.emit(.error, ["code": "QUEUE_TRIMMED", "message": "Queue full; \(trimmed) oldest location\(trimmed == 1 ? "" : "s") dropped", "sessionId": c.id])
        }
    }

    func pump() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config, inFlight == nil, flushCompletion == nil, hasPending(c) else { return }
        refreshed = []
        let now = nowMs()
        if shouldUploadNow(lastSuccessMs: lastSuccessMs, nowMs: now, minIntervalMs: c.uploadMinIntervalMs, bypass: false) {
            send()
        } else if let last = lastSuccessMs {
            let wait = last + c.uploadMinIntervalMs - now
            Log.debug("upload", "throttled", data: ["waitMs": wait, "generation": generation(c), "minIntervalMs": c.uploadMinIntervalMs], sessionId: c.id)
            schedule(afterMs: wait)
        }
    }

    func flush(_ completion: @escaping () -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config, inFlight != nil || hasPending(c) else {
            Log.debug("upload", "flush skipped, nothing pending", sessionId: config?.id)
            completion()
            return
        }
        Log.debug("upload", "flush start", data: ["generation": generation(c), "inFlight": inFlight != nil], sessionId: c.id)
        flushCompletion = completion
        timer?.invalidate()
        timer = nil
        let t = Timer(timeInterval: Double(finalUploadBudgetMs) / 1000, repeats: false) { [weak self] _ in
            self?.flushDeadlineReached()
        }
        RunLoop.main.add(t, forMode: .common)
        flushDeadline = t
        if inFlight == nil {
            refreshed = []
            send()
        }
    }

    @discardableResult
    private func enqueue(_ fix: Fix, _ c: SessionConfig) -> Int {
        let uuid = UUID().uuidString.lowercased()
        let body: String
        switch c.sync.mode {
        case .latest:
            guard let data = try? JSONEncoder().encode(fix) else { return 0 }
            body = String(decoding: data, as: UTF8.self)
        case .all:
            body = buildElement(record: FixRecord(uuid: uuid, sessionId: c.id, fix: fix, battery: battery()), sync: c.sync, extrasJson: c.uploadExtrasJson)
        }
        return db.enqueue(PendingInsert(sessionId: c.id, uuid: uuid, fixTs: fix.timestampMs, body: body, mode: c.sync.mode, maxRecords: c.sync.maxRecords))
    }

    private func generation(_ c: SessionConfig) -> Int64 {
        db.store()?.latestPending(sessionId: c.id)?.id ?? 0
    }

    private func hasPending(_ c: SessionConfig) -> Bool {
        db.store()?.latestPending(sessionId: c.id) != nil
    }

    private func syncPendingFix(_ c: SessionConfig) {
        guard c.sync.mode == .latest else { return }
        store.pendingFix = db.store()?.latestPending(sessionId: c.id).flatMap { try? JSONDecoder().decode(Fix.self, from: Data($0.body.utf8)) }
    }

    private func giveBack(_ ids: [Int64], _ c: SessionConfig) {
        guard let q = db.store() else { return }
        if c.sync.mode == .latest, q.latestPending(sessionId: c.id) != nil {
            q.delete(ids: ids)
        } else {
            q.release(ids: ids)
        }
    }

    private func schedule(afterMs delay: Int64) {
        startTimer(afterMs: delay) { [weak self] in self?.pump() }
    }

    private func scheduleFlushRetry(afterMs delay: Int64) {
        guard let c = config else { return }
        let remaining = c.expiresAtMs - nowMs()
        guard remaining > 0 else {
            completeFlush()
            return
        }
        startTimer(afterMs: min(delay, remaining)) { [weak self] in
            guard let self, let c = self.config else { return }
            if nowMs() >= c.expiresAtMs {
                self.completeFlush()
            } else {
                self.send()
            }
        }
    }

    private func startTimer(afterMs delay: Int64, _ fire: @escaping () -> Void) {
        timer?.invalidate()
        let t = Timer(timeInterval: max(0, Double(delay)) / 1000, repeats: false) { [weak self] _ in
            self?.timer = nil
            fire()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func flushDeadlineReached() {
        flushDeadline = nil
        guard flushCompletion != nil else { return }
        Log.debug("upload", "final upload deadline reached", data: ["pending": config.map(hasPending) ?? false, "inFlight": inFlight != nil], sessionId: config?.id, level: "warn")
        timer?.invalidate()
        timer = nil
        completeFlush()
    }

    private func completeFlush() {
        flushDeadline?.invalidate()
        flushDeadline = nil
        if flushCompletion != nil {
            Log.debug("upload", "flush end", data: ["pending": config.map(hasPending) ?? false], sessionId: config?.id)
        }
        let done = flushCompletion
        flushCompletion = nil
        done?()
    }

    private func continueAfterSend(_ c: SessionConfig) {
        if flushCompletion != nil {
            if hasPending(c) { send() } else { completeFlush() }
        } else if c.sync.mode == .all, hasPending(c) {
            send()
        } else {
            pump()
        }
    }

    private func send() {
        guard let c = config, let q = db.store(), let url = URL(string: c.uploadUrl) else {
            completeFlush()
            return
        }
        timer?.invalidate()
        timer = nil
        flushId += 1
        var rows = q.claim(sessionId: c.id, limit: c.sync.mode == .latest ? 1 : batchLimit(c.sync))
        if c.sync.mode == .latest {
            while let row = rows.first, q.latestPending(sessionId: c.id) != nil {
                q.delete(ids: [row.id])
                Log.debug("queue", "superseded row deleted", data: ["fixTimestamp": row.fixTs], sessionId: c.id)
                rows = q.claim(sessionId: c.id, limit: 1)
            }
        }
        guard !rows.isEmpty else {
            completeFlush()
            return
        }
        var elements: [String] = []
        var ids: [Int64] = []
        var unreadable: [QueuedRow] = []
        for row in rows {
            if let element = element(for: row, c) {
                elements.append(element)
                ids.append(row.id)
            } else {
                unreadable.append(row)
            }
        }
        if !unreadable.isEmpty {
            q.delete(ids: unreadable.map(\.id))
            for row in unreadable {
                Log.debug("queue", "unreadable row deleted", data: ["fixTimestamp": row.fixTs, "attempts": row.attempts], sessionId: c.id, level: "warn")
                events.emit(.error, ["code": "UPLOAD_DROPPED", "message": "Queued location unreadable; location dropped", "sessionId": c.id])
            }
            syncPendingFix(c)
        }
        guard !ids.isEmpty else {
            continueAfterSend(c)
            return
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in credentials.load(sessionId: c.id)?.headers ?? [:] {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let body = buildRequestBody(sessionId: c.id, elements: elements, sync: c.sync, extrasJson: c.uploadExtrasJson)
        request.httpBody = body
        let sentId = flushId
        let startedAt = nowMs()
        Log.debug("upload", "attempt", data: ["generation": ids.last ?? 0, "attempt": attempt, "bytes": body.count, "flush": flushCompletion != nil, "rows": ids.count, "fixTimestamp": rows.first(where: { ids.contains($0.id) })?.fixTs ?? 0], sessionId: c.id)
        beginBackgroundTask()
        let task = session.dataTask(with: request) { [weak self] _, response, error in
            let status = error == nil ? (response as? HTTPURLResponse)?.statusCode : nil
            let failure = error.map { "\(($0 as NSError).domain) \(($0 as NSError).code) \($0.localizedDescription)" }
            DispatchQueue.main.async {
                self?.finish(sentId, status: status, detail: ["latencyMs": nowMs() - startedAt, "bytes": body.count, "error": failure ?? NSNull()])
            }
        }
        inFlight = task
        inFlightRows = ids
        startWatchdog(sentId, startedAt: startedAt)
        task.resume()
    }

    private func element(for row: QueuedRow, _ c: SessionConfig) -> String? {
        switch c.sync.mode {
        case .latest:
            guard let fix = try? JSONDecoder().decode(Fix.self, from: Data(row.body.utf8)) else { return nil }
            return buildElement(record: FixRecord(uuid: row.uuid, sessionId: c.id, fix: fix, battery: battery()), sync: c.sync, extrasJson: c.uploadExtrasJson)
        case .all:
            guard (try? JSONSerialization.jsonObject(with: Data(row.body.utf8))) != nil else { return nil }
            return row.body
        }
    }

    private func startWatchdog(_ sentId: Int, startedAt: Int64) {
        watchdog?.invalidate()
        let t = Timer(timeInterval: 25, repeats: false) { [weak self] _ in
            guard let self, sentId == self.flushId else { return }
            self.watchdog = nil
            self.abandonInFlight(detail: ["latencyMs": nowMs() - startedAt, "error": "watchdog"])
        }
        RunLoop.main.add(t, forMode: .common)
        watchdog = t
    }

    private func abandonInFlight(detail: [String: Any]) {
        flushId += 1
        inFlight?.cancel()
        if refreshing {
            completeRefresh(status: nil, body: Data(), error: detail["error"] as? String)
        } else {
            complete(status: nil, detail: detail)
        }
    }

    private func finish(_ sentId: Int, status: Int?, detail: [String: Any]) {
        guard sentId == flushId else {
            Log.debug("upload", "stale response ignored", data: ["status": status ?? NSNull()])
            return
        }
        complete(status: status, detail: detail)
    }

    private func complete(status: Int?, detail: [String: Any]) {
        let rows = inFlightRows
        inFlight = nil
        inFlightRows = []
        watchdog?.invalidate()
        watchdog = nil
        endBackgroundTask()
        guard let c = config else {
            if !rows.isEmpty { db.store()?.release(ids: rows) }
            return
        }
        let at = nowMs()
        let action = syncAction(status: status, ended: false, refreshConfigured: c.sync.refresh != nil, alreadyRefreshed: refreshed.contains(c.id))
        var payload: [String: Any] = ["ok": action == .deleteSent, "at": at, "sessionId": c.id]
        if let status { payload["status"] = status }
        if c.sync.mode == .all { payload["count"] = rows.count }
        events.emit(.upload, payload)
        var data = detail
        data["rows"] = rows.count
        data["generation"] = rows.last ?? 0
        data["outcome"] = uploadOutcomeName(action)
        if let status { data["status"] = status }
        let retrying: Bool
        switch action {
        case .retry, .dropSession: retrying = true
        default: retrying = false
        }
        if retrying { data["backoffMs"] = backoffMs(attempt: attempt) }
        Log.upload(status: status, outcome: action, data: data, sessionId: c.id)
        DebugLogger.shared.onUploadCompleted()

        switch action {
        case .deleteSent:
            db.store()?.delete(ids: rows)
            syncPendingFix(c)
            lastSuccessMs = at
            store.lastUploadAtMs = at
            attempt = 0
        case .dropSent:
            db.store()?.delete(ids: rows)
            syncPendingFix(c)
            attempt = 0
            let message = rows.count == 1 ? "Server responded \(status ?? 0); location dropped" : "Server responded \(status ?? 0); \(rows.count) locations dropped"
            events.emit(.error, ["code": "UPLOAD_DROPPED", "message": message, "sessionId": c.id])
        case .stop(let reason):
            giveBack(rows, c)
            onStop?(reason)
            return
        case .refreshAndRetry:
            giveBack(rows, c)
            refresh(c)
            return
        case .retry, .dropSession:
            giveBack(rows, c)
            scheduleRetry()
            return
        }
        continueAfterSend(c)
    }

    private func scheduleRetry() {
        let delay = backoffMs(attempt: attempt)
        attempt += 1
        if flushCompletion != nil {
            scheduleFlushRetry(afterMs: delay)
        } else {
            schedule(afterMs: delay)
        }
    }

    private func refresh(_ c: SessionConfig) {
        refreshed.insert(c.id)
        flushId += 1
        guard let r = c.sync.refresh, let url = URL(string: r.url) else {
            completeRefresh(status: nil, body: Data(), error: "invalid refresh url")
            return
        }
        let headers = credentials.load(sessionId: c.id)?.refreshHeaders ?? r.headers
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = refreshRequestBody(bodyJson: r.bodyJson, sessionId: c.id)
        let sentId = flushId
        let startedAt = nowMs()
        Log.debug("auth", "refresh attempt", data: ["headers": headers.keys.sorted()], sessionId: c.id)
        beginBackgroundTask()
        let task = session.dataTask(with: request) { [weak self] data, response, error in
            let status = error == nil ? (response as? HTTPURLResponse)?.statusCode : nil
            let failure = error.map { "\(($0 as NSError).domain) \(($0 as NSError).code) \($0.localizedDescription)" }
            DispatchQueue.main.async {
                guard let self else { return }
                guard sentId == self.flushId else {
                    Log.debug("auth", "stale refresh response ignored", data: ["status": status ?? NSNull()])
                    return
                }
                self.completeRefresh(status: status, body: data ?? Data(), error: failure, latencyMs: nowMs() - startedAt)
            }
        }
        inFlight = task
        refreshing = true
        startWatchdog(sentId, startedAt: startedAt)
        task.resume()
    }

    private func completeRefresh(status: Int?, body: Data, error: String?, latencyMs: Int64? = nil) {
        inFlight = nil
        refreshing = false
        watchdog?.invalidate()
        watchdog = nil
        endBackgroundTask()
        guard let c = config else { return }
        if let result = parseRefreshResponse(status: status, body: body) {
            let refreshHeaders = result.refreshHeaders ?? credentials.load(sessionId: c.id)?.refreshHeaders ?? c.sync.refresh?.headers ?? [:]
            do {
                try credentials.save(sessionId: c.id, headers: result.headers, refreshHeaders: refreshHeaders)
            } catch {
                refreshFailed(c, rejected: false, message: "Could not store refreshed credentials", data: ["error": "\(error)"])
                return
            }
            var data: [String: Any] = ["headers": result.headers.keys.sorted()]
            if let rotated = result.refreshHeaders { data["refreshHeaders"] = rotated.keys.sorted() }
            if let status { data["status"] = status }
            if let latencyMs { data["latencyMs"] = latencyMs }
            Log.debug("auth", "refresh succeeded", data: data, sessionId: c.id)
            send()
            return
        }
        let message = status.map { "Refresh rejected: server responded \($0) without usable headers" } ?? "Refresh transport failure: \(error ?? "no response")"
        refreshFailed(c, rejected: status != nil, message: message, data: ["status": status ?? NSNull(), "latencyMs": latencyMs ?? NSNull(), "error": error ?? NSNull()])
    }

    private func refreshFailed(_ c: SessionConfig, rejected: Bool, message: String, data: [String: Any]) {
        Log.debug("auth", rejected ? "refresh rejected" : "refresh failed", data: data, sessionId: c.id, level: "warn")
        events.emit(.error, ["code": "AUTH_REFRESH_FAILED", "message": message, "sessionId": c.id])
        if rejected, case .stop(let reason) = syncAction(status: 401, ended: false, refreshConfigured: true, alreadyRefreshed: true) {
            onStop?(reason)
            return
        }
        if !rejected { refreshed.remove(c.id) }
        scheduleRetry()
    }

    private func battery() -> Battery? {
        let device = UIDevice.current
        guard device.isBatteryMonitoringEnabled, device.batteryLevel >= 0 else { return nil }
        let level = Double(device.batteryLevel)
        guard level.isFinite else { return nil }
        return Battery(level: level, charging: device.batteryState == .charging || device.batteryState == .full)
    }

    private func beginBackgroundTask() {
        endBackgroundTask()
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "expo-location-session.upload") { [weak self] in
            self?.backgroundTaskExpired()
        }
    }

    private func backgroundTaskExpired() {
        let keptAlive = processKeptAlive?() ?? false
        Log.debug("upload", keptAlive ? "background task expired; background location keeps the upload running" : "background task expired; cancelling in-flight upload", data: ["inFlight": inFlight != nil], sessionId: config?.id, level: "warn")
        if !keptAlive, inFlight != nil {
            abandonInFlight(detail: ["error": "background task expired"])
        }
        endBackgroundTask()
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

final class RefuseRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
