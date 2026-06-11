package com.workflow.orchestrator.agent.ui

/**
 * Bounded, thread-safe FIFO ring used for [AgentDashboardPanel]'s mirror replay log
 * (audit P2-2 + bug B7).
 *
 * [add] evicts the OLDEST entry on overflow so the NEWEST entries are always retained.
 * This is the inverse of the inverted cap it replaced (`if (size < max) add(...)`),
 * which stopped ADDING once full — a late-joining editor-tab mirror then replayed the
 * oldest 5000 actions and silently missed everything newer (B7).
 *
 * Backed by [ArrayDeque] under a plain lock: O(1) add/evict versus the previous
 * `CopyOnWriteArrayList`'s full array copy on every add (one add per batched stream
 * token — P2-2).
 *
 * Behaviour pinned by `ReplayRingTest`.
 */
class ReplayRing<T>(capacity: Int) {

    private val capacity = capacity.coerceAtLeast(1)
    private val deque = ArrayDeque<T>()
    private val lock = Any()

    /** Append [item]; if the ring is full, the OLDEST entry is evicted first. */
    fun add(item: T) {
        synchronized(lock) {
            if (deque.size >= capacity) deque.removeFirst()
            deque.addLast(item)
        }
    }

    /** Defensive copy in insertion (oldest → newest) order. */
    fun snapshot(): List<T> = synchronized(lock) { deque.toList() }

    fun clear() {
        synchronized(lock) { deque.clear() }
    }

    fun size(): Int = synchronized(lock) { deque.size }
}
