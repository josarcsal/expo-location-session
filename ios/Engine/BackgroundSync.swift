import BackgroundTasks
import Foundation

enum BackgroundSync {
    static let identifier: String? = Bundle.main.bundleIdentifier.map { $0 + queueSyncIdentifierSuffix }

    static let isPermitted: Bool = {
        guard let identifier,
              let permitted = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        else { return false }
        return permitted.contains(identifier)
    }()

    static func register() {
        guard isPermitted, let identifier else { return }
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
            SessionEngine.shared.primeOnMain()
            EngineQueue.shared.async { run(task) }
        }
        Log.debug("sync", "background sync registered", data: ["identifier": identifier, "registered": registered], level: registered ? "debug" : "warn")
    }

    static func submitIfNeeded(pending: Int, sessionRunning: Bool) {
        guard shouldScheduleSync(enabled: isPermitted, pending: pending, sessionRunning: sessionRunning), let identifier else { return }
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        do {
            try BGTaskScheduler.shared.submit(request)
            Log.debug("sync", "background sync submitted", data: ["pending": pending])
        } catch {
            Log.debug("sync", "background sync submit failed", data: ["pending": pending, "error": "\(error)"], level: "warn")
        }
    }

    private static func run(_ task: BGTask) {
        EngineQueue.shared.assertCurrent()
        let engine = SessionEngine.shared
        var completed = false
        var expired = false
        let finish = {
            guard !completed else { return }
            completed = true
            let pending = engine.queue.status()?.pending
            let sessionRunning = engine.config != nil
            let remaining = pending.map { shouldScheduleSync(enabled: true, pending: $0, sessionRunning: sessionRunning) } ?? true
            Log.debug("sync", "background sync finished", data: ["pending": pending ?? NSNull(), "sessionRunning": sessionRunning, "expired": expired])
            task.setTaskCompleted(success: !expired && !remaining)
            submitIfNeeded(pending: pending ?? 0, sessionRunning: sessionRunning)
        }
        task.expirationHandler = {
            EngineQueue.shared.async {
                guard !completed else { return }
                expired = true
                engine.uploader.cancelDrain(release: true)
                finish()
            }
        }
        Log.debug("sync", "background sync started")
        engine.syncQueue { _ in finish() }
    }
}
