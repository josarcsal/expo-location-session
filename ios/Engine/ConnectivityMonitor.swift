import Foundation
import Network

final class ConnectivityMonitor {
    private var monitor: NWPathMonitor?
    private var edge = ConnectivityEdge()
    private var pending: DispatchWorkItem?
    private var generation = 0

    func start(onRegained: @escaping () -> Void) {
        EngineQueue.shared.assertCurrent()
        guard monitor == nil else { return }
        generation += 1
        let current = generation
        edge = ConnectivityEdge()
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            guard let self, self.generation == current else { return }
            self.update(satisfied: path.status == .satisfied, onRegained: onRegained)
        }
        monitor = m
        m.start(queue: EngineQueue.shared.queue)
    }

    func stop() {
        EngineQueue.shared.assertCurrent()
        generation += 1
        monitor?.cancel()
        monitor = nil
        pending?.cancel()
        pending = nil
    }

    private func update(satisfied: Bool, onRegained: @escaping () -> Void) {
        if edge.update(satisfied: satisfied) {
            pending?.cancel()
            let current = generation
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.generation == current else { return }
                self.pending = nil
                onRegained()
            }
            pending = work
            EngineQueue.shared.queue.asyncAfter(deadline: .now() + 1, execute: work)
        } else if !satisfied {
            pending?.cancel()
            pending = nil
        }
    }
}
