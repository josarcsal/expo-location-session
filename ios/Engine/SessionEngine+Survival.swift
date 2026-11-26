import CoreLocation
import Foundation
import UIKit

@objc(ELSBootstrap)
final class ELSBootstrap: NSObject {
    @objc static func registerBackgroundTasks() {
        BackgroundSync.register()
    }

    @objc(didFinishLaunching:launchKeys:)
    static func didFinishLaunching(_ location: NSNumber?, launchKeys: NSArray?) {
        let locationLaunch = location?.boolValue ?? false
        let keys = (launchKeys as? [String] ?? []).sorted()
        let run = {
            let app = UIApplication.shared
            DebugLogger.shared.processStarted(
                launchKeys: keys,
                locationLaunch: locationLaunch,
                appState: app.applicationState.rawValue,
                protectedData: app.isProtectedDataAvailable
            )
            Log.info("lifecycle", "bootstrap launch location=\(locationLaunch) appState=\(app.applicationState.rawValue)")
            SessionEngine.shared.primeOnMain()
            EngineQueue.shared.async { SessionEngine.shared.resumeIfNeeded() }
        }
        if Thread.isMainThread {
            run()
        } else {
            DispatchQueue.main.async(execute: run)
        }
    }
}

extension SessionEngine {
    func resumeIfNeeded() {
        EngineQueue.shared.assertCurrent()
        guard config == nil else { return }
        let protectedData = AppSnapshot.shared.protectedDataAvailable
        let source = self.source
        switch store.load(protectedDataAvailable: protectedData) {
        case .none where protectedData:
            stopWaitingForProtectedData()
            DispatchQueue.main.async { source.releaseLeftovers() }
            endLeftoverTargets()
        case .none, .unavailable:
            Log.info("store", "persisted session not readable yet protectedData=\(protectedData); waiting")
            waitForProtectedData()
        case .corrupt:
            stopWaitingForProtectedData()
            Log.info("store", "discarding unreadable persisted session")
            store.wipe()
            endLeftoverTargets()
            DispatchQueue.main.async { source.stop() }
        case let .session(c, startedAt):
            stopWaitingForProtectedData()
            endLeftoverTargets(except: c.id)
            DispatchQueue.main.async {
                Log.info("store", "persisted session \(c.id) protectedData=\(protectedData) regionsAtLaunch=\(source.monitoredRegionIds)", sessionId: c.id)
            }
            if nowMs() >= c.expiresAtMs {
                teardown(c, .expired)
            } else if !permissions.isAuthorized {
                teardown(c, .permissionRevoked)
            } else {
                let launchAtMs = self.launchAtMs
                DispatchQueue.main.async { source.expectLaunchEvent(launchAtMs: launchAtMs) }
                activate(c, startedAtMs: startedAt, pending: store.pendingFix, lastUploadAtMs: store.lastUploadAtMs)
                Log.info("store", "resumed session \(c.id)", sessionId: c.id)
                if let fix = lastFix { evaluate(fix: fix) } else { uploader.pump() }
            }
        }
        uploader.drain(budgetMs: nil) { _ in }
    }

    private func endLeftoverTargets(except running: String? = nil) {
        guard let removed = queue.store()?.endLeftovers(except: running) else { return }
        removed.forEach { credentials.remove(sessionId: $0) }
        if !removed.isEmpty {
            Log.debug("queue", "leftover latest-mode sessions deleted", data: ["targets": removed.count])
        }
    }

    private func waitForProtectedData() {
        guard protectedDataObservers.isEmpty else { return }
        let center = NotificationCenter.default
        var forwarded = false
        protectedDataObservers = [
            UIApplication.protectedDataDidBecomeAvailableNotification,
            UIApplication.didBecomeActiveNotification,
        ].map { name in
            center.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                guard !forwarded else { return }
                forwarded = true
                AppSnapshot.shared.primeOnMain()
                EngineQueue.shared.async {
                    Log.info("lifecycle", "retrying resume after \(name.rawValue)")
                    self?.stopWaitingForProtectedData()
                    self?.resumeIfNeeded()
                }
            }
        }
        Log.info("lifecycle", "waiting for protectedDataDidBecomeAvailable or didBecomeActive")
    }

    private func stopWaitingForProtectedData() {
        protectedDataObservers.forEach { NotificationCenter.default.removeObserver($0) }
        protectedDataObservers.removeAll()
    }

    func observeForeground() {
        _ = NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil) { [weak self] _ in
            EngineQueue.shared.async { self?.uploader.drain(budgetMs: nil) { _ in } }
        }
    }

    func observeTermination() {
        guard terminateObserver == nil else { return }
        terminateObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            self?.prepareForTermination()
        }
    }

    private func prepareForTermination() {
        let (config, lastFix, hasAlways) = EngineQueue.shared.syncFromMain { (self.config, self.lastFix, self.permissions.hasAlways) }
        guard let c = config, hasAlways else { return }
        if let region = source.armTerminateRegion(at: lastFix) {
            Log.info("region", "armed terminate region for \(c.id) r=\(region.radius)", data: ["latitude": region.center.latitude, "longitude": region.center.longitude, "radius": region.radius], sessionId: c.id)
        } else {
            Log.info("region", "could not arm terminate region for \(c.id)", sessionId: c.id)
        }
        DebugLogger.shared.drain()
    }
}
