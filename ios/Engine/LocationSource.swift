import CoreLocation
import Foundation

func sanitized(_ f: Fix) -> Fix? {
    guard f.latitude.isFinite, f.longitude.isFinite, abs(f.latitude) <= 90, abs(f.longitude) <= 180,
          f.accuracy.isFinite, f.accuracy >= 0 else { return nil }
    var fix = f
    if let v = fix.altitude, !v.isFinite { fix.altitude = nil }
    if let v = fix.speed, !(v.isFinite && v >= 0) { fix.speed = nil }
    if let v = fix.heading, !(v.isFinite && v >= 0) { fix.heading = nil }
    return fix
}

func makeFix(_ location: CLLocation) -> Fix? {
    let ms = location.timestamp.timeIntervalSince1970 * 1000
    guard ms.isFinite else { return nil }
    return sanitized(Fix(
        latitude: location.coordinate.latitude,
        longitude: location.coordinate.longitude,
        accuracy: location.horizontalAccuracy,
        altitude: location.verticalAccuracy >= 0 ? location.altitude : nil,
        speed: location.speed >= 0 ? location.speed : nil,
        heading: location.course >= 0 ? location.course : nil,
        timestampMs: Int64(ms.rounded())
    ))
}

func desiredAccuracy(_ accuracy: String) -> CLLocationAccuracy {
    switch accuracy {
    case "high": return kCLLocationAccuracyBest
    case "low": return kCLLocationAccuracyKilometer
    default: return kCLLocationAccuracyHundredMeters
    }
}

final class LocationSource: NSObject, CLLocationManagerDelegate {
    static let regionPrefix = "els."
    static let destinationRegionId = "els.destination"
    static let terminateRegionId = "els.terminate"

