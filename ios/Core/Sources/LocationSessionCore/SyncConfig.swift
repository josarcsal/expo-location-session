import Foundation

public enum QueueMode: String, Codable {
    case latest, all
}

public struct BatchConfig: Codable, Equatable {
    public var maxSize: Int
    public var rootProperty: String

    public init(maxSize: Int, rootProperty: String = "locations") {
        self.maxSize = maxSize
        self.rootProperty = rootProperty
    }
}

public struct RefreshConfig: Codable, Equatable {
    public var url: String
    public var headers: [String: String]
    public var bodyJson: String?

    public init(url: String, headers: [String: String] = [:], bodyJson: String? = nil) {
        self.url = url
        self.headers = headers
        self.bodyJson = bodyJson
    }
}

public struct SyncConfig: Codable, Equatable {
    public var mode: QueueMode
    public var maxRecords: Int
    public var maxAgeMs: Int64
    public var batch: BatchConfig?
    public var templateJson: String?
    public var refresh: RefreshConfig?

    public init(mode: QueueMode = .latest, maxRecords: Int = 10_000, maxAgeMs: Int64 = 86_400_000, batch: BatchConfig? = nil, templateJson: String? = nil, refresh: RefreshConfig? = nil) {
        self.mode = mode
        self.maxRecords = maxRecords
        self.maxAgeMs = maxAgeMs
        self.batch = batch
        self.templateJson = templateJson
        self.refresh = refresh
    }

    public static let `default` = SyncConfig()
}

public func validateSync(_ s: SyncConfig, extrasJson: String) -> String? {
    if let b = s.batch {
        if s.mode != .all { return "batch requires queue.mode 'all'" }
        if !(1...500).contains(b.maxSize) { return "batch.maxSize must be between 1 and 500" }
        if b.rootProperty.isEmpty { return "batch.rootProperty must not be empty" }
    }
    if !(1...100_000).contains(s.maxRecords) { return "queue.maxRecords must be between 1 and 100000" }
    if !(60_000...2_592_000_000).contains(s.maxAgeMs) { return "queue.maxAgeMs must be between 60000 and 2592000000" }
    if let r = s.refresh {
        guard let url = URL(string: r.url), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else {
            return "auth.refresh.url must be an https URL"
        }
        if let body = r.bodyJson {
            let parsed = try? JSONSerialization.jsonObject(with: Data(body.utf8))
            if !(parsed is [String: Any]) { return "auth.refresh.body must be a JSON object" }
        }
    }
    if let t = s.templateJson {
        if t.isEmpty { return "body.template is required" }
        if let reason = validateTemplate(t, extrasJson: extrasJson) { return "template: \(reason)" }
    }
    return nil
}
