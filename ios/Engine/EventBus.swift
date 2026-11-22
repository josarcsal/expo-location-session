import Foundation
import os

final class EventBus {
    enum Event: String {
        case location, upload, stop, error, log
    }

    private let lock = NSLock()
    private var listeners: [Double: (event: Event, callback: (String) -> Void)] = [:]
    private var nextId = 0.0
    private var stickyErrors: [String: String] = [:]

    func add(_ event: Event, _ callback: @escaping (String) -> Void) -> Double {
        lock.lock()
        nextId += 1
        let id = nextId
        listeners[id] = (event, callback)
        let replay = event == .error ? Array(stickyErrors.values) : []
        lock.unlock()
        if !replay.isEmpty {
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.lock.lock()
                let live = self.listeners[id] != nil
                self.lock.unlock()
                if live { replay.forEach(callback) }
            }
        }
        return id
    }

    func emitStickyError(code: String, message: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: ["code": code, "message": message]),
              let json = String(data: data, encoding: .utf8) else { return }
        lock.lock()
        stickyErrors[code] = json
        lock.unlock()
        emit(.error, json: json)
    }

    func clearStickyError(_ code: String) {
        lock.lock()
        stickyErrors[code] = nil
        lock.unlock()
    }

    func clearStickyErrors() {
        lock.lock()
        stickyErrors.removeAll()
        lock.unlock()
    }

    func remove(_ id: Double) {
        lock.lock()
        defer { lock.unlock() }
        listeners[id] = nil
    }

    func hasListeners(_ event: Event) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return listeners.values.contains { $0.event == event }
    }

    func emit(_ event: Event, _ payload: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return }
        emit(event, json: json)
    }

    func emit(_ event: Event, json: String) {
        lock.lock()
        let targets = listeners.values.filter { $0.event == event }.map(\.callback)
        lock.unlock()
        targets.forEach { $0(json) }
    }
}

enum Log {
    private static let logger = Logger(subsystem: "expo-location-session", category: "engine")

    static func info(_ tag: String, _ message: String, data: [String: Any]? = nil, sessionId: String? = nil) {
        logger.info("\(message, privacy: .public)")
        DebugLogger.shared.log("info", tag, message, data: data, sessionId: sessionId)
    }

    static func debug(_ tag: String, _ message: String, data: [String: Any]? = nil, sessionId: String? = nil, level: String = "debug") {
        DebugLogger.shared.log(level, tag, message, data: data, sessionId: sessionId)
    }

    static func upload(status: Int?, outcome: UploadOutcome, data: [String: Any], sessionId: String?) {
        info("upload", "upload status=\(status.map(String.init) ?? "none") outcome=\(outcome)", data: data, sessionId: sessionId)
    }
}
