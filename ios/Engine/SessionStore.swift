import Foundation
import Security

struct LastStop: Codable, Equatable {
    var reason: StopReason
    var atMs: Int64
    var sessionId: String
}

enum StoredSession {
    case none
    case unavailable
    case corrupt
    case session(SessionConfig, startedAtMs: Int64)
}

enum KeychainError: Error {
    case status(OSStatus)
}

final class SessionStore {
    private let service = "expo-location-session"
    private let account = "session"
    private let defaults = UserDefaults(suiteName: "expo-location-session") ?? .standard

    private enum Key {
        static let startedAtMs = "startedAtMs"
        static let pendingFix = "pendingFix"
        static let lastUploadAtMs = "lastUploadAtMs"
        static let lastStop = "lastStop"
        static let alwaysRequested = "alwaysRequested"
    }

    func save(_ config: SessionConfig, startedAtMs: Int64) throws {
        let data = try JSONEncoder().encode(config)
        try writeKeychain(data)
        defaults.set(NSNumber(value: startedAtMs), forKey: Key.startedAtMs)
        defaults.removeObject(forKey: Key.pendingFix)
        defaults.removeObject(forKey: Key.lastUploadAtMs)
        Log.debug("store", "session persisted", data: ["startedAtMs": startedAtMs, "expiresAtMs": config.expiresAtMs], sessionId: config.id, level: "info")
    }

    func load(protectedDataAvailable: Bool) -> StoredSession {
        let result = read(protectedDataAvailable: protectedDataAvailable)
        switch result {
        case .none: Log.debug("store", "no persisted session", data: ["protectedData": protectedDataAvailable])
        case .unavailable: Log.debug("store", "persisted session unavailable", data: ["protectedData": protectedDataAvailable], level: "warn")
        case .corrupt: Log.debug("store", "persisted session corrupt", data: ["protectedData": protectedDataAvailable], level: "warn")
        case let .session(c, startedAt): Log.debug("store", "persisted session loaded", data: ["startedAtMs": startedAt, "expiresAtMs": c.expiresAtMs], sessionId: c.id, level: "info")
        }
        return result
    }

    private func read(protectedDataAvailable: Bool) -> StoredSession {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            break
        case errSecItemNotFound:
            return defaults.object(forKey: Key.startedAtMs) == nil ? .none : .corrupt
        default:
            Log.debug("store", "keychain read failed", data: ["status": Int(status)], level: "warn")
            return .unavailable
        }
        guard let data = item as? Data,
              let config = try? JSONDecoder().decode(SessionConfig.self, from: data) else {
            return .corrupt
        }
        guard let startedAt = (defaults.object(forKey: Key.startedAtMs) as? NSNumber)?.int64Value else {
            return protectedDataAvailable ? .corrupt : .unavailable
        }
        return .session(config, startedAtMs: startedAt)
    }

    var pendingFix: Fix? {
        get {
            guard let data = defaults.data(forKey: Key.pendingFix) else { return nil }
            return try? JSONDecoder().decode(Fix.self, from: data)
        }
        set {
            if let fix = newValue, let data = try? JSONEncoder().encode(fix) {
                defaults.set(data, forKey: Key.pendingFix)
            } else {
                defaults.removeObject(forKey: Key.pendingFix)
            }
        }
    }

    var lastUploadAtMs: Int64? {
        get { (defaults.object(forKey: Key.lastUploadAtMs) as? NSNumber)?.int64Value }
        set {
            if let v = newValue {
                defaults.set(NSNumber(value: v), forKey: Key.lastUploadAtMs)
            } else {
                defaults.removeObject(forKey: Key.lastUploadAtMs)
            }
        }
    }

    var lastStop: LastStop? {
        get {
            guard let data = defaults.data(forKey: Key.lastStop) else { return nil }
            return try? JSONDecoder().decode(LastStop.self, from: data)
        }
        set {
            if let stop = newValue, let data = try? JSONEncoder().encode(stop) {
                defaults.set(data, forKey: Key.lastStop)
            } else {
                defaults.removeObject(forKey: Key.lastStop)
            }
        }
    }

    var alwaysRequested: Bool {
        get { defaults.bool(forKey: Key.alwaysRequested) }
        set { defaults.set(newValue, forKey: Key.alwaysRequested) }
    }

    func wipe() {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        Log.debug("store", "session wiped", data: ["keychainStatus": Int(status)], level: "info")
        defaults.removeObject(forKey: Key.startedAtMs)
        defaults.removeObject(forKey: Key.pendingFix)
        defaults.removeObject(forKey: Key.lastUploadAtMs)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func writeKeychain(_ data: Data) throws {
        var add = baseQuery()
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            let update: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ]
            let updateStatus = SecItemUpdate(baseQuery() as CFDictionary, update as CFDictionary)
            guard updateStatus == errSecSuccess else { throw KeychainError.status(updateStatus) }
        default:
            throw KeychainError.status(status)
        }
    }
}
