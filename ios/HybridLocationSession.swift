import Foundation
import NitroModules
import UIKit

class HybridLocationSession: HybridLocationSessionSpec {
    private let engine = SessionEngine.shared
    private let lock = NSLock()
    private var listenerIds: Set<Double> = []

    override init() {
        super.init()
        DispatchQueue.main.async {
            SessionEngine.shared.primeOnMain()
            EngineQueue.shared.async { SessionEngine.shared.resumeIfNeeded() }
        }
    }

    deinit {
        lock.lock()
        let ids = listenerIds
        lock.unlock()
        ids.forEach { SessionEngine.shared.events.remove($0) }
    }

    func getPermissionStatus() throws -> Promise<NativePermissionStatus> {
        onMain { promise in promise.resolve(withResult: self.engine.permissions.status()) }
    }

    func requestPermission(level: PermissionLevel) throws -> Promise<NativePermissionStatus> {
        onMain { promise in
            self.engine.permissions.request(always: level == .always) { promise.resolve(withResult: $0) }
        }
    }

    func getCurrentPosition(accuracy: Accuracy, timeoutMs: Double, maxAgeMs: Double) throws -> Promise<NativeLocation> {
        onMain { promise in
            OneShotLocator.locate(accuracy: accuracy.stringValue, timeoutMs: timeoutMs, maxAgeMs: maxAgeMs) { result in
                switch result {
                case let .success(fix): promise.resolve(withResult: nativeLocation(fix))
                case let .failure(error): promise.reject(withError: runtimeError(error))
                }
            }
        }
    }

    func start(config: NativeSessionConfig) throws -> Promise<Void> {
        onMain { promise in
            self.engine.primeOnMain()
            let appActive = UIApplication.shared.applicationState == .active
            EngineQueue.shared.async {
                do {
                    try self.engine.start(sessionConfig(config), appActive: appActive)
                    promise.resolve()
                } catch let error as LocationSessionError {
                    promise.reject(withError: runtimeError(error))
                } catch {
                    promise.reject(withError: RuntimeError.error(withMessage: "INTERNAL: \(error)"))
                }
            }
        }
    }

    func stop() throws -> Promise<Void> {
        onEngine { promise in
            self.engine.stop(.stopped)
            promise.resolve()
        }
    }

    func getState() throws -> Promise<NativeSessionState> {
        onEngine { promise in promise.resolve(withResult: nativeState(self.engine.state())) }
    }

    func configureDebug(options: NativeDebugOptions) throws -> Promise<Void> {
        onMain { promise in
            do {
                try DebugLogger.shared.configure(debugOptions(options))
                promise.resolve()
            } catch let error as LocationSessionError {
                promise.reject(withError: runtimeError(error))
            } catch {
                promise.reject(withError: RuntimeError.error(withMessage: "INTERNAL: \(error)"))
            }
        }
    }

    func getDebugLog(limit: Double) throws -> Promise<String> {
        offMain { promise in
            guard limit.isFinite, limit >= 0, limit <= Double(Int32.max) else {
                promise.reject(withError: RuntimeError.error(withMessage: "INVALID_CONFIG: limit must be a non-negative number"))
                return
            }
            promise.resolve(withResult: DebugLogger.shared.read(limit: Int(limit)))
        }
    }

    func clearDebugLog() throws -> Promise<Void> {
        offMain { promise in
            DebugLogger.shared.clear()
            promise.resolve()
        }
    }

    func flushDebugLog() throws -> Promise<Double> {
        let promise = Promise<Double>()
        DebugLogger.shared.flush { promise.resolve(withResult: Double($0)) }
        return promise
    }

    func getDebugInstallId() throws -> Promise<String> {
        offMain { promise in
            if let id = DebugLogger.shared.installId() {
                promise.resolve(withResult: id)
            } else {
                promise.reject(withError: RuntimeError.error(withMessage: "INTERNAL: Debug storage is not available"))
            }
        }
    }

    func getQueueStatus() throws -> Promise<NativeQueueStatus> {
        onEngine { promise in
            do {
                let status = try self.engine.queueStatus()
                promise.resolve(withResult: NativeQueueStatus(pending: Double(status.pending), oldestAt: status.oldestAt.map(Double.init), sessions: Double(status.sessions)))
            } catch let error as LocationSessionError {
                promise.reject(withError: runtimeError(error))
            } catch {
                promise.reject(withError: RuntimeError.error(withMessage: "INTERNAL: \(error)"))
            }
        }
    }

    func syncQueue() throws -> Promise<Double> {
        onEngine { promise in
            self.engine.syncQueue { promise.resolve(withResult: Double($0)) }
        }
    }

    func clearQueue() throws -> Promise<Void> {
        onEngine { promise in
            self.engine.clearQueue()
            promise.resolve()
        }
    }

    func addListener(event: SessionEvent, callback: @escaping (String) -> Void) throws -> Double {
        guard let kind = EventBus.Event(rawValue: event.stringValue) else {
            throw RuntimeError.error(withMessage: "INVALID_EVENT: \(event.stringValue)")
        }
        let id = engine.events.add(kind, callback)
        lock.lock()
        listenerIds.insert(id)
        lock.unlock()
        return id
    }

