import CoreLocation
import Foundation
import UIKit

@objc(ELSBootstrap)
final class ELSBootstrap: NSObject {
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
            SessionEngine.shared.resumeIfNeeded()
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
        dispatchPrecondition(condition: .onQueue(.main))
        guard config == nil else { return }
        let protectedData = UIApplication.shared.isProtectedDataAvailable
        switch store.load(protectedDataAvailable: protectedData) {
        case .none where protectedData:
            stopWaitingForProtectedData()
        case .none, .unavailable:
            Log.info("store", "persisted session not readable yet protectedData=\(protectedData); waiting")
            waitForProtectedData()
        case .corrupt:
            stopWaitingForProtectedData()
            Log.info("store", "discarding unreadable persisted session")
            store.wipe()
            source.stop()
        case let .session(c, startedAt):
            stopWaitingForProtectedData()
            Log.info("store", "persisted session \(c.id) protectedData=\(protectedData) regionsAtLaunch=\(source.monitoredRegionIds)", sessionId: c.id)
            if nowMs() >= c.expiresAtMs {
                teardown(c, .expired)
            } else if !permissions.isAuthorized {
                teardown(c, .permissionRevoked)
            } else {
                activate(c, startedAtMs: startedAt, pending: store.pendingFix, lastUploadAtMs: store.lastUploadAtMs)
                Log.info("store", "resumed session \(c.id)", sessionId: c.id)
                if let fix = lastFix { evaluate(fix: fix) } else { uploader.pump() }
            }
        }
    }

    private func waitForProtectedData() {
        guard protectedDataObservers.isEmpty else { return }
        let center = NotificationCenter.default
        protectedDataObservers = [
            UIApplication.protectedDataDidBecomeAvailableNotification,
            UIApplication.didBecomeActiveNotification,
        ].map { name in
            center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Log.info("lifecycle", "retrying resume after \(name.rawValue)")
                self?.stopWaitingForProtectedData()
                self?.resumeIfNeeded()
            }
        }
        Log.info("lifecycle", "waiting for protectedDataDidBecomeAvailable or didBecomeActive")
    }

    private func stopWaitingForProtectedData() {
        protectedDataObservers.forEach { NotificationCenter.default.removeObserver($0) }
        protectedDataObservers.removeAll()
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
        dispatchPrecondition(condition: .onQueue(.main))
        guard let c = config, permissions.hasAlways else { return }
        if let region = source.armTerminateRegion(at: lastFix) {
            Log.info("region", "armed terminate region for \(c.id) r=\(region.radius)", data: ["latitude": region.center.latitude, "longitude": region.center.longitude, "radius": region.radius], sessionId: c.id)
        } else {
            Log.info("region", "could not arm terminate region for \(c.id)", sessionId: c.id)
        }
    }
}
