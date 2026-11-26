import Foundation
import Testing
@testable import LocationSessionCore

@Suite struct EngineQueueTests {
    private func engine() -> EngineQueue { EngineQueue(label: "test.\(UUID())") }
    private func wait(_ s: DispatchSemaphore) -> Bool { s.wait(timeout: .now() + 2) == .success }

    @Test func asyncRunsOnTheEngineQueueOffMain() {
        let e = engine()
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var onEngine = false
        nonisolated(unsafe) var offMain = false
        e.async {
            onEngine = e.isCurrent
            offMain = !Thread.isMainThread
            done.signal()
        }
        #expect(wait(done))
        #expect(onEngine)
        #expect(offMain)
    }

    @MainActor @Test func isCurrentIsFalseOnMain() {
        #expect(!engine().isCurrent)
    }

    @Test func operationQueueRunsOnTheEngineQueue() {
        let e = engine()
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var onEngine = false
        e.operationQueue.addOperation {
            onEngine = e.isCurrent
            done.signal()
        }
        #expect(wait(done))
        #expect(onEngine)
    }

    @MainActor @Test func syncFromMainReturnsTheValueComputedOnTheEngine() {
        let e = engine()
        #expect(e.syncFromMain { e.isCurrent ? 42 : 0 } == 42)
    }

    @Test func syncFromMainRunsInlineWhenAlreadyOnTheEngine() {
        let e = engine()
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var v = 0
        e.async {
            v = e.syncFromMain { 7 }
            done.signal()
        }
        #expect(wait(done))
        #expect(v == 7)
    }

    @Test func timerFiresOnTheEngineQueueAfterTheDelay() {
        let e = engine()
        let t = EngineTimer(e)
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var onEngine = false
        nonisolated(unsafe) var elapsedMs = 0.0
        e.async {
            let start = DispatchTime.now().uptimeNanoseconds
            t.schedule(afterMs: 50) {
                onEngine = e.isCurrent
                elapsedMs = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
                done.signal()
            }
        }
        #expect(wait(done))
        #expect(onEngine)
        #expect(elapsedMs >= 50)
    }

    @Test func rescheduleReplacesThePendingFire() {
        let e = engine()
        let t = EngineTimer(e)
        nonisolated(unsafe) var a = 0
        nonisolated(unsafe) var b = 0
        e.async {
            t.schedule(afterMs: 50) { a += 1 }
            t.schedule(afterMs: 100) { b += 1 }
        }
        Thread.sleep(forTimeInterval: 0.4)
        let done = DispatchSemaphore(value: 0)
        e.async { done.signal() }
        #expect(wait(done))
        #expect(a == 0)
        #expect(b == 1)
    }

    @Test func cancelPreventsTheFire() {
        let e = engine()
        let t = EngineTimer(e)
        nonisolated(unsafe) var count = 0
        nonisolated(unsafe) var scheduled = true
        e.async {
            t.schedule(afterMs: 50) { count += 1 }
            t.cancel()
        }
        Thread.sleep(forTimeInterval: 0.3)
        let done = DispatchSemaphore(value: 0)
        e.async {
            scheduled = t.isScheduled
            done.signal()
        }
        #expect(wait(done))
        #expect(count == 0)
        #expect(!scheduled)
    }

    @Test func isScheduledClearsAfterTheFire() {
        let e = engine()
        let t = EngineTimer(e)
        let fired = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var right = false
        nonisolated(unsafe) var inside = true
        nonisolated(unsafe) var after = true
        e.async {
            t.schedule(afterMs: 20) {
                inside = t.isScheduled
                fired.signal()
            }
            right = t.isScheduled
        }
        #expect(wait(fired))
        let done = DispatchSemaphore(value: 0)
        e.async {
            after = t.isScheduled
            done.signal()
        }
        #expect(wait(done))
        #expect(right)
        #expect(!inside)
        #expect(!after)
    }

    @MainActor @Test func timerFiresWhileMainIsBlocked() {
        let e = engine()
        let t = EngineTimer(e)
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var firedAt = DispatchTime.distantFuture
        e.async {
            t.schedule(afterMs: 20) {
                firedAt = DispatchTime.now()
                done.signal()
            }
        }
        Thread.sleep(forTimeInterval: 0.3)
        let unblockedAt = DispatchTime.now()
        #expect(wait(done))
        #expect(firedAt < unblockedAt)
    }
}
