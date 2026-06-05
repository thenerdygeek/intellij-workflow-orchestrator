package com.workflow.orchestrator.agent.monitor

/** Pure list-by-key diff shared by sprint/issue monitors. */
object ListDiff {
    data class Changes<T>(val added: List<T>, val removed: List<T>, val retained: List<Pair<T, T>>)  // retained = (previous, current)
    fun <T> byKey(previous: List<T>, current: List<T>, keyOf: (T) -> String): Changes<T> {
        val prev = previous.associateBy(keyOf); val cur = current.associateBy(keyOf)
        val added = (cur.keys - prev.keys).map { cur.getValue(it) }
        val removed = (prev.keys - cur.keys).map { prev.getValue(it) }
        val retained = (cur.keys intersect prev.keys).map { prev.getValue(it) to cur.getValue(it) }
        return Changes(added, removed, retained)
    }
}
