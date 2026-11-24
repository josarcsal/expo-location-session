import Foundation
import Network

final class ConnectivityMonitor {
    private var monitor: NWPathMonitor?
    private var edge = ConnectivityEdge()
    private var pending: DispatchWorkItem?
    private var generation = 0

    func start(onRegained: @escaping () -> Void) {
        dispatchPrecondition(condition: .onQueue(.main))
        guard monitor == nil else { return }
        generation += 1
        let current = generation
        edge = ConnectivityEdge()
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            DispatchQueue.main.async {
                guard let self, self.generation == current else { return }
                self.update(satisfied: satisfied, onRegained: onRegained)
            }
        }
        monitor = m
        m.start(queue: DispatchQueue(label: "expo-location-session.connectivity"))
    }

    func stop() {
        dispatchPrecondition(condition: .onQueue(.main))
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
            DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
        } else if !satisfied {
            pending?.cancel()
            pending = nil
        }
    }
}
