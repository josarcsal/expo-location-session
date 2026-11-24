package com.margelo.nitro.locationsession.core

class BoundedBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()

    val count: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    fun append(x: T): Boolean {
        items.addLast(x)
        if (items.size <= capacity) return false
        while (items.size > capacity) items.removeFirst()
        return true
    }

    fun drain(): List<T> {
        val all = items.toList()
        items.clear()
        return all
    }

    fun flush(insert: (T) -> Boolean): Int {
        var inserted = 0
        while (items.isNotEmpty() && insert(items.first())) {
            items.removeFirst()
            inserted += 1
        }
        return inserted
    }

    fun removeAll(match: (T) -> Boolean): Int {
        val before = items.size
        items.removeAll(match)
        return before - items.size
    }
}