    static var hasBackgroundLocationMode: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String])?.contains("location") ?? false
    }

    var onFixes: (([Fix]) -> Void)?
    var onAuthorizationChange: ((CLAuthorizationStatus) -> Void)?
    var onAccuracyChange: ((CLAccuracyAuthorization) -> Void)?

    private var manager: CLLocationManager?
    private var lastStatus: CLAuthorizationStatus?
    private var lastAccuracy: CLAccuracyAuthorization?

    var accuracyAuthorization: CLAccuracyAuthorization? { manager?.accuracyAuthorization }
    private var launchReport: (launchAtMs: Int64, region: CLCircularRegion?)?

    func expectLaunchEvent(launchAtMs: Int64) {
        dispatchPrecondition(condition: .onQueue(.main))
        let region = (manager ?? CLLocationManager()).monitoredRegions
            .first { $0.identifier == Self.terminateRegionId } as? CLCircularRegion
        launchReport = (launchAtMs, region)
    }

    private func reportLaunchEvent(_ kind: String, regionId: String? = nil, location: CLLocation? = nil) {
        guard let report = launchReport else { return }
        launchReport = nil
        var data: [String: Any] = ["kind": kind, "msSinceLaunch": nowMs() - report.launchAtMs]
        if let regionId { data["regionId"] = regionId }
        if let location {
            data["fixAgeMs"] = nowMs() - Int64((location.timestamp.timeIntervalSince1970 * 1000).rounded())
            data["accuracy"] = location.horizontalAccuracy
        }
        if let region = report.region {
            data["terminateRadius"] = region.radius
            if let location {
                let center = CLLocation(latitude: region.center.latitude, longitude: region.center.longitude)
                data["distanceFromTerminateCenter"] = location.distance(from: center)
            }
        }
        Log.debug("lifecycle", "first location event after launch", data: data, level: "info")
    }

    @discardableResult
    func start(_ config: SessionConfig) -> Bool {
        dispatchPrecondition(condition: .onQueue(.main))
        let m = manager ?? CLLocationManager()
        manager = m
        lastStatus = m.authorizationStatus
        lastAccuracy = m.accuracyAuthorization
        m.delegate = self
        let backgroundMode = Self.hasBackgroundLocationMode
        if backgroundMode {
            m.allowsBackgroundLocationUpdates = true
        } else {
            Log.debug("location", "UIBackgroundModes has no location; background updates not enabled", sessionId: config.id, level: "warn")
        }
        m.pausesLocationUpdatesAutomatically = false
        m.showsBackgroundLocationIndicator = true
        m.activityType = .other
        m.desiredAccuracy = desiredAccuracy(config.accuracy)
        m.distanceFilter = config.distanceFilterMeters > 0 ? config.distanceFilterMeters : kCLDistanceFilterNone
        m.startUpdatingLocation()
        m.startMonitoringSignificantLocationChanges()
        Log.debug("location", "manager started", data: [
            "mode": "standard+significantChange",
            "desiredAccuracy": m.desiredAccuracy,
            "distanceFilter": m.distanceFilter,
            "authorization": m.authorizationStatus.rawValue,
            "accuracyAuthorization": m.accuracyAuthorization.rawValue,
        ], sessionId: config.id, level: "info")
        removeRegions(m)
        if let d = config.destination, m.authorizationStatus == .authorizedAlways,
           CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) {
            let radius = min(d.radiusMeters, m.maximumRegionMonitoringDistance)
            let region = CLCircularRegion(
                center: CLLocationCoordinate2D(latitude: d.latitude, longitude: d.longitude),
                radius: radius,
                identifier: Self.destinationRegionId
            )
            region.notifyOnEntry = true
            region.notifyOnExit = false
            m.startMonitoring(for: region)
            Log.debug("region", "destination region armed", data: ["latitude": d.latitude, "longitude": d.longitude, "radius": radius], sessionId: config.id, level: "info")
        }
        return backgroundMode
    }

    func stop() {
        dispatchPrecondition(condition: .onQueue(.main))
        let m = manager ?? CLLocationManager()
        m.stopUpdatingLocation()
        m.stopMonitoringSignificantLocationChanges()
        removeRegions(m)
        m.allowsBackgroundLocationUpdates = false
        m.delegate = nil
        manager = nil
        lastStatus = nil
        lastAccuracy = nil
        Log.debug("location", "manager stopped", level: "info")
    }

    func removeRegions() {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let m = manager else { return }
        removeRegions(m)
    }

    @discardableResult
    func armTerminateRegion(at fix: Fix?) -> CLCircularRegion? {
        dispatchPrecondition(condition: .onQueue(.main))
        guard let m = manager, m.authorizationStatus == .authorizedAlways,
              CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return nil }
        m.startMonitoringSignificantLocationChanges()
        guard let anchor = fix ?? m.location.flatMap(makeFix) else { return nil }
        let region = CLCircularRegion(
            center: CLLocationCoordinate2D(latitude: anchor.latitude, longitude: anchor.longitude),
            radius: min(terminateRadius(anchor, nowMs: nowMs()), m.maximumRegionMonitoringDistance),
            identifier: Self.terminateRegionId
        )
        region.notifyOnEntry = false
        region.notifyOnExit = true
        m.startMonitoring(for: region)
        return region
    }

    var monitoredRegionIds: [String] {
        (manager ?? CLLocationManager()).monitoredRegions.map(\.identifier).filter { $0.hasPrefix(Self.regionPrefix) }.sorted()
    }

    private func removeRegions(_ m: CLLocationManager) {
        for region in m.monitoredRegions where region.identifier.hasPrefix(Self.regionPrefix) {
            m.stopMonitoring(for: region)
            Log.debug("region", "region removed", data: ["id": region.identifier])
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        reportLaunchEvent("locations", location: locations.last)
        let fixes = locations.compactMap(makeFix)
        if fixes.count < locations.count {
            for location in locations where makeFix(location) == nil {
                Log.debug("location", "fix dropped invalid", data: [
                    "latitude": location.coordinate.latitude,
                    "longitude": location.coordinate.longitude,
                    "accuracy": location.horizontalAccuracy,
                    "timestamp": location.timestamp.timeIntervalSince1970 * 1000,
                ], level: "warn")
            }
        }
        guard !fixes.isEmpty else { return }
        onFixes?(fixes)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let e = error as NSError
        reportLaunchEvent("error")
        Log.debug("location", "manager error", data: ["domain": e.domain, "code": e.code], level: "warn")
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        reportLaunchEvent("regionEnter", regionId: region.identifier)
        Log.debug("region", "region entered", data: ["id": region.identifier], level: "info")
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        reportLaunchEvent("regionExit", regionId: region.identifier)
        Log.debug("region", "region exited", data: ["id": region.identifier], level: "info")
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        let e = error as NSError
        Log.debug("region", "region monitoring failed", data: ["id": region?.identifier ?? NSNull(), "domain": e.domain, "code": e.code], level: "warn")
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        Log.debug("location", "updates paused by the system", level: "warn")
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        Log.debug("location", "updates resumed by the system", level: "info")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let accuracy = manager.accuracyAuthorization
        if let last = lastAccuracy, accuracy != last {
            lastAccuracy = accuracy
            onAccuracyChange?(accuracy)
        }
        let status = manager.authorizationStatus
        guard status != lastStatus else { return }
        lastStatus = status
        onAuthorizationChange?(status)
    }
}

