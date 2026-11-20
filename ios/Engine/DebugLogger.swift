import Foundation

final class DebugLogger {
    static let shared = DebugLogger()

    private init() {}

    func configure(_ options: DebugOptions) throws {
        if let reason = validateDebugOptions(options) {
            throw LocationSessionError(code: "INVALID_CONFIG", message: reason)
        }
    }

    func log(_ level: String, _ tag: String, _ msg: String, data: [String: Any]? = nil, sessionId: String? = nil) {}

    func read(limit: Int) -> String { "[]" }

    func clear() {}

    func flush(completion: @escaping (Int) -> Void) { completion(0) }

    func onUploadCompleted() {}

    func onStopping() {}
}
