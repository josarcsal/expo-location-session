import CoreLocation
import Foundation
import UIKit

struct LocationSessionError: Error {
    let code: String
    let message: String
}

struct EngineState {
    var running: Bool
    var sessionId: String?
    var startedAtMs: Int64?
    var expiresAtMs: Int64?
    var lastFix: Fix?
    var lastUploadAtMs: Int64?
    var survivesProcessDeath: Bool
    var lastStop: LastStop?
}

final class SessionEngine {
    static let shared = SessionEngine()

    let events = EventBus()
    let store = SessionStore()
    private(set) lazy var permissions = Permissions(store: store)
    let source = LocationSource()
    let queue = QueueDatabase.shared
    let credentials = TargetCredentials()
    private(set) lazy var uploader = Uploader(store: store, events: events, db: queue, credentials: credentials)

    let launchAtMs = nowMs()
    private(set) var config: SessionConfig?
    private var startedAtMs: Int64 = 0
    private(set) var lastFix: Fix?
    private var stopping = false
    private var expiryTimer: Timer?
    var protectedDataObservers: [NSObjectProtocol] = []
    var terminateObserver: NSObjectProtocol?

    private init() {
        DebugLogger.shared.attach(events)
        source.onFixes = { [weak self] in self?.handle($0) }
        source.onAuthorizationChange = { [weak self] in self?.authorizationChanged($0) }
        source.onAccuracyChange = { [weak self] in self?.accuracyChanged($0) }
        _ = NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { [weak self] _ in
            self?.uploader.protectedDataAvailable()
        }
        observeForeground()
        if BackgroundSync.isPermitted {
            _ = NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.submitBackgroundSync()
            }
        }
    }

    func start(_ c: SessionConfig) throws {
        dispatchPrecondition(condition: .onQueue(.main))
        guard UIApplication.shared.applicationState == .active else {
            throw LocationSessionError(code: "NOT_FOREGROUND", message: "start must be called while the app is in the foreground")
        }
        guard permissions.isAuthorized else {
            throw LocationSessionError(code: "PERMISSION_DENIED", message: "Location permission is not granted")
        }
        let now = nowMs()
        if let code = validate(c, nowMs: now, running: config != nil) {
            throw LocationSessionError(code: code, message: validationMessage(code, c))
        }
        guard LocationSource.hasBackgroundLocationMode else {
            throw LocationSessionError(code: "INVALID_CONFIG", message: "missing UIBackgroundModes location")
        }
        do {
            try store.save(c, startedAtMs: now)
            try credentials.save(sessionId: c.id, headers: c.uploadHeaders, refreshHeaders: c.sync.refresh?.headers ?? [:])
        } catch {
            store.wipe()
            credentials.remove(sessionId: c.id)
            throw LocationSessionError(code: "INTERNAL", message: "Could not persist the session: \(error)")
        }
        activate(c, startedAtMs: now, pending: nil, lastUploadAtMs: nil)
        Log.info("lifecycle", "started session \(c.id)", sessionId: c.id)
    }

    func stop(_ reason: StopReason) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config else { return }
        Log.debug("stop", "stop requested", data: ["reason": reason.rawValue, "nowMs": nowMs(), "expiresAtMs": c.expiresAtMs], sessionId: c.id, level: "info")
        teardown(c, reason)
    }

    func teardown(_ c: SessionConfig, _ reason: StopReason) {
        expiryTimer?.invalidate()
        expiryTimer = nil
        source.stop()
        events.clearStickyErrors()
        uploader.reset()
        let action = stopQueueAction(mode: c.sync.mode, reason: reason)
        if action == .markEnded {
            queue.markEnded(c.id)
            Log.debug("queue", "session marked ended", data: ["totalPending": queue.status()?.pending ?? 0, "buffered": queue.buffered], sessionId: c.id)
        } else {
            uploader.discardFallback()
            let deleted = queue.deleteSession(c.id)
            Log.debug("queue", "session rows deleted", data: ["rows": deleted], sessionId: c.id)
            credentials.remove(sessionId: c.id)
            if action == .deleteRowsDropped, deleted > 0 {
                events.emit(.error, ["code": "UPLOAD_DROPPED", "message": "Session rejected; \(deleted) location\(deleted == 1 ? "" : "s") dropped", "sessionId": c.id])
            }
        }
        uploader.evaluateConnectivity()
        store.wipe()
        store.lastStop = LastStop(reason: reason, atMs: nowMs(), sessionId: c.id)
        config = nil
        lastFix = nil
        stopping = false
        Log.info("stop", "stopped session \(c.id) reason=\(reason.rawValue)", sessionId: c.id)
        DebugLogger.shared.onStopping()
        events.emit(.stop, ["reason": reason.rawValue, "sessionId": c.id])
        submitBackgroundSync()
        uploader.drain(budgetMs: nil) { _ in }
    }

    func submitBackgroundSync() {
        guard BackgroundSync.isPermitted else { return }
        BackgroundSync.submitIfNeeded(pending: queue.status()?.pending ?? 0, sessionRunning: config != nil)
    }

    func queueStatus() throws -> QueueStatus {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let status = queue.status(hiding: uploader.hiddenRows()) else {
            throw LocationSessionError(code: "INTERNAL", message: "queue storage is not available")
        }
        return status
    }

    func syncQueue(_ completion: @escaping (Int) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        uploader.drain(budgetMs: nil, completion: completion)
    }

    func clearQueue() {
        dispatchPrecondition(condition: .onQueue(.main))
        uploader.cancelDrain(release: false)
        if config?.sync.mode == .latest {
            uploader.discardFallback()
            store.pendingFix = nil
        }
        guard let removed = queue.clear() else { return }
        removed.forEach { credentials.remove(sessionId: $0) }
        Log.debug("queue", "cleared", data: ["targets": removed.count], sessionId: config?.id)
        uploader.evaluateConnectivity()
    }

    func state() -> EngineState {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config else {
            return EngineState(running: false, survivesProcessDeath: false, lastStop: store.lastStop)
        }
        return EngineState(
            running: true,
            sessionId: c.id,
            startedAtMs: startedAtMs,
            expiresAtMs: c.expiresAtMs,
            lastFix: lastFix,
            lastUploadAtMs: uploader.lastSuccessMs,
            survivesProcessDeath: permissions.hasAlways,
            lastStop: nil
        )
    }

    func activate(_ c: SessionConfig, startedAtMs: Int64, pending: Fix?, lastUploadAtMs: Int64?) {
        config = c
        self.startedAtMs = startedAtMs
        lastFix = pending.flatMap(sanitize)
        stopping = false
        uploader.onStop = { [weak self] in self?.stop($0) }
        uploader.processKeptAlive = { [weak self] in self?.source.backgroundUpdatesActive ?? false }
        saveTarget(c)
        uploader.begin(c, pending: lastFix, lastSuccessMs: lastUploadAtMs)
        startSource(c)
        if source.accuracyAuthorization == .reducedAccuracy { reportReducedAccuracy(c) }
        observeTermination()
        scheduleExpiry(c)
    }

    private func saveTarget(_ c: SessionConfig) {
        let target = QueueTarget(sessionId: c.id, url: c.uploadUrl, mode: c.sync.mode, batch: c.sync.batch, extrasJson: c.uploadExtrasJson, maxAgeMs: c.sync.maxAgeMs, refreshUrl: c.sync.refresh?.url, refreshBodyJson: c.sync.refresh?.bodyJson, ended: false)
        queue.saveTarget(target)
    }

    private func startSource(_ c: SessionConfig) {
        guard !source.start(c) else { return }
        events.emitStickyError(code: "CONFIG_MISSING_BACKGROUND_MODE", message: "UIBackgroundModes does not contain location; the session only updates while the app is in the foreground")
    }

    private func scheduleExpiry(_ c: SessionConfig) {
        expiryTimer?.invalidate()
        let delay = max(0, Double(c.expiresAtMs - nowMs()) / 1000)
        let timer = Timer(timeInterval: delay, repeats: false) { [weak self] _ in
            self?.evaluate(fix: nil)
        }
        RunLoop.main.add(timer, forMode: .common)
        expiryTimer = timer
    }

    private func handle(_ fixes: [Fix]) {
        dispatchPrecondition(condition: .onQueue(.main))
        for fix in fixes {
            guard let c = config, !stopping else {
                logFix(fix, outcome: "dropped", reason: config == nil ? "no session" : "stopping")
                return
            }
            if nowMs() >= c.expiresAtMs || fix.timestampMs >= c.expiresAtMs {
                logFix(fix, outcome: "dropped", reason: "after expiry", sessionId: c.id)
                evaluate(fix: nil)
                return
            }
            guard acceptFix(fix, startedAtMs: startedAtMs) else {
                logFix(fix, outcome: "dropped", reason: "before session start", sessionId: c.id)
                continue
            }
            if isDuplicateFix(fix, of: lastFix) {
                logFix(fix, outcome: "dropped", reason: "duplicate", sessionId: c.id)
                continue
            }
            logFix(fix, outcome: "accepted", reason: nil, sessionId: c.id)
            uploader.put(fix)
            lastFix = fix
            events.emit(.location, locationPayload(fix))
            evaluate(fix: fix)
        }
    }

    func evaluate(fix: Fix?) {
        guard let c = config, !stopping else { return }
        let now = nowMs()
        guard let reason = stopDecision(c, fix: fix, nowMs: now) else {
            if fix != nil { uploader.pump() }
            return
        }
        var inputs: [String: Any] = ["reason": reason.rawValue, "nowMs": now, "expiresAtMs": c.expiresAtMs]
        if let d = c.destination, let f = fix {
            inputs["distanceMeters"] = distanceMeters(latitude: d.latitude, longitude: d.longitude, latitude: f.latitude, longitude: f.longitude)
            inputs["accuracy"] = f.accuracy
            inputs["radiusMeters"] = d.radiusMeters
        }
        Log.debug("stop", "stop decision", data: inputs, sessionId: c.id, level: "info")
        stopping = true
        uploader.flush { [weak self] in
            self?.stop(reason)
        }
    }

    private func logFix(_ fix: Fix, outcome: String, reason: String?, sessionId: String? = nil) {
        var data = locationPayload(fix)
        data["ageMs"] = nowMs() - fix.timestampMs
        data["outcome"] = outcome
        if let reason { data["reason"] = reason }
        Log.debug("location", "fix \(outcome)", data: data, sessionId: sessionId)
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        Log.debug("permission", "authorization changed", data: ["status": status.rawValue, "sessionRunning": config != nil], sessionId: config?.id, level: "info")
        guard let c = config else { return }
        switch status {
        case .denied, .restricted:
            stop(.permissionRevoked)
        case .authorizedWhenInUse:
            source.removeRegions()
            Log.info("permission", "authorization downgraded to whenInUse; session \(c.id) keeps running without relaunch", sessionId: c.id)
        case .authorizedAlways:
            startSource(c)
            Log.info("permission", "authorization upgraded to always; session \(c.id) can relaunch", sessionId: c.id)
        default:
            break
        }
    }

    private func accuracyChanged(_ accuracy: CLAccuracyAuthorization) {
        guard let c = config else { return }
        if accuracy == .reducedAccuracy {
            reportReducedAccuracy(c)
        } else {
            events.clearStickyError("ACCURACY_REDUCED")
            Log.info("permission", "precise location restored for session \(c.id)", sessionId: c.id)
        }
    }

    private func reportReducedAccuracy(_ c: SessionConfig) {
        Log.info("permission", "location accuracy reduced to approximate for session \(c.id)", sessionId: c.id)
        events.emitStickyError(code: "ACCURACY_REDUCED", message: "Precise location is off; fixes are approximate and arrival cannot trigger")
    }

    private func validationMessage(_ code: String, _ c: SessionConfig) -> String {
        if code == "INVALID_CONFIG", let reason = syncInvalidReason(c) { return reason }
        switch code {
        case "ALREADY_RUNNING": return "A session is already running; call stop() first"
        case "EXPIRED": return "expiresAt is in the past"
        default: return "Invalid session config (https upload url, non-empty id, positive radius, valid accuracy)"
        }
    }
}

func locationPayload(_ fix: Fix) -> [String: Any] {
    var payload: [String: Any] = [
        "latitude": fix.latitude,
        "longitude": fix.longitude,
        "accuracy": fix.accuracy,
        "timestamp": fix.timestampMs,
    ]
    if let v = fix.altitude { payload["altitude"] = v }
    if let v = fix.speed { payload["speed"] = v }
    if let v = fix.heading { payload["heading"] = v }
    return payload
}
