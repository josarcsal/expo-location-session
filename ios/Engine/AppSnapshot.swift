import Foundation
import UIKit

final class AppSnapshot {
    static let shared = AppSnapshot()

    private let lock = NSLock()
    private var protectedData = false
    private var batteryValue: Battery?
    private var primed = false
    private var ownsBatteryMonitoring = false

    private init() {}

    var protectedDataAvailable: Bool {
        lock.lock()
        defer { lock.unlock() }
        return protectedData
    }

    var battery: Battery? {
        lock.lock()
        defer { lock.unlock() }
        return batteryValue
    }

    func primeOnMain() {
        dispatchPrecondition(condition: .onQueue(.main))
        setProtectedData(UIApplication.shared.isProtectedDataAvailable)
        refreshBattery()
        guard !primed else { return }
        primed = true
        let center = NotificationCenter.default
        _ = center.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setProtectedData(true)
        }
        _ = center.addObserver(forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: .main) { [weak self] _ in
            self?.setProtectedData(false)
        }
        for name in [UIDevice.batteryLevelDidChangeNotification, UIDevice.batteryStateDidChangeNotification] {
            _ = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.refreshBattery()
            }
        }
    }

    func setBatteryMonitoring(_ on: Bool) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let device = UIDevice.current
            if on {
                if !device.isBatteryMonitoringEnabled {
                    device.isBatteryMonitoringEnabled = true
                    self.ownsBatteryMonitoring = true
                }
            } else if self.ownsBatteryMonitoring {
                device.isBatteryMonitoringEnabled = false
                self.ownsBatteryMonitoring = false
            }
            self.refreshBattery()
        }
    }

    private func setProtectedData(_ available: Bool) {
        lock.lock()
        protectedData = available
        lock.unlock()
    }

    private func refreshBattery() {
        let device = UIDevice.current
        var value: Battery?
        if device.isBatteryMonitoringEnabled, device.batteryLevel >= 0 {
            let level = Double(device.batteryLevel)
            if level.isFinite {
                value = Battery(level: level, charging: device.batteryState == .charging || device.batteryState == .full)
            }
        }
        lock.lock()
        batteryValue = value
        lock.unlock()
    }
}
