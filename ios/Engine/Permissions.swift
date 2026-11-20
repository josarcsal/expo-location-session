import CoreLocation
import UIKit

final class Permissions: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let store: SessionStore
    private var waiters: [(CLAuthorizationStatus) -> Void] = []
    private var promptTimer: Timer?
    private var resignedActive = false
    private var promptStartStatus: CLAuthorizationStatus = .notDetermined
    private var observers: [NSObjectProtocol] = []
    private var queued: [(always: Bool, completion: (NativePermissionStatus) -> Void)] = []
    private var requesting = false

    init(store: SessionStore) {
        self.store = store
        super.init()
        manager.delegate = self
    }

    var authorizationStatus: CLAuthorizationStatus { manager.authorizationStatus }

    var isAuthorized: Bool {
        let s = authorizationStatus
        return s == .authorizedAlways || s == .authorizedWhenInUse
    }

    var hasAlways: Bool { authorizationStatus == .authorizedAlways }

    func status() -> NativePermissionStatus {
        let foreground: PermissionValue
        let background: PermissionValue
        switch authorizationStatus {
        case .notDetermined:
            foreground = .undetermined
            background = .undetermined
        case .authorizedAlways:
            foreground = .granted
            background = .granted
        case .authorizedWhenInUse:
            foreground = .granted
            background = store.alwaysRequested ? .denied : .undetermined
        default:
            foreground = .denied
            background = .denied
        }
        let result = NativePermissionStatus(
            foreground: foreground,
            background: background,
            precise: manager.accuracyAuthorization == .fullAccuracy,
            notifications: .granted
        )
        Log.debug("permission", "status", data: [
            "authorization": authorizationStatus.rawValue,
            "accuracyAuthorization": manager.accuracyAuthorization.rawValue,
            "alwaysRequested": store.alwaysRequested,
            "foreground": foreground.stringValue,
            "background": background.stringValue,
            "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled,
            "backgroundRefresh": UIApplication.shared.backgroundRefreshStatus.rawValue,
        ])
        return result
    }

    func request(always: Bool, completion: @escaping (NativePermissionStatus) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        queued.append((always, completion))
        runNext()
    }

    private func runNext() {
        guard !requesting, !queued.isEmpty else { return }
        requesting = true
        let next = queued.removeFirst()
        perform(always: next.always) { [weak self] status in
            next.completion(status)
            self?.requesting = false
            self?.runNext()
        }
    }

    private func perform(always: Bool, completion: @escaping (NativePermissionStatus) -> Void) {
        Log.debug("permission", "request", data: ["always": always, "authorization": authorizationStatus.rawValue, "alwaysRequested": store.alwaysRequested], level: "info")
        let finish: () -> Void = { [weak self] in
            guard let self else { return }
            let result = self.status()
            Log.debug("permission", "request result", data: ["foreground": result.foreground.stringValue, "background": result.background.stringValue, "precise": result.precise], level: "info")
            completion(result)
        }
        let askAlways: () -> Void = { [weak self] in
            guard let self else { return }
            guard always, self.authorizationStatus == .authorizedWhenInUse, !self.store.alwaysRequested else {
                finish()
                return
            }
            self.store.alwaysRequested = true
            self.awaitPrompt { _ in finish() }
            self.manager.requestAlwaysAuthorization()
        }
        if authorizationStatus == .notDetermined {
            awaitPrompt { _ in askAlways() }
            manager.requestWhenInUseAuthorization()
        } else {
            askAlways()
        }
    }

    private func awaitPrompt(_ waiter: @escaping (CLAuthorizationStatus) -> Void) {
        waiters.append(waiter)
        resignedActive = false
        promptStartStatus = authorizationStatus
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.resignedActive = true
            self?.promptTimer?.invalidate()
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.resignedActive else { return }
            self.resolveWaiters()
        })
        promptTimer?.invalidate()
        promptTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: false) { [weak self] _ in
            guard let self, !self.resignedActive else { return }
            self.resolveWaiters()
        }
    }

    private func resolveWaiters() {
        promptTimer?.invalidate()
        promptTimer = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        let pending = waiters
        waiters.removeAll()
        let s = authorizationStatus
        pending.forEach { $0(s) }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Log.debug("permission", "authorization callback", data: ["authorization": manager.authorizationStatus.rawValue, "accuracyAuthorization": manager.accuracyAuthorization.rawValue, "awaitingPrompt": !waiters.isEmpty])
        guard !waiters.isEmpty, manager.authorizationStatus != promptStartStatus else { return }
        resolveWaiters()
    }
}
