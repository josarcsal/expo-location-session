package com.margelo.nitro.locationsession.core

class PendingSlot {
    var fix: Fix? = null
        private set
    private var generation = 0

    fun put(f: Fix): Int {
        generation += 1
        fix = f
        return generation
    }

    fun markSent(sent: Int) {
        if (sent == generation) fix = null
    }
}
