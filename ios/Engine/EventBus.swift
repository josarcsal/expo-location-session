import Foundation
import os

final class EventBus {
    enum Event: String {
        case location, upload, stop, error
    }

    private let lock = NSLock()
    private var listeners: [Double: (event: Event, callback: (String) -> Void)] = [:]
    private var nextId = 0.0

    func add(_ event: Event, _ callback: @escaping (String) -> Void) -> Double {
        lock.lock()
        defer { lock.unlock() }
        nextId += 1
        listeners[nextId] = (event, callback)
        return nextId
    }

    func remove(_ id: Double) {
        lock.lock()
        defer { lock.unlock() }
        listeners[id] = nil
    }

    func emit(_ event: Event, _ payload: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return }
        lock.lock()
        let targets = listeners.values.filter { $0.event == event }.map(\.callback)
        lock.unlock()
        targets.forEach { $0(json) }
    }
}

enum Log {
    private static let logger = Logger(subsystem: "expo-location-session", category: "engine")

    static func info(_ message: String) {
        logger.info("\(message, privacy: .public)")
    }

    static func upload(status: Int?, outcome: UploadOutcome) {
        info("upload status=\(status.map(String.init) ?? "none") outcome=\(outcome)")
    }
}
