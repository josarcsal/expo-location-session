import Foundation
import UIKit

func nowMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

final class Uploader {
    private struct Target {
        let id: String
        let url: String
        let sync: SyncConfig
        let extrasJson: String
        let ended: Bool
    }

    private typealias Waiter = (base: Int, done: (Int) -> Void)

    private enum PassEnd {
        case done, retry, cancelled
    }

    var onStop: ((StopReason) -> Void)?
    var processKeptAlive: (() -> Bool)?

    private let store: SessionStore
    private let events: EventBus
    private let db: QueueDatabase
    private let credentials: TargetCredentials
    private let connectivity = ConnectivityMonitor()
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
    private var inFlightTarget: Target?
    private var inFlightFallback: Int?
    private var fallback = FallbackSlot()
    private var pass: (waiters: [Waiter], requests: Int)?
    private var deferred: [Waiter] = []
    private var dirty = false
    private var drained = 0
    private var drainBudget: Timer?
    private var refreshing = false
    private var refreshStartedAt: Int64 = 0
    private var refreshed: Set<String> = []
    private var refreshHold = false
    private var runningCredentials: (headers: [String: String], refreshHeaders: [String: String])?
    private var queueUnavailableReported = false
    private var bufferTrimReported = false
    private var credentialsFallbackLogged = false
    private var syncSubmitted = false
    private var endedAttempt = 0
    private var endedRetryAtMs: Int64 = 0
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
        fallback.keep(sessionId: c.id)
        config = c
        self.lastSuccessMs = lastSuccessMs
        if !UIDevice.current.isBatteryMonitoringEnabled {
            UIDevice.current.isBatteryMonitoringEnabled = true
            ownsBatteryMonitoring = true
        }
        evaluateConnectivity()
        if c.sync.mode == .latest, let fix = pending.flatMap(sanitize), fallback.next(sessionId: c.id) == nil, db.store()?.latestPending(sessionId: c.id) == nil {
            enqueue(fix, c)
        }
        if dirty { startPass(budgetMs: nil, waiters: []) }
    }

    func reset() {
        dispatchPrecondition(condition: .onQueue(.main))
        flushId += 1
        if !inFlightRows.isEmpty, let t = inFlightTarget { giveBack(inFlightRows, t) }
        inFlightRows = []
        inFlightTarget = nil
        inFlightFallback = nil
        inFlight?.cancel()
        inFlight = nil
        if let p = pass {
            pass = nil
            deferred = p.waiters + deferred
            dirty = true
        }
        drainBudget?.invalidate()
        drainBudget = nil
        refreshing = false
        refreshed = []
        refreshHold = false
        runningCredentials = nil
        queueUnavailableReported = false
        bufferTrimReported = false
        credentialsFallbackLogged = false
        endedAttempt = 0
        endedRetryAtMs = 0
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
        evaluateConnectivity()
    }

    func discardFallback() {
        dispatchPrecondition(condition: .onQueue(.main))
        fallback.keep(sessionId: nil)
    }

    func evaluateConnectivity() {
        dispatchPrecondition(condition: .onQueue(.main))
        if config != nil || (db.status()?.pending ?? 0) > 0 {
            connectivity.start { [weak self] in self?.connectivityRegained() }
        } else {
            connectivity.stop()
        }
    }

    private func connectivityRegained() {
        guard config != nil || (db.status()?.pending ?? 0) > 0 else {
            evaluateConnectivity()
            return
        }
        Log.debug("sync", "connectivity regained", data: ["running": config != nil, "inFlight": inFlight != nil, "flush": flushCompletion != nil, "attempt": attempt], sessionId: config?.id)
        attempt = 0
        endedAttempt = 0
        endedRetryAtMs = 0
        refreshHold = false
        guard let c = config else {
            drain(budgetMs: nil) { _ in }
            return
        }
        guard inFlight == nil else { return }
        timer?.invalidate()
        timer = nil
        if flushCompletion != nil {
            send(running(c))
        } else {
            pump()
        }
    }

    func drain(budgetMs: Int64?, completion: @escaping (Int) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        let waiter: Waiter = (drained, completion)
        guard inFlight == nil, flushCompletion == nil, pass == nil else {
            dirty = true
            deferred.append(waiter)
            return
        }
        startPass(budgetMs: budgetMs, waiters: [waiter])
    }

    func cancelDrain(release: Bool) {
        dispatchPrecondition(condition: .onQueue(.main))
        abandonDrainRequest(release: release)
        if config == nil {
            timer?.invalidate()
            timer = nil
        }
        if pass == nil {
            pass = ([], 0)
        }
        endPass(.cancelled)
    }

    private func startPass(budgetMs: Int64?, waiters: [Waiter]) {
        dirty = false
        pass = (waiters + deferred, 0)
        deferred = []
        if config == nil {
            timer?.invalidate()
            timer = nil
        }
        refreshed = refreshed.filter { $0 == config?.id }
        if let budgetMs {
            let t = Timer(timeInterval: Double(max(0, budgetMs)) / 1000, repeats: false) { [weak self] _ in
                self?.drainBudgetReached()
            }
            RunLoop.main.add(t, forMode: .common)
            drainBudget = t
        }
        prepareQueue()
        db.purge()
        drainNext()
    }

    private func drainNext() {
        guard pass != nil else { return }
        guard flushCompletion == nil, let q = db.store(), let id = q.nextSession(running: config?.id), id != config?.id,
              let t = q.target(id), t.ended else {
            endPass(.done)
            return
        }
        let refresh = t.refreshUrl.map { RefreshConfig(url: $0, bodyJson: t.refreshBodyJson) }
        send(Target(id: id, url: t.url, sync: SyncConfig(mode: t.mode, maxAgeMs: t.maxAgeMs, batch: t.batch, refresh: refresh), extrasJson: t.extrasJson, ended: true))
    }

    private func drainBudgetReached() {
        drainBudget = nil
        guard pass != nil else { return }
        Log.debug("sync", "drain budget reached", data: ["inFlight": inFlight != nil])
        abandonDrainRequest(release: true)
        endPass(.done)
    }

    private func abandonDrainRequest(release: Bool) {
        guard inFlightTarget?.ended == true else { return }
        flushId += 1
        if release {
            inFlight?.cancel()
            db.release(inFlightRows)
        }
        inFlight = nil
        inFlightRows = []
        inFlightTarget = nil
        inFlightFallback = nil
        refreshing = false
        watchdog?.invalidate()
        watchdog = nil
        endBackgroundTask()
    }

    private func endPass(_ end: PassEnd) {
        guard let p = pass else { return }
        pass = nil
        drainBudget?.invalidate()
        drainBudget = nil
        var waiters = p.waiters
        if end != .done {
            dirty = false
            waiters += deferred
            deferred = []
        }
        let total = drained
        if end == .retry, config == nil {
            let delay = backoffMs(attempt: attempt)
            attempt += 1
            Log.debug("sync", "drain retry scheduled", data: ["backoffMs": delay])
            startTimer(afterMs: delay) { [weak self] in self?.drain(budgetMs: nil) { _ in } }
            if BackgroundSync.isPermitted, !syncSubmitted {
                syncSubmitted = true
                BackgroundSync.submitIfNeeded(pending: db.status()?.pending ?? 0, sessionRunning: false)
            }
        } else if end == .retry {
            let delay = backoffMs(attempt: endedAttempt)
            endedAttempt += 1
            endedRetryAtMs = nowMs() + delay
            Log.debug("sync", "drain retry deferred", data: ["backoffMs": delay], sessionId: config?.id)
        } else if end == .done {
            syncSubmitted = false
        }
        if p.requests > 0 { resumeRunning() }
        if dirty, inFlight == nil, flushCompletion == nil {
            startPass(budgetMs: nil, waiters: [])
        } else {
            evaluateConnectivity()
        }
        waiters.forEach { $0.done(total - $0.base) }
    }

    private func resumeRunning() {
        guard let c = config, inFlight == nil, timer == nil else { return }
        if flushCompletion != nil {
            send(running(c))
        } else {
            pump()
        }
    }

    private func drainIfIdle() {
        guard let c = config, inFlight == nil, flushCompletion == nil, pass == nil, !hasPending(c) else { return }
        guard dirty || (nowMs() >= endedRetryAtMs && db.store()?.nextSession(running: nil) != nil) else { return }
        startPass(budgetMs: nil, waiters: [])
    }

    private func running(_ c: SessionConfig) -> Target {
        Target(id: c.id, url: c.uploadUrl, sync: c.sync, extrasJson: c.uploadExtrasJson, ended: false)
    }

    func put(_ raw: Fix) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config else { return }
        guard let fix = sanitize(raw) else {
            Log.debug("queue", "invalid location dropped", data: ["fixTimestamp": raw.timestampMs], sessionId: c.id, level: "warn")
            return
        }
        let trimmed = enqueue(fix, c)
        store.pendingFix = fix
        reportQueueHealth(c, trimmed: trimmed)
    }

    private func prepareQueue() {
        let trimmed = db.prepareSend()
        if let c = config { reportQueueHealth(c, trimmed: trimmed) }
    }

    private func reportQueueHealth(_ c: SessionConfig, trimmed: Int) {
        if db.failing, !queueUnavailableReported {
            queueUnavailableReported = true
            let latest = c.sync.mode == .latest
            Log.debug("queue", latest ? "database unavailable; newest location kept in memory" : "database unavailable; locations buffered in memory", data: ["buffered": db.buffered], sessionId: c.id, level: "warn")
            events.emitStickyError(code: "QUEUE_UNAVAILABLE", message: latest ? "Location queue storage is unavailable; the newest location is uploaded from memory" : "Location queue storage is unavailable; up to 500 locations are kept in memory until it recovers")
        } else if queueUnavailableReported, !db.failing, db.isOpen {
            queueUnavailableReported = false
            Log.debug("queue", "database available again", sessionId: c.id)
            events.clearStickyError("QUEUE_UNAVAILABLE")
        }
        if c.sync.mode == .all, trimmed > 0 {
            events.emit(.error, ["code": "QUEUE_TRIMMED", "message": "Queue full; \(trimmed) oldest location\(trimmed == 1 ? "" : "s") dropped", "sessionId": c.id])
        }
        if db.buffered == 0 { bufferTrimReported = false }
    }

    func pump() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config, inFlight == nil, flushCompletion == nil, !refreshHold, hasPending(c) else { return }
        refreshed = []
        let now = nowMs()
        if shouldUploadNow(lastSuccessMs: lastSuccessMs, nowMs: now, minIntervalMs: c.uploadMinIntervalMs, bypass: false) {
            send(running(c))
        } else if let last = lastSuccessMs {
            let wait = last + c.uploadMinIntervalMs - now
            Log.debug("upload", "throttled", data: ["waitMs": wait, "generation": generation(c), "minIntervalMs": c.uploadMinIntervalMs], sessionId: c.id)
            schedule(afterMs: wait)
        }
    }

    func flush(_ completion: @escaping () -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        if inFlight == nil { prepareQueue() }
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
            send(running(c))
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
        switch db.enqueue(PendingInsert(sessionId: c.id, uuid: uuid, fixTs: fix.timestampMs, body: body, mode: c.sync.mode, maxRecords: c.sync.maxRecords)) {
        case .stored(let trimmed):
            fallback.stored()
            return trimmed
        case .failed:
            let generation = fallback.put(sessionId: c.id, uuid: uuid, fix: fix)
            Log.debug("queue", "kept in memory", data: ["fixTimestamp": fix.timestampMs, "generation": generation], sessionId: c.id)
            return 0
        case .buffered(let dropped):
            if dropped, !bufferTrimReported {
                bufferTrimReported = true
                events.emit(.error, ["code": "QUEUE_TRIMMED", "message": "Queue storage unavailable and memory buffer full; oldest buffered locations dropped", "sessionId": c.id])
            }
            return 0
        }
    }

    private func generation(_ c: SessionConfig) -> Int64 {
        if let f = fallback.next(sessionId: c.id) { return Int64(f.generation) }
        return queued(c.id, c.sync.mode)?.id ?? 0
    }

    private func hasPending(_ c: SessionConfig) -> Bool {
        fallback.next(sessionId: c.id) != nil || queued(c.id, c.sync.mode) != nil
    }

    private func queued(_ sessionId: String, _ mode: QueueMode) -> QueuedRow? {
        guard let row = db.store()?.latestPending(sessionId: sessionId) else { return nil }
        return mode == .latest && fallback.supersedes(sessionId: sessionId, fixTs: row.fixTs) ? nil : row
    }

    private func syncPendingFix(_ t: Target) {
        guard !t.ended, t.sync.mode == .latest else { return }
        store.pendingFix = fallback.next(sessionId: t.id)?.fix ?? queued(t.id, .latest).flatMap { try? JSONDecoder().decode(Fix.self, from: Data($0.body.utf8)) }
    }

    private func giveBack(_ ids: [Int64], _ t: Target) {
        if t.sync.mode == .latest, fallback.next(sessionId: t.id) != nil || db.store()?.latestPending(sessionId: t.id) != nil {
            db.delete(ids)
        } else {
            db.release(ids)
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
                self.send(self.running(c))
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

    private func continueAfterSend(_ t: Target) {
        if t.ended {
            drainNext()
            return
        }
        guard let c = config else { return }
        if flushCompletion != nil {
            if hasPending(c) { send(t) } else { completeFlush() }
        } else if c.sync.mode == .all, hasPending(c) {
            send(t)
        } else {
            pump()
        }
        drainIfIdle()
    }

    private func sendFailed(_ t: Target) {
        if t.ended {
            endPass(.done)
        } else {
            completeFlush()
        }
    }

    private func send(_ t: Target) {
        guard let url = URL(string: t.url) else {
            sendFailed(t)
            return
        }
        prepareQueue()
        if !t.ended, let f = fallback.next(sessionId: t.id) {
            timer?.invalidate()
            timer = nil
            flushId += 1
            let element = buildElement(record: FixRecord(uuid: f.uuid, sessionId: t.id, fix: f.fix, battery: battery()), sync: t.sync, extrasJson: t.extrasJson)
            upload(t, url: url, elements: [element], rows: [], fallback: f.generation, fixTs: f.fix.timestampMs)
            return
        }
        guard let q = db.store() else {
            sendFailed(t)
            return
        }
        if !t.ended {
            timer?.invalidate()
            timer = nil
        }
        flushId += 1
        var rows = q.claim(sessionId: t.id, limit: t.sync.mode == .latest ? 1 : batchLimit(t.sync))
        if t.sync.mode == .latest {
            while let row = rows.first, q.latestPending(sessionId: t.id) != nil || fallback.supersedes(sessionId: t.id, fixTs: row.fixTs) {
                db.delete([row.id])
                Log.debug("queue", "superseded row deleted", data: ["fixTimestamp": row.fixTs], sessionId: t.id)
                rows = q.claim(sessionId: t.id, limit: 1)
            }
        }
        guard !rows.isEmpty else {
            sendFailed(t)
            return
        }
        var elements: [String] = []
        var ids: [Int64] = []
        var unreadable: [QueuedRow] = []
        for row in rows {
            if let element = element(for: row, t) {
                elements.append(element)
                ids.append(row.id)
            } else {
                unreadable.append(row)
            }
        }
        if !unreadable.isEmpty {
            db.delete(unreadable.map(\.id))
            for row in unreadable {
                Log.debug("queue", "unreadable row deleted", data: ["fixTimestamp": row.fixTs, "attempts": row.attempts], sessionId: t.id, level: "warn")
                events.emit(.error, ["code": "UPLOAD_DROPPED", "message": "Queued location unreadable; location dropped", "sessionId": t.id])
            }
            syncPendingFix(t)
        }
        guard !ids.isEmpty else {
            continueAfterSend(t)
            return
        }
        upload(t, url: url, elements: elements, rows: ids, fallback: nil, fixTs: rows.first(where: { ids.contains($0.id) })?.fixTs ?? 0)
    }

    private func upload(_ t: Target, url: URL, elements: [String], rows ids: [Int64], fallback generation: Int?, fixTs: Int64) {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in credentials(for: t)?.headers ?? [:] {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let body = buildRequestBody(sessionId: t.id, elements: elements, sync: t.sync, extrasJson: t.extrasJson)
        request.httpBody = body
        let sentId = flushId
        let startedAt = nowMs()
        var data: [String: Any] = ["generation": generation.map(Int64.init) ?? ids.last ?? 0, "bytes": body.count, "flush": flushCompletion != nil, "rows": ids.count, "fixTimestamp": fixTs]
        if generation != nil { data["memory"] = true }
        if ownsAttempt(t) { data["attempt"] = attempt }
        if t.ended {
            data["ended"] = true
            pass?.requests += 1
        }
        Log.debug("upload", "attempt", data: data, sessionId: t.id)
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
        inFlightTarget = t
        inFlightFallback = generation
        startWatchdog(sentId, startedAt: startedAt)
        task.resume()
    }

    private func element(for row: QueuedRow, _ t: Target) -> String? {
        switch t.sync.mode {
        case .latest:
            guard let fix = try? JSONDecoder().decode(Fix.self, from: Data(row.body.utf8)) else { return nil }
            return buildElement(record: FixRecord(uuid: row.uuid, sessionId: t.id, fix: fix, battery: battery()), sync: t.sync, extrasJson: t.extrasJson)
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
            completeRefresh(status: nil, body: Data(), error: detail["error"] as? String, latencyMs: nowMs() - refreshStartedAt)
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
        let target = inFlightTarget
        let sentFallback = inFlightFallback
        inFlight = nil
        inFlightRows = []
        inFlightTarget = nil
        inFlightFallback = nil
        watchdog?.invalidate()
        watchdog = nil
        endBackgroundTask()
        guard let t = target, t.ended || t.id == config?.id else {
            db.release(rows)
            return
        }
        let at = nowMs()
        let action = syncAction(status: status, ended: t.ended, refreshConfigured: t.sync.refresh != nil, alreadyRefreshed: refreshed.contains(t.id))
        var payload: [String: Any] = ["ok": action == .deleteSent, "at": at, "sessionId": t.id]
        if let status { payload["status"] = status }
        if t.sync.mode == .all { payload["count"] = rows.count }
        events.emit(.upload, payload)
        var data = detail
        data["rows"] = rows.count
        data["generation"] = sentFallback.map(Int64.init) ?? rows.last ?? 0
        if sentFallback != nil { data["memory"] = true }
        data["outcome"] = uploadOutcomeName(action)
        if let status { data["status"] = status }
        if action == .retry, ownsAttempt(t) { data["backoffMs"] = backoffMs(attempt: attempt) }
        Log.upload(status: status, outcome: action, data: data, sessionId: t.id)
        DebugLogger.shared.onUploadCompleted()

        switch action {
        case .deleteSent:
            db.delete(rows)
            if let sentFallback { fallback.markSent(generation: sentFallback) }
            if ownsAttempt(t) { attempt = 0 }
            if !t.ended { refreshHold = false }
            if t.ended {
                drained += rows.count
                endedAttempt = 0
                endedRetryAtMs = 0
            } else {
                syncPendingFix(t)
                lastSuccessMs = at
                store.lastUploadAtMs = at
            }
        case .dropSent:
            db.delete(rows)
            if let sentFallback { fallback.markSent(generation: sentFallback) }
            syncPendingFix(t)
            if ownsAttempt(t) { attempt = 0 }
            let dropped = sentFallback == nil ? rows.count : 1
            let message = dropped == 1 ? "Server responded \(status ?? 0); location dropped" : "Server responded \(status ?? 0); \(dropped) locations dropped"
            events.emit(.error, ["code": "UPLOAD_DROPPED", "message": message, "sessionId": t.id])
        case .dropSession:
            let deleted = db.deleteSession(t.id)
            credentials.remove(sessionId: t.id)
            if ownsAttempt(t) { attempt = 0 }
            Log.debug("queue", "session rows deleted", data: ["rows": deleted, "status": status ?? 0], sessionId: t.id)
            events.emit(.error, ["code": "UPLOAD_DROPPED", "message": "Server responded \(status ?? 0); \(deleted) location\(deleted == 1 ? "" : "s") of the ended session dropped", "sessionId": t.id])
        case .stop(let reason):
            giveBack(rows, t)
            onStop?(reason)
            return
        case .refreshAndRetry:
            giveBack(rows, t)
            refresh(t)
            return
        case .retry:
            giveBack(rows, t)
            retryLater(t)
            return
        }
        if !t.ended { resolveDeferredIfBlocked() }
        continueAfterSend(t)
    }

    private func ownsAttempt(_ t: Target) -> Bool {
        !t.ended || config == nil
    }

    private func retryLater(_ t: Target) {
        if t.ended {
            endPass(.retry)
        } else {
            resolveDeferredIfBlocked()
            scheduleRetry()
        }
    }

    private func resolveDeferredIfBlocked() {
        guard !deferred.isEmpty, let c = config, hasPending(c) else { return }
        let waiters = deferred
        deferred = []
        let total = drained
        waiters.forEach { $0.done(total - $0.base) }
    }

    private func scheduleRetry() {
        let delay = backoffMs(attempt: attempt)
        attempt += 1
        if flushCompletion != nil {
            scheduleFlushRetry(afterMs: delay)
        } else {
            startTimer(afterMs: delay) { [weak self] in
                self?.refreshHold = false
                self?.pump()
            }
        }
    }

    private func credentials(for t: Target) -> (headers: [String: String], refreshHeaders: [String: String])? {
        if t.ended { return credentials.load(sessionId: t.id) }
        if let runningCredentials { return runningCredentials }
        if let loaded = credentials.load(sessionId: t.id) {
            runningCredentials = loaded
            return loaded
        }
        guard let c = config else { return nil }
        if !credentialsFallbackLogged {
            credentialsFallbackLogged = true
            Log.debug("auth", "stored credentials missing; using session config headers", sessionId: t.id, level: "warn")
        }
        return (c.uploadHeaders, c.sync.refresh?.headers ?? [:])
    }

    private func refresh(_ t: Target) {
        refreshed.insert(t.id)
        flushId += 1
        inFlightTarget = t
        guard let r = t.sync.refresh, let url = URL(string: r.url) else {
            completeRefresh(status: nil, body: Data(), error: "invalid refresh url")
            return
        }
        let headers = credentials(for: t)?.refreshHeaders ?? r.headers
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = refreshRequestBody(bodyJson: r.bodyJson, sessionId: t.id)
        let sentId = flushId
        let startedAt = nowMs()
        Log.debug("auth", "refresh attempt", data: ["headers": headers.keys.sorted()], sessionId: t.id)
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
        refreshStartedAt = startedAt
        startWatchdog(sentId, startedAt: startedAt)
        task.resume()
    }

    private func completeRefresh(status: Int?, body: Data, error: String?, latencyMs: Int64? = nil) {
        let target = inFlightTarget
        inFlight = nil
        inFlightTarget = nil
        refreshing = false
        watchdog?.invalidate()
        watchdog = nil
        endBackgroundTask()
        guard let t = target, t.ended || t.id == config?.id else { return }
        if let result = parseRefreshResponse(status: status, body: body) {
            let refreshHeaders = result.refreshHeaders ?? credentials(for: t)?.refreshHeaders ?? t.sync.refresh?.headers ?? [:]
            do {
                try credentials.save(sessionId: t.id, headers: result.headers, refreshHeaders: refreshHeaders)
                if !t.ended { runningCredentials = (result.headers, refreshHeaders) }
            } catch {
                refreshFailed(t, rejected: false, message: "Could not store refreshed credentials", data: ["error": "\(error)"])
                return
            }
            var data: [String: Any] = ["headers": result.headers.keys.sorted()]
            if let rotated = result.refreshHeaders { data["refreshHeaders"] = rotated.keys.sorted() }
            if let status { data["status"] = status }
            if let latencyMs { data["latencyMs"] = latencyMs }
            Log.debug("auth", "refresh succeeded", data: data, sessionId: t.id)
            send(t)
            return
        }
        let rejected = status.map { !(300..<400).contains($0) } ?? false
        let message: String
        if let status {
            message = rejected ? "Refresh rejected: server responded \(status) without usable headers" : "Refresh transport failure: redirect \(status) refused"
        } else {
            message = "Refresh transport failure: no response"
        }
        refreshFailed(t, rejected: rejected, message: message, data: ["status": status ?? NSNull(), "latencyMs": latencyMs ?? NSNull(), "error": error ?? NSNull()])
    }

    private func refreshFailed(_ t: Target, rejected: Bool, message: String, data: [String: Any]) {
        Log.debug("auth", rejected ? "refresh rejected" : "refresh failed", data: data, sessionId: t.id, level: "warn")
        events.emit(.error, ["code": "AUTH_REFRESH_FAILED", "message": message, "sessionId": t.id])
        if rejected, case .stop(let reason) = syncAction(status: 401, ended: t.ended, refreshConfigured: true, alreadyRefreshed: true) {
            onStop?(reason)
            return
        }
        if !rejected {
            refreshed.remove(t.id)
            if !t.ended { refreshHold = true }
        }
        retryLater(t)
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
        Log.debug("upload", keptAlive ? "background task expired; background location keeps the upload running" : "background task expired; cancelling in-flight upload", data: ["inFlight": inFlight != nil], sessionId: inFlightTarget?.id ?? config?.id, level: "warn")
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