final class OneShotLocator: NSObject, CLLocationManagerDelegate {
    private static var active: Set<OneShotLocator> = []

    private let manager = CLLocationManager()
    private var completion: ((Result<Fix, LocationSessionError>) -> Void)?
    private var timer: Timer?

    static func locate(accuracy: String, timeoutMs: Double, maxAgeMs: Double, completion: @escaping (Result<Fix, LocationSessionError>) -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        let locator = OneShotLocator()
        active.insert(locator)
        locator.run(accuracy: accuracy, timeoutMs: timeoutMs, maxAgeMs: maxAgeMs, completion: completion)
    }

    private func run(accuracy: String, timeoutMs: Double, maxAgeMs: Double, completion: @escaping (Result<Fix, LocationSessionError>) -> Void) {
        self.completion = completion
        let status = manager.authorizationStatus
        guard status == .authorizedAlways || status == .authorizedWhenInUse else {
            finish(.failure(LocationSessionError(code: "PERMISSION_DENIED", message: "Location permission is not granted")))
            return
        }
        if maxAgeMs > 0, let cached = manager.location.flatMap(makeFix), Double(nowMs() - cached.timestampMs) <= maxAgeMs {
            finish(.success(cached))
            return
        }
        manager.delegate = self
        manager.desiredAccuracy = desiredAccuracy(accuracy)
        let timeout = timeoutMs.isFinite && timeoutMs > 0 ? timeoutMs : 15000
        timer = Timer.scheduledTimer(withTimeInterval: timeout / 1000, repeats: false) { [weak self] _ in
            self?.finish(.failure(LocationSessionError(code: "LOCATION_TIMEOUT", message: "No location within \(Int(timeout)) ms")))
        }
        manager.requestLocation()
    }

    private func finish(_ result: Result<Fix, LocationSessionError>) {
        guard let completion else { return }
        self.completion = nil
        timer?.invalidate()
        timer = nil
        manager.stopUpdatingLocation()
        manager.delegate = nil
        Self.active.remove(self)
        completion(result)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let fix = locations.reversed().lazy.compactMap(makeFix).first else { return }
        finish(.success(fix))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard completion != nil else { return }
        switch (error as? CLError)?.code {
        case .denied:
            finish(.failure(LocationSessionError(code: "PERMISSION_DENIED", message: "Location permission is not granted")))
        case .locationUnknown:
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                guard let self, self.completion != nil else { return }
                self.manager.requestLocation()
            }
        default:
            finish(.failure(LocationSessionError(code: "LOCATION_UNAVAILABLE", message: error.localizedDescription)))
        }
    }
}
