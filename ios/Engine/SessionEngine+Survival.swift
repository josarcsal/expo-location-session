import CoreLocation
import Foundation
import UIKit

@objc(ELSBootstrap)
final class ELSBootstrap: NSObject {
    @objc(didFinishLaunching:)
    static func didFinishLaunching(_ location: NSNumber?) {
        let locationLaunch = location?.boolValue ?? false
        let run = {
            Log.info("bootstrap launch location=\(locationLaunch) appState=\(UIApplication.shared.applicationState.rawValue)")
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
            Log.info("persisted session not readable yet protectedData=\(protectedData); waiting")
            waitForProtectedData()
        case .corrupt:
            stopWaitingForProtectedData()
            Log.info("discarding unreadable persisted session")
            store.wipe()
            source.stop()
        case let .session(c, startedAt):
            stopWaitingForProtectedData()
            Log.info("persisted session \(c.id) protectedData=\(protectedData) regionsAtLaunch=\(source.monitoredRegionIds)")
            if nowMs() >= c.expiresAtMs {
                teardown(c, .expired)
            } else if !permissions.isAuthorized {
                teardown(c, .permissionRevoked)
            } else {
                activate(c, startedAtMs: startedAt, pending: store.pendingFix, lastUploadAtMs: store.lastUploadAtMs)
                Log.info("resumed session \(c.id)")
                uploader.pump()
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
                Log.info("retrying resume after \(name.rawValue)")
                self?.stopWaitingForProtectedData()
                self?.resumeIfNeeded()
            }
        }
        Log.info("waiting for protectedDataDidBecomeAvailable or didBecomeActive")
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
            Log.info("armed terminate region for \(c.id) r=\(region.radius)")
        } else {
            Log.info("could not arm terminate region for \(c.id)")
        }
    }
}
