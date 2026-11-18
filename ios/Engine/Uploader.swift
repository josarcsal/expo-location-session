import Foundation
import UIKit

func nowMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

final class Uploader {
    var onStop: ((StopReason) -> Void)?

    private let store: SessionStore
    private let events: EventBus
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 20
        configuration.urlCache = nil
        let queue = OperationQueue()
        queue.name = "expo-location-session.upload"
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: nil, delegateQueue: queue)
    }()

    private var config: SessionConfig?
    private var slot = PendingSlot()
    private var generation = 0
    private(set) var lastSuccessMs: Int64?
    private var attempt = 0
    private var inFlight: URLSessionDataTask?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var timer: Timer?
    private var epoch = 0
    private var flushCompletion: (() -> Void)?
    private var flushTarget = 0
    private var ownsBatteryMonitoring = false

    init(store: SessionStore, events: EventBus) {
        self.store = store
        self.events = events
    }

    func begin(_ c: SessionConfig, pending: Fix?, lastSuccessMs: Int64?) {
        dispatchPrecondition(condition: .onQueue(.main))
        reset()
        config = c
        self.lastSuccessMs = lastSuccessMs
        if let fix = pending.flatMap(sanitized) {
            generation = slot.put(fix)
        }
        if !UIDevice.current.isBatteryMonitoringEnabled {
            UIDevice.current.isBatteryMonitoringEnabled = true
            ownsBatteryMonitoring = true
        }
    }

    func reset() {
        dispatchPrecondition(condition: .onQueue(.main))
        epoch += 1
        inFlight?.cancel()
        inFlight = nil
        timer?.invalidate()
        timer = nil
        endBackgroundTask()
        config = nil
        slot = PendingSlot()
        generation = 0
        lastSuccessMs = nil
        attempt = 0
        flushCompletion = nil
        flushTarget = 0
        if ownsBatteryMonitoring {
            UIDevice.current.isBatteryMonitoringEnabled = false
            ownsBatteryMonitoring = false
        }
    }

    func put(_ fix: Fix) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard config != nil else { return }
        generation = slot.put(fix)
        store.pendingFix = fix
    }

    func pump() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config, inFlight == nil, flushCompletion == nil, slot.fix != nil else { return }
        let now = nowMs()
        if shouldUploadNow(lastSuccessMs: lastSuccessMs, nowMs: now, minIntervalMs: c.uploadMinIntervalMs, bypass: false) {
            send()
        } else if let last = lastSuccessMs {
            schedule(afterMs: last + c.uploadMinIntervalMs - now)
        }
    }

    func flush(_ completion: @escaping () -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard config != nil, inFlight != nil || slot.fix != nil else {
            completion()
            return
        }
        flushCompletion = completion
        flushTarget = generation
        timer?.invalidate()
        timer = nil
        if inFlight == nil { send() }
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

    private func completeFlush() {
        let done = flushCompletion
        flushCompletion = nil
        done?()
    }

    private func send() {
        guard let c = config, let fix = slot.fix, let url = URL(string: c.uploadUrl) else {
            completeFlush()
            return
        }
        timer?.invalidate()
        timer = nil
        let sentGeneration = generation
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in c.uploadHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = buildBody(sessionId: c.id, fix: fix, battery: battery(), extrasJson: c.uploadExtrasJson)
        let sentEpoch = epoch
        beginBackgroundTask()
        let task = session.dataTask(with: request) { [weak self] _, response, error in
            let status = error == nil ? (response as? HTTPURLResponse)?.statusCode : nil
            DispatchQueue.main.async {
                self?.finish(generation: sentGeneration, status: status, epoch: sentEpoch)
            }
        }
        inFlight = task
        task.resume()
    }

    private func finish(generation sent: Int, status: Int?, epoch sentEpoch: Int) {
        guard sentEpoch == epoch else { return }
        inFlight = nil
        endBackgroundTask()
        let at = nowMs()
        let outcome = classify(status: status)
        var payload: [String: Any] = ["ok": outcome == .success, "at": at]
        if let status { payload["status"] = status }
        events.emit(.upload, payload)
        Log.upload(status: status, outcome: outcome)

        switch outcome {
        case .success:
            slot.markSent(generation: sent)
            store.pendingFix = slot.fix
            lastSuccessMs = at
            store.lastUploadAtMs = at
            attempt = 0
        case .stop(let reason):
            onStop?(reason)
            return
        case .drop:
            slot.markSent(generation: sent)
            store.pendingFix = slot.fix
            attempt = 0
            events.emit(.error, ["code": "UPLOAD_DROPPED", "message": "Server responded \(status ?? 0); location dropped"])
        case .retry:
            let delay = backoffMs(attempt: attempt)
            attempt += 1
            if flushCompletion != nil {
                scheduleFlushRetry(afterMs: delay)
            } else {
                schedule(afterMs: delay)
            }
            return
        }

        if flushCompletion != nil {
            if sent >= flushTarget || slot.fix == nil {
                completeFlush()
            } else {
                send()
            }
        } else {
            pump()
        }
    }

    private func battery() -> (level: Double, charging: Bool)? {
        let device = UIDevice.current
        guard device.isBatteryMonitoringEnabled, device.batteryLevel >= 0 else { return nil }
        let level = Double(device.batteryLevel)
        guard level.isFinite else { return nil }
        return (level, device.batteryState == .charging || device.batteryState == .full)
    }

    private func beginBackgroundTask() {
        endBackgroundTask()
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "expo-location-session.upload") { [weak self] in
            self?.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}
