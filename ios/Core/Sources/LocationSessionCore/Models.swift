import Foundation

public struct Fix: Codable, Equatable {
    public var latitude: Double
    public var longitude: Double
    public var accuracy: Double
    public var altitude: Double?
    public var speed: Double?
    public var heading: Double?
    public var timestampMs: Int64

    public init(latitude: Double, longitude: Double, accuracy: Double, altitude: Double? = nil, speed: Double? = nil, heading: Double? = nil, timestampMs: Int64) {
        self.latitude = latitude
        self.longitude = longitude
        self.accuracy = accuracy
        self.altitude = altitude
        self.speed = speed
        self.heading = heading
        self.timestampMs = timestampMs
    }
}

public struct Destination: Codable, Equatable {
    public var latitude: Double
    public var longitude: Double
    public var radiusMeters: Double

    public init(latitude: Double, longitude: Double, radiusMeters: Double) {
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
    }
}

public struct Battery: Codable, Equatable {
    public var level: Double
    public var charging: Bool

    public init(level: Double, charging: Bool) {
        self.level = level
        self.charging = charging
    }
}

public struct SessionConfig: Codable, Equatable {
    public var id: String
    public var expiresAtMs: Int64
    public var destination: Destination?
    public var accuracy: String
    public var distanceFilterMeters: Double
    public var uploadUrl: String
    public var uploadHeaders: [String: String]
    public var uploadExtrasJson: String
    public var uploadMinIntervalMs: Int64
    public var sync: SyncConfig

    public init(id: String, expiresAtMs: Int64, destination: Destination?, accuracy: String, distanceFilterMeters: Double, uploadUrl: String, uploadHeaders: [String: String], uploadExtrasJson: String, uploadMinIntervalMs: Int64, sync: SyncConfig = .default) {
        self.id = id
        self.expiresAtMs = expiresAtMs
        self.destination = destination
        self.accuracy = accuracy
        self.distanceFilterMeters = distanceFilterMeters
        self.uploadUrl = uploadUrl
        self.uploadHeaders = uploadHeaders
        self.uploadExtrasJson = uploadExtrasJson
        self.uploadMinIntervalMs = uploadMinIntervalMs
        self.sync = sync
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        expiresAtMs = try c.decode(Int64.self, forKey: .expiresAtMs)
        destination = try c.decodeIfPresent(Destination.self, forKey: .destination)
        accuracy = try c.decode(String.self, forKey: .accuracy)
        distanceFilterMeters = try c.decode(Double.self, forKey: .distanceFilterMeters)
        uploadUrl = try c.decode(String.self, forKey: .uploadUrl)
        uploadHeaders = try c.decode([String: String].self, forKey: .uploadHeaders)
        uploadExtrasJson = try c.decode(String.self, forKey: .uploadExtrasJson)
        uploadMinIntervalMs = try c.decode(Int64.self, forKey: .uploadMinIntervalMs)
        sync = try c.decodeIfPresent(SyncConfig.self, forKey: .sync) ?? .default
    }
}

public enum StopReason: String, Codable {
    case stopped, expired, arrived, unauthorized, rejected, permissionRevoked
}
