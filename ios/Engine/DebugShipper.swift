import Foundation

final class DebugShipper {
    private unowned let logger: DebugLogger
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 20
        configuration.urlCache = nil
        configuration.httpMaximumConnectionsPerHost = 1
        let queue = OperationQueue()
        queue.name = "expo-location-session.debug-ship"
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: configuration, delegate: RefuseRedirects(), delegateQueue: queue)
    }()

    private var inFlight = false
    private var sent = 0
    private var waiters: [(Int) -> Void] = []

    init(logger: DebugLogger) {
        self.logger = logger
    }

    func ship(_ completion: ((Int) -> Void)?) {
        dispatchPrecondition(condition: .onQueue(logger.queue))
        if let completion { waiters.append(completion) }
        guard !inFlight else { return }
        sent = 0
        sendNext()
    }

    private func sendNext() {
        guard let batch = logger.makeBatch() else {
            finish()
            return
        }
        inFlight = true
        let task = session.dataTask(with: batch.request) { [weak self] _, response, error in
            let status = error == nil ? (response as? HTTPURLResponse)?.statusCode : nil
            let message = error?.localizedDescription
            guard let self else { return }
            self.logger.queue.async {
                if let status, (200..<300).contains(status) {
                    self.logger.delivered(upTo: batch.toSeq)
                    self.sent += batch.count
                    self.sendNext()
                } else {
                    self.finish()
                    self.logger.shipFailed(status: status, error: message)
                }
            }
        }
        task.resume()
    }

    private func finish() {
        inFlight = false
        let done = waiters
        waiters.removeAll()
        let count = sent
        done.forEach { $0(count) }
    }
}
