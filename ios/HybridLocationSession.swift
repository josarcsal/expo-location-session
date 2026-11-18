import Foundation
import NitroModules

class HybridLocationSession: HybridLocationSessionSpec {
    private let notImplemented = RuntimeError.error(withMessage: "NOT_IMPLEMENTED: not implemented")
    private var nextListenerId = 0.0

    func getPermissionStatus() throws -> Promise<NativePermissionStatus> {
        return Promise.rejected(withError: notImplemented)
    }

    func requestPermission(level: PermissionLevel) throws -> Promise<NativePermissionStatus> {
        return Promise.rejected(withError: notImplemented)
    }

    func getCurrentPosition(accuracy: Accuracy, timeoutMs: Double, maxAgeMs: Double) throws -> Promise<NativeLocation> {
        return Promise.rejected(withError: notImplemented)
    }

    func start(config: NativeSessionConfig) throws -> Promise<Void> {
        return Promise.rejected(withError: notImplemented)
    }

    func stop() throws -> Promise<Void> {
        return Promise.rejected(withError: notImplemented)
    }

    func getState() throws -> Promise<NativeSessionState> {
        return Promise.rejected(withError: notImplemented)
    }

    func addListener(event: SessionEvent, callback: @escaping (String) -> Void) throws -> Double {
        nextListenerId += 1
        return nextListenerId
    }

    func removeListener(id: Double) throws {}
}
