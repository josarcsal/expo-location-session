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
    private let source = LocationSource()
    private lazy var uploader = Uploader(store: store, events: events)

    private var config: SessionConfig?
    private var startedAtMs: Int64 = 0
    private var lastFix: Fix?
    private var stopping = false
    private var expiryTimer: Timer?

    private init() {
        source.onFixes = { [weak self] in self?.handle($0) }
        source.onAuthorizationChange = { [weak self] in self?.authorizationChanged($0) }
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
            throw LocationSessionError(code: code, message: validationMessage(code))
        }
        guard LocationSource.hasBackgroundLocationMode else {
            throw LocationSessionError(code: "INVALID_CONFIG", message: "missing UIBackgroundModes location")
        }
        do {
            try store.save(c, startedAtMs: now)
        } catch {
            store.wipe()
            throw LocationSessionError(code: "INTERNAL", message: "Could not persist the session: \(error)")
        }
        activate(c, startedAtMs: now, pending: nil, lastUploadAtMs: nil)
        Log.info("started session \(c.id)")
    }

    func stop(_ reason: StopReason) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config else { return }
        teardown(c, reason)
    }

    private func teardown(_ c: SessionConfig, _ reason: StopReason) {
        expiryTimer?.invalidate()
        expiryTimer = nil
        source.stop()
        uploader.reset()
        store.wipe()
        store.lastStop = LastStop(reason: reason, atMs: nowMs(), sessionId: c.id)
        config = nil
        lastFix = nil
        stopping = false
        Log.info("stopped session \(c.id) reason=\(reason.rawValue)")
        events.emit(.stop, ["reason": reason.rawValue, "sessionId": c.id])
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

    func resumeIfNeeded() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard config == nil, UIApplication.shared.isProtectedDataAvailable else { return }
        switch store.load() {
        case .none, .unavailable:
            return
        case .corrupt:
            Log.info("discarding unreadable persisted session")
            store.wipe()
            source.stop()
        case let .session(c, startedAt):
            if nowMs() >= c.expiresAtMs {
                teardown(c, .expired)
            } else if !permissions.isAuthorized {
                teardown(c, .permissionRevoked)
            } else {
                activate(c, startedAtMs: startedAt, pending: store.pendingFix, lastUploadAtMs: store.lastUploadAtMs)
                Log.info("resumed session \(c.id)")
                uploader.pump()
            }
        }
    }

    private func activate(_ c: SessionConfig, startedAtMs: Int64, pending: Fix?, lastUploadAtMs: Int64?) {
        config = c
        self.startedAtMs = startedAtMs
        lastFix = pending.flatMap(sanitized)
        stopping = false
        uploader.onStop = { [weak self] in self?.stop($0) }
        uploader.begin(c, pending: lastFix, lastSuccessMs: lastUploadAtMs)
        source.start(c)
        scheduleExpiry(c)
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
            guard config != nil, !stopping else { return }
            guard acceptFix(fix, startedAtMs: startedAtMs) else { continue }
            uploader.put(fix)
            lastFix = fix
            events.emit(.location, locationPayload(fix))
            evaluate(fix: fix)
        }
    }

    private func evaluate(fix: Fix?) {
        guard let c = config, !stopping else { return }
        guard let reason = stopDecision(c, fix: fix, nowMs: nowMs()) else {
            if fix != nil { uploader.pump() }
            return
        }
        stopping = true
        uploader.flush { [weak self] in
            self?.stop(reason)
        }
    }

    private func authorizationChanged(_ status: CLAuthorizationStatus) {
        guard config != nil else { return }
        if status == .denied || status == .restricted {
            stop(.permissionRevoked)
        }
    }

    private func validationMessage(_ code: String) -> String {
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
