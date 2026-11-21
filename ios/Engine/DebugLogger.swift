import Foundation
import Security
import UIKit

final class DebugLogger {
    static let shared = DebugLogger()

    let queue = DispatchQueue(label: "expo-location-session.debug", qos: .utility)
    let pid = ProcessInfo.processInfo.processIdentifier

    private let defaults = UserDefaults(suiteName: "expo-location-session") ?? .standard
    private let keychainService = "expo-location-session"
    private let keychainAccount = "debugHeaders"
    private let maxPendingLines = 1000
    private let maxEarlyLines = 200
    private let checkpointEvery: Int64 = 256
    private let chunkBytes = 256 * 1024

    private enum Key {
        static let options = "debugOptions"
        static let seq = "debugSeq"
        static let cursor = "debugCursor"
        static let fileIndex = "debugFileIndex"
        static let installId = "debugInstallId"
    }

    private struct FileState {
        var firstSeq: Int64?
        var lastSeq: Int64?
        var size: Int64 = 0
        var checkpoints: [(seq: Int64, offset: UInt64)] = []
    }

    private struct EarlyLine {
        var level: String
        var tag: String
        var msg: String
        var data: [String: Any]?
        var sessionId: String?
        var t: Int64
    }

    private struct StoredOptions: Codable {
        var enabled: Bool
        var url: String?
        var flushIntervalMs: Int64
        var maxFileBytes: Int64
    }

    private var options: DebugOptions?
    private var cachedHeaders: [String: String]?
    private var active = false
    private var seq: Int64 = 0
    private var files = [FileState(), FileState()]
    private var current = 0
    private var handle: FileHandle?
    private var pending: [(seq: Int64, line: String)] = []
    private var droppedPending = 0
    private var early: [EarlyLine] = []
    private var droppedEarly = 0
    private var protectedData: Bool?
    private var protectedObserver: NSObjectProtocol?
    private var timer: DispatchSourceTimer?
    private var observers: [NSObjectProtocol] = []
    private weak var events: EventBus?
    private lazy var shipper = DebugShipper(logger: self)

    private init() {}

    func attach(_ bus: EventBus) {
        queue.async { self.events = bus }
    }

    func configure(_ o: DebugOptions) throws {
        if let reason = validateDebugOptions(o) {
            throw LocationSessionError(code: "INVALID_CONFIG", message: reason)
        }
        do {
            try writeHeaders(o.headers)
        } catch {
            throw LocationSessionError(code: "INTERNAL", message: "Could not persist debug headers: \(error)")
        }
        let stored = StoredOptions(enabled: o.enabled, url: o.url, flushIntervalMs: o.flushIntervalMs, maxFileBytes: o.maxFileBytes)
        if let data = try? JSONEncoder().encode(stored) {
            defaults.set(data, forKey: Key.options)
        }
        queue.async { self.apply(o) }
    }

    func log(_ level: String, _ tag: String, _ msg: String, data: [String: Any]? = nil, sessionId: String? = nil) {
        let t = nowMs()
        queue.async {
            self.loadIfNeeded()
            if self.active {
                self.append(level, tag, msg, data: data, sessionId: sessionId, t: t)
            } else if self.options == nil, self.protectedData != true {
                if self.early.count < self.maxEarlyLines {
                    self.early.append(EarlyLine(level: level, tag: tag, msg: msg, data: data, sessionId: sessionId, t: t))
                } else {
                    self.droppedEarly += 1
                }
            }
        }
    }

    func drain() {
        queue.sync {}
    }

    func processStarted(launchKeys: [String], locationLaunch: Bool, appState: Int, protectedData: Bool) {
        queue.async {
            self.protectedData = protectedData
            if !protectedData { self.retryWhenProtectedDataAvailable() }
        }
        log("info", "lifecycle", "process start", data: [
            "pid": pid,
            "libVersion": DebugLogger.libVersion,
            "os": DebugLogger.osVersion,
            "model": DebugLogger.model,
            "launchOptionsKeys": launchKeys,
            "locationLaunch": locationLaunch,
            "bootstrap": "+load didFinishLaunching",
            "appState": appState,
            "protectedData": protectedData,
            "lowPowerMode": ProcessInfo.processInfo.isLowPowerModeEnabled,
        ])
    }

