import Foundation

public final class EngineQueue {
    public static let shared = EngineQueue(label: "els.engine")

    public let queue: DispatchQueue
    public let operationQueue: OperationQueue
    private let key = DispatchSpecificKey<Void>()

    public init(label: String) {
        queue = DispatchQueue(label: label, qos: .default)
        queue.setSpecific(key: key, value: ())
        operationQueue = OperationQueue()
        operationQueue.underlyingQueue = queue
        operationQueue.maxConcurrentOperationCount = 1
    }

    public var isCurrent: Bool { DispatchQueue.getSpecific(key: key) != nil }

    public func async(_ work: @escaping () -> Void) {
        queue.async(execute: work)
    }

    public func assertCurrent() {
        dispatchPrecondition(condition: .onQueue(queue))
    }

    public func syncFromMain<T>(_ work: () throws -> T) rethrows -> T {
        if isCurrent { return try work() }
        dispatchPrecondition(condition: .onQueue(.main))
        return try queue.sync(execute: work)
    }
}

public final class EngineTimer {
    private let engine: EngineQueue
    private var source: DispatchSourceTimer?
    private var generation = 0

    public init(_ engine: EngineQueue) {
        self.engine = engine
    }

    public var isScheduled: Bool { source != nil }

    public func schedule(afterMs: Int64, _ fire: @escaping () -> Void) {
        cancel()
        let token = generation
        let timer = DispatchSource.makeTimerSource(queue: engine.queue)
        timer.schedule(deadline: .now() + .milliseconds(Int(max(0, afterMs))))
        timer.setEventHandler { [weak self] in
            guard let self, self.generation == token else { return }
            self.source?.cancel()
            self.source = nil
            fire()
        }
        source = timer
        timer.resume()
    }

    public func cancel() {
        generation += 1
        source?.cancel()
        source = nil
    }
}
