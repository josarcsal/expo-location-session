import Foundation
import Security

final class TargetCredentials {
    private let service = "expo-location-session"

    private struct Stored: Codable {
        var headers: [String: String]
        var refreshHeaders: [String: String]
    }

    func save(sessionId: String, headers: [String: String], refreshHeaders: [String: String]) throws {
        let data = try JSONEncoder().encode(Stored(headers: headers, refreshHeaders: refreshHeaders))
        var add = query(sessionId)
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
            let updateStatus = SecItemUpdate(query(sessionId) as CFDictionary, update as CFDictionary)
            guard updateStatus == errSecSuccess else { throw KeychainError.status(updateStatus) }
        default:
            throw KeychainError.status(status)
        }
    }

    func load(sessionId: String) -> (headers: [String: String], refreshHeaders: [String: String])? {
        var q = query(sessionId)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data,
              let stored = try? JSONDecoder().decode(Stored.self, from: data) else {
            if status != errSecSuccess, status != errSecItemNotFound {
                Log.debug("auth", "credentials read failed", data: ["status": Int(status)], sessionId: sessionId, level: "warn")
            }
            return nil
        }
        return (stored.headers, stored.refreshHeaders)
    }

    func remove(sessionId: String) {
        SecItemDelete(query(sessionId) as CFDictionary)
    }

    func removeAll(except sessionId: String?) -> Int {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &items) == errSecSuccess, let found = items as? [[String: Any]] else { return 0 }
        let keep = sessionId.map { "target.\($0)" }
        var removed = 0
        for account in found.compactMap({ $0[kSecAttrAccount as String] as? String }) where account.hasPrefix("target.") && account != keep {
            let delete: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
            ]
            if SecItemDelete(delete as CFDictionary) == errSecSuccess { removed += 1 }
        }
        return removed
    }

    private func query(_ sessionId: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "target.\(sessionId)",
        ]
    }
}