    func removeListener(id: Double) throws {
        engine.events.remove(id)
        lock.lock()
        listenerIds.remove(id)
        lock.unlock()
    }

    private func onMain<T>(_ body: @escaping (Promise<T>) -> Void) -> Promise<T> {
        let promise = Promise<T>()
        DispatchQueue.main.async { body(promise) }
        return promise
    }

    private func onEngine<T>(_ body: @escaping (Promise<T>) -> Void) -> Promise<T> {
        let promise = Promise<T>()
        DispatchQueue.main.async { EngineQueue.shared.async { body(promise) } }
        return promise
    }

    private func offMain<T>(_ body: @escaping (Promise<T>) -> Void) -> Promise<T> {
        let promise = Promise<T>()
        DispatchQueue.global(qos: .userInitiated).async { body(promise) }
        return promise
    }
}

private func runtimeError(_ error: LocationSessionError) -> Error {
    RuntimeError.error(withMessage: "\(error.code): \(error.message)")
}

private func milliseconds(_ value: Double) -> Int64? {
    guard value.isFinite, abs(value) < 9.0e15 else { return nil }
    return Int64(value.rounded())
}

private func syncConfig(_ c: NativeSessionConfig) throws -> SyncConfig {
    let mode: QueueMode
    switch c.uploadQueueMode {
    case .latest: mode = .latest
    case .all: mode = .all
    }
    do {
        return try makeSyncConfig(
            mode: mode,
            maxRecords: c.uploadQueueMaxRecords,
            maxAgeMs: c.uploadQueueMaxAgeMs,
            batchMaxSize: c.uploadBatchMaxSize,
            batchRootProperty: c.uploadBatchRootProperty,
            templateJson: c.uploadBodyTemplateJson,
            refreshUrl: c.uploadRefreshUrl,
            refreshHeaders: c.uploadRefreshHeaders,
            refreshBodyJson: c.uploadRefreshBodyJson
        )
    } catch let error as InvalidConfig {
        throw LocationSessionError(code: "INVALID_CONFIG", message: error.reason)
    }
}

private func sessionConfig(_ c: NativeSessionConfig) throws -> SessionConfig {
    guard let expiresAt = milliseconds(c.expiresAt), let minInterval = milliseconds(c.uploadMinIntervalMs), c.distanceFilterMeters.isFinite else {
        throw LocationSessionError(code: "INVALID_CONFIG", message: "expiresAt, distanceFilterMeters and upload.minIntervalMs must be finite numbers")
    }
    let destination = c.destination.map {
        Destination(latitude: $0.latitude, longitude: $0.longitude, radiusMeters: $0.radiusMeters)
    }
    if let d = destination, !(d.latitude.isFinite && d.longitude.isFinite && d.radiusMeters.isFinite && abs(d.latitude) <= 90 && abs(d.longitude) <= 180) {
        throw LocationSessionError(code: "INVALID_CONFIG", message: "destination coordinates are invalid")
    }
    return SessionConfig(
        id: c.id,
        expiresAtMs: expiresAt,
        destination: destination,
        accuracy: c.accuracy.stringValue,
        distanceFilterMeters: c.distanceFilterMeters,
        uploadUrl: c.uploadUrl,
        uploadHeaders: c.uploadHeaders,
        uploadExtrasJson: c.uploadExtrasJson,
        uploadMinIntervalMs: minInterval,
        sync: try syncConfig(c)
    )
}

private func debugOptions(_ o: NativeDebugOptions) throws -> DebugOptions {
    guard let flush = milliseconds(o.flushIntervalMs), let maxBytes = milliseconds(o.maxFileBytes) else {
        throw LocationSessionError(code: "INVALID_CONFIG", message: "flushIntervalMs and maxFileBytes must be finite numbers")
    }
    return DebugOptions(enabled: o.enabled, url: o.url, headers: o.headers, flushIntervalMs: flush, maxFileBytes: maxBytes)
}

private func nativeLocation(_ f: Fix) -> NativeLocation {
    NativeLocation(
        latitude: f.latitude,
        longitude: f.longitude,
        accuracy: f.accuracy,
        altitude: f.altitude,
        speed: f.speed,
        heading: f.heading,
        timestamp: Double(f.timestampMs)
    )
}

private func nativeState(_ s: EngineState) -> NativeSessionState {
    if s.running {
        return NativeSessionState(
            status: .running,
            sessionId: s.sessionId,
            startedAt: s.startedAtMs.map(Double.init),
            expiresAt: s.expiresAtMs.map(Double.init),
            lastLocation: s.lastFix.map(nativeLocation),
            lastUploadAt: s.lastUploadAtMs.map(Double.init),
            survivesProcessDeath: s.survivesProcessDeath,
            lastStopReason: nil,
            lastStopAt: nil,
            lastStopSessionId: nil
        )
    }
    return NativeSessionState(
        status: .idle,
        sessionId: nil,
        startedAt: nil,
        expiresAt: nil,
        lastLocation: nil,
        lastUploadAt: nil,
        survivesProcessDeath: nil,
        lastStopReason: s.lastStop?.reason.rawValue,
        lastStopAt: s.lastStop.map { Double($0.atMs) },
        lastStopSessionId: s.lastStop?.sessionId
    )
}