    func read(limit: Int) -> String {
        queue.sync {
            let newest = active ? current : (defaults.integer(forKey: Key.fileIndex) == 1 ? 1 : 0)
            var lines: [String] = []
            for index in [1 - newest, newest] {
                forEachLine(fileURL(index), from: 0) { line, seq, _ in
                    guard seq != nil else { return true }
                    lines.append(String(decoding: line, as: UTF8.self))
                    if limit > 0, lines.count >= 2 * limit { lines.removeFirst(lines.count - limit) }
                    return true
                }
            }
            lines.append(contentsOf: pending.map(\.line))
            if limit > 0, lines.count > limit { lines.removeFirst(lines.count - limit) }
            return "[" + lines.joined(separator: ",") + "]"
        }
    }

    func clear() {
        queue.sync {
            try? handle?.close()
            handle = nil
            try? FileManager.default.removeItem(at: directoryURL)
            files = [FileState(), FileState()]
            pending.removeAll()
            droppedPending = 0
        }
    }

    func flush(completion: @escaping (Int) -> Void) {
        queue.async {
            self.loadIfNeeded()
            guard self.canShip else {
                completion(0)
                return
            }
            self.shipper.ship(completion)
        }
    }

    func onUploadCompleted() {
        trigger()
    }

    func onStopping() {
        trigger()
    }

    private func trigger() {
        queue.async {
            self.loadIfNeeded()
            guard self.canShip else { return }
            self.shipper.ship(nil)
        }
    }

    private var canShip: Bool {
        active && options?.url != nil
    }

    private func loadIfNeeded() {
        guard options == nil else { return }
        guard let data = defaults.data(forKey: Key.options),
              let stored = try? JSONDecoder().decode(StoredOptions.self, from: data) else {
            if protectedData == true { dropEarly() }
            return
        }
        options = DebugOptions(enabled: stored.enabled, url: stored.url, headers: [:], flushIntervalMs: stored.flushIntervalMs, maxFileBytes: stored.maxFileBytes)
        if stored.enabled {
            activate()
            replayEarly()
        } else {
            dropEarly()
        }
    }

    private func replayEarly() {
        let lines = early
        let dropped = droppedEarly
        dropEarly()
        for e in lines {
            append(e.level, e.tag, e.msg, data: e.data, sessionId: e.sessionId, t: e.t)
        }
        if dropped > 0 {
            append("warn", "debug", "lines dropped before debug options were readable", data: ["count": dropped], sessionId: nil, t: nowMs())
        }
    }

    private func dropEarly() {
        early.removeAll()
        droppedEarly = 0
    }

