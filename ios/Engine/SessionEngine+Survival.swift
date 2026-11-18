import CoreLocation
import Foundation
import UIKit

@objc(ELSBootstrap)
public final class ELSBootstrap: NSObject {
    @objc(didFinishLaunching:)
    public static func didFinishLaunching(locationLaunch: Bool) {
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
        case .none:
            stopWaitingForProtectedData()
        case .unavailable:
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
        guard protectedDataObserver == nil else { return }
        protectedDataObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Log.info("protected data available")
            self?.stopWaitingForProtectedData()
            self?.resumeIfNeeded()
        }
    }

    private func stopWaitingForProtectedData() {
        guard let observer = protectedDataObserver else { return }
        NotificationCenter.default.removeObserver(observer)
        protectedDataObserver = nil
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
            Log.info("armed terminate region for \(c.id) at \(region.center.latitude),\(region.center.longitude) r=\(region.radius)")
        } else {
            Log.info("could not arm terminate region for \(c.id)")
        }
    }
}
