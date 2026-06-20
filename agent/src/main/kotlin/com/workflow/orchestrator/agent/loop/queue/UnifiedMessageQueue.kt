package com.workflow.orchestrator.agent.loop.queue

/**
 * One per-session queue carrying all four async sources. Drains at loop boundaries, ordered by
 * (timestamp asc, priority desc) and grouped by source (groups in earliest-item order). Guarded by
 * a plain JVM monitor — enqueue/remove/clear are called synchronously from EDT and IO threads, so
 * the guard must NOT be a coroutine Mutex. Durable items are persisted on every mutation.
 */
class UnifiedMessageQueue(
    private val sessionId: String,
    private val persistence: QueuePersistence?,
) {
    private val lock = Any()
    private val items = ArrayList<QueuedMessage>()

    init {
        persistence?.load(sessionId)?.let { synchronized(lock) { items.addAll(it) } }
    }

    fun enqueue(msg: QueuedMessage): Boolean = synchronized(lock) {
        val key = msg.coalesceKey
        val idx = if (key == null) -1 else items.indexOfFirst { it.kind == msg.kind && it.coalesceKey == key }
        if (idx >= 0) items[idx] = msg else items.add(msg)
        persist()
        true
    }

    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }

    fun remove(id: String): Boolean = synchronized(lock) {
        val removed = items.removeIf { it.id == id }
        if (removed) persist()
        removed
    }

    fun pendingIds(): List<String> = synchronized(lock) { items.map { it.id } }

    fun clear(ids: List<String>) = synchronized(lock) {
        val idSet = ids.toHashSet()
        if (items.removeIf { it.id in idSet }) persist()
    }

    /** Drop every pending item in one locked op (no `pendingIds()` round-trip). */
    fun clearAll() = synchronized(lock) {
        if (items.isNotEmpty()) {
            items.clear()
            persist()
        }
    }

    /** Sort by (timestamp asc, priority desc), group by kind in first-appearance order, frame, clear. */
    fun drainGrouped(): List<DrainGroup> = synchronized(lock) {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedWith(compareBy<QueuedMessage> { it.timestamp }.thenByDescending { it.priority })
        items.clear()
        persist()
        val byKind = LinkedHashMap<QueueSourceKind, MutableList<QueuedMessage>>()
        for (m in sorted) byKind.getOrPut(m.kind) { mutableListOf() }.add(m)
        byKind.map { (kind, group) ->
            val policy = QueueSourceRegistry.policyFor(kind)
            DrainGroup(
                kind = kind,
                framedText = policy.frame(group),
                ids = group.map { it.id },
                resetsUserSilenceCounter = policy.resetsUserSilenceCounter,
                defersCompletion = policy.defersCompletion,
                items = group,
            )
        }
    }

    /** Persist only the durable subset (USER items stay in-memory). Caller holds [lock]. */
    private fun persist() {
        val p = persistence ?: return
        p.save(sessionId, items.filter { QueueSourceRegistry.policyFor(it.kind).durable })
    }
}
