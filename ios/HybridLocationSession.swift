import Foundation
import NitroModules

class HybridLocationSession: HybridLocationSessionSpec {
    private let engine = SessionEngine.shared
    private let lock = NSLock()
    private var listenerIds: Set<Double> = []

    override init() {
        super.init()
        DispatchQueue.main.async { SessionEngine.shared.resumeIfNeeded() }
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
            do {
                try self.engine.start(sessionConfig(config))
                promise.resolve()
            } catch let error as LocationSessionError {
                promise.reject(withError: runtimeError(error))
            } catch {
                promise.reject(withError: RuntimeError.error(withMessage: "INTERNAL: \(error)"))
            }
        }
    }

    func stop() throws -> Promise<Void> {
        onMain { promise in
            self.engine.stop(.stopped)
            promise.resolve()
        }
    }

    func getState() throws -> Promise<NativeSessionState> {
        onMain { promise in promise.resolve(withResult: nativeState(self.engine.state())) }
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
}

private func runtimeError(_ error: LocationSessionError) -> Error {
    RuntimeError.error(withMessage: "\(error.code): \(error.message)")
}

private func milliseconds(_ value: Double) -> Int64? {
    guard value.isFinite, abs(value) < 9.0e15 else { return nil }
    return Int64(value.rounded())
}

private func sessionConfig(_ c: NativeSessionConfig) throws -> SessionConfig {
    guard let expiresAt = milliseconds(c.expiresAt), let minInterval = milliseconds(c.uploadMinIntervalMs) else {
        throw LocationSessionError(code: "INVALID_CONFIG", message: "expiresAt and upload.minIntervalMs must be finite numbers")
    }
    let destination = c.destination.map {
        Destination(latitude: $0.latitude, longitude: $0.longitude, radiusMeters: $0.radiusMeters)
    }
    if let d = destination, !(d.latitude.isFinite && d.longitude.isFinite && abs(d.latitude) <= 90 && abs(d.longitude) <= 180) {
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
        uploadMinIntervalMs: minInterval
    )
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
