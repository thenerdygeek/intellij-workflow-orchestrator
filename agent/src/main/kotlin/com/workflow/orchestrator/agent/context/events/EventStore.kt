package com.workflow.orchestrator.agent.context.events

import java.io.File
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only event store with monotonic gapless IDs.
 *
 * This is the single source of truth for conversation history in the event-sourced
 * context management system. Events are immutable once added -- the store assigns
 * ID, timestamp, and source via [add], returning a new copy with metadata set.
 *
 * Thread safety: [add] uses a [ReentrantLock] so background agents can safely append
 * events concurrently. Read operations ([get], [slice], [all], [size]) are lock-free
 * and operate on snapshot copies or volatile reads.
 *
 * JSONL persistence: [appendToJsonl] incrementally writes only new events since the
 * last persist. [loadFromJsonl] rebuilds the store from disk for session resume.
 *
 * @param sessionDir optional directory for JSONL persistence. If null, persistence
 *                   operations are no-ops.
 */
class EventStore(private val sessionDir: File? = null) {

    private val lock = ReentrantLock()

    /** All events in insertion order. Index == event ID. */
    private val events = mutableListOf<Event>()

    /** Next ID to assign (== events.size, maintained under lock). */
    private var nextId: Int = 0

    /** Number of events already written to JSONL. */
    private var persistedCount: Int = 0

    /**
     * Append an event to the store, assigning a monotonic gapless ID, current timestamp,
     * and the given [source].
     *
     * The original event is not mutated -- a new copy is created via data class `copy()`.
     *
     * @return the event copy with [Event.id], [Event.timestamp], and [Event.source] set
     */
    fun add(event: Event, source: EventSource): Event = lock.withLock {
        val id = nextId++
        val now = Instant.now()
        val assigned = assignMetadata(event, id, now, source)
        events.add(assigned)
        assigned
    }

    /**
     * Retrieve an event by its ID. O(1) index lookup.
     *
     * @return the event, or null if [id] is out of range
     */
    fun get(id: Int): Event? {
        if (id < 0 || id >= events.size) return null
        return events[id]
    }

    /**
     * Return a sublist of events in the range [startId, endId).
     *
     * @return list of events, empty if the range is invalid or empty
     */
    fun slice(startId: Int, endId: Int): List<Event> {
        val safeStart = startId.coerceAtLeast(0)
        val safeEnd = endId.coerceAtMost(events.size)
        if (safeStart >= safeEnd) return emptyList()
        return events.subList(safeStart, safeEnd).toList()
    }

    /**
     * Current number of events in the store.
     */
    fun size(): Int = events.size

    /**
     * Snapshot copy of all events. The returned list is a new ArrayList
     * and can be safely iterated while new events are being added.
     */
    fun all(): List<Event> = ArrayList(events)

    /**
     * Incrementally append new events (since last persist) to the JSONL file.
     *
     * Each event is written as a single JSON line. The file is opened in append mode.
     * If [sessionDir] is null, this is a no-op.
     */
    fun appendToJsonl() {
        val dir = sessionDir ?: return

        // Snapshot the slice to write under lock, then perform file I/O outside the lock
        // to avoid holding the lock during potentially slow disk writes.
        val toWrite = lock.withLock {
            if (persistedCount >= events.size) return
            val slice = events.subList(persistedCount, events.size).toList()
            persistedCount = events.size
            slice
        }

        dir.mkdirs()
        val file = File(dir, JSONL_FILENAME)
        file.appendText(toWrite.joinToString("\n") { EventSerializer.serialize(it) } + "\n")
    }

    /**
     * Alias for [appendToJsonl].
     */
    fun flush() = appendToJsonl()

    companion object {
        const val JSONL_FILENAME = "events.jsonl"

        /**
         * Rebuild an [EventStore] from a JSONL file on disk.
         *
         * Each line in the file is deserialized as an event. The store's [nextId]
         * is restored to `max(event.id) + 1` (or `lineCount` if IDs are gapless).
         *
         * @param sessionDir the directory containing [JSONL_FILENAME]
         * @return a new EventStore with all events loaded and persistedCount set
         */
        fun loadFromJsonl(sessionDir: File): EventStore {
            val store = EventStore(sessionDir)
            val file = File(sessionDir, JSONL_FILENAME)
            if (!file.exists()) return store

            file.useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val event = EventSerializer.deserialize(line)
                    store.events.add(event)
                }
            }

            store.nextId = if (store.events.isEmpty()) 0
            else store.events.maxOf { it.id } + 1
            store.persistedCount = store.events.size

            return store
        }
    }

    // -------------------------------------------------------------------------
    // Internal: assign metadata via data class copy
    // -------------------------------------------------------------------------

    private fun assignMetadata(event: Event, id: Int, timestamp: Instant, source: EventSource): Event =
        when (event) {
            is MessageAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is SystemMessageAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is UserSteeringAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is AgentThinkAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is AgentFinishAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is DelegateAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is CondensationAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is CondensationRequestAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is FileReadAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is FileEditAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is CommandRunAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is SearchCodeAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is DiagnosticsAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is GenericToolAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is MetaToolAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is FactRecordedAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is PlanUpdatedAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is SkillActivatedAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is SkillDeactivatedAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is GuardrailRecordedAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is MentionAction -> event.copy(id = id, timestamp = timestamp, source = source)
            is ToolResultObservation -> event.copy(id = id, timestamp = timestamp, source = source)
            is CondensationObservation -> event.copy(id = id, timestamp = timestamp, source = source)
            is ErrorObservation -> event.copy(id = id, timestamp = timestamp, source = source)
            is SuccessObservation -> event.copy(id = id, timestamp = timestamp, source = source)
        }
}