    private func retryWhenProtectedDataAvailable() {
        guard protectedObserver == nil else { return }
        protectedObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification,
            object: nil,
            queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                if let o = self.protectedObserver { NotificationCenter.default.removeObserver(o) }
                self.protectedObserver = nil
                self.protectedData = true
                self.loadIfNeeded()
            }
        }
    }

    private func apply(_ o: DebugOptions) {
        options = o
        cachedHeaders = o.headers
        guard o.enabled else {
            dropEarly()
            deactivate()
            return
        }
        activate()
        replayEarly()
        restartTimer()
        var data: [String: Any] = [
            "flushIntervalMs": o.flushIntervalMs,
            "maxFileBytes": o.maxFileBytes,
            "headers": o.headers.keys.sorted(),
        ]
        if let raw = o.url, let url = URL(string: raw) {
            data["url"] = "\(url.scheme ?? "")://\(url.host ?? "")\(url.port.map { ":\($0)" } ?? "")\(url.path)"
        }
        append("info", "debug", "configured", data: data, sessionId: nil, t: nowMs())
        if canShip { shipper.ship(nil) }
    }

    private func activate() {
        guard !active else { return }
        active = true
        current = defaults.integer(forKey: Key.fileIndex) == 1 ? 1 : 0
        for index in 0...1 {
            files[index] = scan(fileURL(index))
        }
        let onDisk = files.compactMap(\.lastSeq).max() ?? 0
        seq = max((defaults.object(forKey: Key.seq) as? NSNumber)?.int64Value ?? 0, onDisk)
        observeApp()
        restartTimer()
        queue.async {
            guard self.canShip else { return }
            self.shipper.ship(nil)
        }
    }

    private func deactivate() {
        active = false
        timer?.cancel()
        timer = nil
        try? handle?.close()
        handle = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
    }

    private func restartTimer() {
        timer?.cancel()
        timer = nil
        guard canShip, let o = options else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        let interval = DispatchTimeInterval.milliseconds(Int(o.flushIntervalMs))
        t.schedule(deadline: .now() + interval, repeating: interval, leeway: .seconds(1))
        t.setEventHandler { [weak self] in
            guard let self, self.canShip else { return }
            self.shipper.ship(nil)
        }
        t.resume()
        timer = t
    }

    private func observeApp() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        let lifecycle: [(Notification.Name, String)] = [
            (UIApplication.didBecomeActiveNotification, "app state active"),
            (UIApplication.willResignActiveNotification, "app state will resign active"),
            (UIApplication.didEnterBackgroundNotification, "app state background"),
            (UIApplication.willEnterForegroundNotification, "app state will enter foreground"),
            (UIApplication.didReceiveMemoryWarningNotification, "memory warning"),
            (UIApplication.willTerminateNotification, "will terminate"),
            (UIApplication.protectedDataWillBecomeUnavailableNotification, "protected data will become unavailable"),
            (UIApplication.protectedDataDidBecomeAvailableNotification, "protected data available"),
        ]
        observers = lifecycle.map { name, msg in
            center.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                self?.log(name == UIApplication.didReceiveMemoryWarningNotification ? "warn" : "info", "lifecycle", msg)
                if name == UIApplication.willTerminateNotification { self?.drain() }
            }
        }
        observers.append(center.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: nil) { [weak self] _ in
            self?.log("info", "permission", "low power mode changed", data: ["enabled": ProcessInfo.processInfo.isLowPowerModeEnabled])
        })
    }

    private func append(_ level: String, _ tag: String, _ msg: String, data: [String: Any]?, sessionId: String?, t: Int64) {
        seq += 1
        defaults.set(NSNumber(value: seq), forKey: Key.seq)
        let line = encodeLogLine(LogLine(seq: seq, t: t, pid: pid, level: level, tag: tag, msg: msg, sessionId: sessionId, data: data))
        pending.append((seq, line))
        drainPending()
        if let events, events.hasListeners(.log) {
            events.emit(.log, json: line)
        }
    }

    private func drainPending() {
        while !pending.isEmpty {
            guard let h = openHandle() else { break }
            let entry = pending[0]
            let bytes = Data((entry.line + "\n").utf8)
            let offset = UInt64(files[current].size)
            do {
                try h.write(contentsOf: bytes)
            } catch {
                try? h.truncate(atOffset: offset)
                try? h.close()
                handle = nil
                break
            }
            pending.removeFirst()
            files[current].size += Int64(bytes.count)
            if files[current].firstSeq == nil { files[current].firstSeq = entry.seq }
            files[current].lastSeq = entry.seq
            if entry.seq - (files[current].checkpoints.last?.seq ?? Int64.min / 2) >= checkpointEvery {
                files[current].checkpoints.append((entry.seq, offset))
            }
            if files[current].size >= (options?.maxFileBytes ?? 1_000_000) { rotate() }
        }
        if pending.isEmpty, droppedPending > 0 {
            let count = droppedPending
            droppedPending = 0
            append("warn", "debug", "lines dropped while the log file was not writable", data: ["count": count], sessionId: nil, t: nowMs())
        } else if pending.count > maxPendingLines {
            let excess = pending.count - maxPendingLines
            pending.removeFirst(excess)
            droppedPending += excess
        }
    }

    private func rotate() {
        try? handle?.close()
        handle = nil
        current = 1 - current
        try? FileManager.default.removeItem(at: fileURL(current))
        files[current] = FileState()
        defaults.set(current, forKey: Key.fileIndex)
    }

    private func openHandle() -> FileHandle? {
        if let handle { return handle }
        let fm = FileManager.default
        let protection: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        var dir = directoryURL
        if !fm.fileExists(atPath: dir.path) {
            do {
                try fm.createDirectory(at: dir, withIntermediateDirectories: true, attributes: protection)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try dir.setResourceValues(values)
            } catch {
                return nil
            }
        }
        let url = fileURL(current)
        if !fm.fileExists(atPath: url.path), !fm.createFile(atPath: url.path, contents: nil, attributes: protection) {
            return nil
        }
        guard let h = try? FileHandle(forWritingTo: url), let end = try? h.seekToEnd() else { return nil }
        files[current].size = Int64(end)
        handle = h
        return h
    }

    private func scan(_ url: URL) -> FileState {
        var state = FileState()
        var end: UInt64 = 0
        forEachLine(url, from: 0) { line, seq, offset in
            end = offset + UInt64(line.count) + 1
            guard let seq else { return true }
            if state.firstSeq == nil { state.firstSeq = seq }
            state.lastSeq = seq
            if seq - (state.checkpoints.last?.seq ?? Int64.min / 2) >= checkpointEvery {
                state.checkpoints.append((seq, offset))
            }
            return true
        }
        guard let h = try? FileHandle(forUpdating: url) else { return state }
        defer { try? h.close() }
        if let size = try? h.seekToEnd(), size > end {
            try? h.truncate(atOffset: end)
        }
        state.size = Int64(end)
        return state
    }

    private func forEachLine(_ url: URL, from offset: UInt64, _ body: (Data, Int64?, UInt64) -> Bool) {
        guard let h = try? FileHandle(forReadingFrom: url) else { return }
        defer { try? h.close() }
        do { try h.seek(toOffset: offset) } catch { return }
        var buffer = Data()
        var bufferOffset = offset
        while let chunk = try? h.read(upToCount: chunkBytes), !chunk.isEmpty {
            buffer.append(chunk)
            var start = buffer.startIndex
            while let newline = buffer[start...].firstIndex(of: UInt8(ascii: "\n")) {
                let line = buffer[start..<newline]
                let lineOffset = bufferOffset + UInt64(start - buffer.startIndex)
                if !line.isEmpty, !body(line, lineSeq(line), lineOffset) { return }
                start = buffer.index(after: newline)
            }
            bufferOffset += UInt64(start - buffer.startIndex)
            buffer = Data(buffer[start...])
        }
    }

    func makeBatch() -> (request: URLRequest, toSeq: Int64, count: Int)? {
        guard canShip, let o = options, let raw = o.url, let url = URL(string: raw) else { return nil }
        let firsts = files.compactMap(\.firstSeq)
        let lasts = files.compactMap(\.lastSeq)
        guard let low = firsts.min(), let high = lasts.max(), low <= high else { return nil }
        var cursor = (defaults.object(forKey: Key.cursor) as? NSNumber)?.int64Value ?? 0
        guard let headers = loadHeaders() else { return nil }
        var lines: [String] = []
        var toSeq: Int64 = 0
        while lines.isEmpty {
            guard var plan = planBatch(fileSeqRange: low...high, cursor: cursor) else { return nil }
            if plan.lostFrom != nil, plan.toSeq - plan.fromSeq + 1 >= 500, let smaller = planBatch(fileSeqRange: low...high, cursor: cursor, max: 499) {
                plan = smaller
            }
            lines = readLines(from: plan.fromSeq, to: plan.toSeq)
            if let from = plan.lostFrom, let to = plan.lostTo {
                let lost = LogLine(seq: to, t: nowMs(), pid: pid, level: "warn", tag: "debug", msg: "lines lost", data: ["from": from, "to": to])
                lines.insert(encodeLogLine(lost), at: 0)
            }
            toSeq = plan.toSeq
            if lines.isEmpty {
                setCursor(toSeq)
                cursor = toSeq
            }
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let install = (try? JSONSerialization.data(withJSONObject: installInfo())).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        request.httpBody = Data("{\"install\":\(install),\"lines\":[\(lines.joined(separator: ","))]}".utf8)
        return (request, toSeq, lines.count)
    }

    func delivered(upTo seq: Int64) {
        setCursor(seq)
    }

    func shipFailed(status: Int?, error: String?) {
        guard active else { return }
        var data: [String: Any] = [:]
        if let status { data["status"] = status }
        if let error { data["error"] = error }
        append("warn", "debug", "log batch not delivered", data: data, sessionId: nil, t: nowMs())
    }

    private func setCursor(_ seq: Int64) {
        defaults.set(NSNumber(value: seq), forKey: Key.cursor)
    }

    private func readLines(from: Int64, to: Int64) -> [String] {
        var out: [String] = []
        for index in [1 - current, current] {
            let state = files[index]
            guard let first = state.firstSeq, let last = state.lastSeq, last >= from, first <= to else { continue }
            let start = state.checkpoints.last { $0.seq <= from }?.offset ?? 0
            var done = false
            forEachLine(fileURL(index), from: start) { line, seq, _ in
                guard let seq else { return true }
                if seq > to {
                    done = true
                    return false
                }
                if seq >= from { out.append(String(decoding: line, as: UTF8.self)) }
                return true
            }
            if done { break }
        }
        return out
    }

    func installId() -> String? {
        queue.sync {
            if defaults.string(forKey: Key.installId) == nil, protectedData == false { return nil }
            return storedInstallId()
        }
    }

    private func storedInstallId() -> String {
        if let existing = defaults.string(forKey: Key.installId) { return existing }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: Key.installId)
        return id
    }

    private func installInfo() -> [String: Any] {
        let id = storedInstallId()
        let info = Bundle.main.infoDictionary ?? [:]
        let short = info["CFBundleShortVersionString"] as? String ?? "0"
        let build = info["CFBundleVersion"] as? String ?? "0"
        return [
            "id": id,
            "platform": "ios",
            "model": DebugLogger.model,
            "os": DebugLogger.osVersion,
            "appVersion": "\(short) (\(build))",
            "libVersion": DebugLogger.libVersion,
        ]
    }

    private func loadHeaders() -> [String: String]? {
        if let cachedHeaders { return cachedHeaders }
        var query = keychainQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &item) {
        case errSecSuccess:
            guard let data = item as? Data, let headers = try? JSONDecoder().decode([String: String].self, from: data) else {
                cachedHeaders = [:]
                return [:]
            }
            cachedHeaders = headers
            return headers
        case errSecItemNotFound:
            cachedHeaders = [:]
            return [:]
        default:
            return nil
        }
    }

    private func writeHeaders(_ headers: [String: String]) throws {
        SecItemDelete(keychainQuery() as CFDictionary)
        guard !headers.isEmpty else { return }
        var add = keychainQuery()
        add[kSecValueData as String] = try JSONEncoder().encode(headers)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    private func keychainQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
        ]
    }

    private var directoryURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("expo-location-session", isDirectory: true)
    }

    private func fileURL(_ index: Int) -> URL {
        directoryURL.appendingPathComponent("debug-\(index).jsonl")
    }

    static let libVersion: String = {
        guard let loader = NSClassFromString("ELSBootstrapLoader") as? NSObject.Type,
              let value = loader.perform(NSSelectorFromString("libVersion"))?.takeUnretainedValue() as? String else { return "unknown" }
        return value
    }()

    static let osVersion: String = {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "iOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }()

    static let model: String = {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }()
}
