# Event-Sourced Context Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the mutation-based ContextManager with an event-sourced context management system modeled after OpenHands, using a composable condenser pipeline with token-aware triggers.

**Architecture:** Three layers — EventStore (append-only) → View (filtered projection) → ConversationMemory (event→message). Four-stage condenser pipeline: ConversationWindow → SmartPruner → ObservationMasking → LLMSummarizing. Events are immutable; compression produces CondensationAction events that the View layer interprets.

**Tech Stack:** Kotlin 2.1.10, kotlinx.serialization (JSON), JUnit 5 + MockK, IntelliJ Platform SDK, OkHttp (Sourcegraph API)

**Spec:** `docs/superpowers/specs/2026-03-31-event-sourced-context-management-design.md`

**Branch:** `feature/context-management` (worktree: `.worktrees/context-management`)

**Base path:** `agent/src/main/kotlin/com/workflow/orchestrator/agent`
**Test path:** `agent/src/test/kotlin/com/workflow/orchestrator/agent`

---

## Phase 1: Event Model + EventStore + View (Foundation)

No external dependencies. Pure data structures and algorithms. Everything else builds on this.

### Task 1: Event Hierarchy

**Files:**
- Create: `context/events/Event.kt`
- Create: `context/events/Actions.kt`
- Create: `context/events/Observations.kt`
- Test: `context/events/EventTest.kt`

- [ ] **Step 1: Write tests for Event hierarchy**

```kotlin
// EventTest.kt
class EventTest {
    @Test
    fun `CondensationAction with explicit IDs returns correct forgotten list`() {
        val action = CondensationAction(
            forgottenEventIds = listOf(3, 4, 5, 6)
        )
        assertEquals(listOf(3, 4, 5, 6), action.forgotten)
    }

    @Test
    fun `CondensationAction with range returns correct forgotten list`() {
        val action = CondensationAction(
            forgottenEventsStartId = 3,
            forgottenEventsEndId = 6
        )
        assertEquals(listOf(3, 4, 5, 6), action.forgotten)
    }

    @Test
    fun `CondensationAction validates summary and summaryOffset are paired`() {
        // summary without offset should fail
        assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = listOf(1),
                summary = "test",
                summaryOffset = null
            ).validate()
        }
    }

    @Test
    fun `CondensationAction validates exactly one mode is set`() {
        // both explicit and range should fail
        assertThrows<IllegalArgumentException> {
            CondensationAction(
                forgottenEventIds = listOf(1),
                forgottenEventsStartId = 1,
                forgottenEventsEndId = 2
            ).validate()
        }
    }

    @Test
    fun `ToolAction subtypes carry toolCallId and responseGroupId`() {
        val action = FileReadAction(
            path = "/src/Main.kt",
            toolCallId = "tc_123",
            responseGroupId = "rg_456"
        )
        assertEquals("tc_123", action.toolCallId)
        assertEquals("rg_456", action.responseGroupId)
        assertEquals("/src/Main.kt", action.path)
    }

    @Test
    fun `NEVER_FORGET_TYPES contains all compression-proof types`() {
        assertTrue(FactRecordedAction::class in NEVER_FORGET_TYPES)
        assertTrue(PlanUpdatedAction::class in NEVER_FORGET_TYPES)
        assertTrue(SkillActivatedAction::class in NEVER_FORGET_TYPES)
        assertTrue(GuardrailRecordedAction::class in NEVER_FORGET_TYPES)
        assertTrue(MentionAction::class in NEVER_FORGET_TYPES)
        assertFalse(MessageAction::class in NEVER_FORGET_TYPES)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*EventTest*" -x composedJar`
Expected: Compilation failure — classes don't exist yet

- [ ] **Step 3: Implement Event hierarchy**

Create `context/events/Event.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.events

import java.time.Instant
import kotlin.reflect.KClass

enum class EventSource { AGENT, USER, SYSTEM }

sealed interface Event {
    val id: Int
    val timestamp: Instant
    val source: EventSource
}

sealed interface Action : Event
sealed interface Observation : Event {
    val content: String
}

// Mutable wrapper used only during EventStore.add() to assign id/timestamp
data class EventMetadata(
    var id: Int = -1,
    var timestamp: Instant = Instant.EPOCH,
    var source: EventSource = EventSource.SYSTEM
)

val NEVER_FORGET_TYPES: Set<KClass<out Event>> = setOf(
    FactRecordedAction::class,
    PlanUpdatedAction::class,
    SkillActivatedAction::class,
    GuardrailRecordedAction::class,
    MentionAction::class
)
```

Create `context/events/Actions.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.events

import java.time.Instant

// ── Messages ──
data class MessageAction(
    val content: String,
    val imageUrls: List<String>? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.USER
) : Action

data class SystemMessageAction(
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class AgentThinkAction(
    val thought: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

data class AgentFinishAction(
    val finalThought: String,
    val outputs: Map<String, String> = emptyMap(),
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

data class DelegateAction(
    val agentType: String,
    val prompt: String,
    val thought: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : Action

// ── Condensation ──
data class CondensationAction(
    val forgottenEventIds: List<Int>? = null,
    val forgottenEventsStartId: Int? = null,
    val forgottenEventsEndId: Int? = null,
    val summary: String? = null,
    val summaryOffset: Int? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action {
    val forgotten: List<Int>
        get() = forgottenEventIds ?: (forgottenEventsStartId!!..forgottenEventsEndId!!).toList()

    fun validate() {
        val hasExplicit = forgottenEventIds != null
        val hasRange = forgottenEventsStartId != null || forgottenEventsEndId != null
        require(hasExplicit xor hasRange) { "Exactly one of explicit IDs or range must be set" }
        if (hasRange) {
            requireNotNull(forgottenEventsStartId) { "Range start must be set" }
            requireNotNull(forgottenEventsEndId) { "Range end must be set" }
        }
        val hasSummary = summary != null
        val hasOffset = summaryOffset != null
        require(hasSummary == hasOffset) { "summary and summaryOffset must both be set or both null" }
    }
}

data class CondensationRequestAction(
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

// ── Typed Tool Actions ──
sealed interface ToolAction : Action {
    val toolCallId: String
    val responseGroupId: String
}

data class FileReadAction(
    val path: String,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class FileEditAction(
    val path: String,
    val oldStr: String? = null,
    val newStr: String? = null,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class CommandRunAction(
    val command: String,
    val cwd: String? = null,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class SearchCodeAction(
    val query: String,
    val path: String? = null,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class DiagnosticsAction(
    val path: String? = null,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class GenericToolAction(
    val toolName: String,
    val arguments: String,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

data class MetaToolAction(
    val toolName: String,
    val actionName: String,
    val arguments: String,
    override val toolCallId: String,
    override val responseGroupId: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.AGENT
) : ToolAction

// ── Compression-Proof Actions ──
data class FactRecordedAction(
    val factType: String,
    val path: String? = null,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class PlanUpdatedAction(
    val planJson: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class SkillActivatedAction(
    val skillName: String,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class SkillDeactivatedAction(
    val skillName: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class GuardrailRecordedAction(
    val rule: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action

data class MentionAction(
    val paths: List<String>,
    val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Action
```

Create `context/events/Observations.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.events

import java.time.Instant

data class ToolResultObservation(
    val toolCallId: String,
    override val content: String,
    val isError: Boolean,
    val toolName: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

data class CondensationObservation(
    override val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

data class ErrorObservation(
    override val content: String,
    val errorId: String? = null,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation

data class SuccessObservation(
    override val content: String,
    override val id: Int = -1,
    override val timestamp: Instant = Instant.EPOCH,
    override val source: EventSource = EventSource.SYSTEM
) : Observation
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*EventTest*" -x composedJar`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/
git commit -m "feat(agent): add event-sourced event hierarchy — actions, observations, NEVER_FORGET_TYPES"
```

---

### Task 2: EventStore

**Files:**
- Create: `context/events/EventStore.kt`
- Test: `context/events/EventStoreTest.kt`

- [ ] **Step 1: Write tests for EventStore**

```kotlin
class EventStoreTest {
    @Test
    fun `add assigns monotonic gapless IDs starting at 0`() {
        val store = EventStore()
        val e1 = store.add(MessageAction(content = "hello"), EventSource.USER)
        val e2 = store.add(MessageAction(content = "world"), EventSource.AGENT)
        assertEquals(0, e1.id)
        assertEquals(1, e2.id)
    }

    @Test
    fun `add sets timestamp`() {
        val store = EventStore()
        val before = Instant.now()
        val event = store.add(MessageAction(content = "test"), EventSource.USER)
        val after = Instant.now()
        assertTrue(event.timestamp >= before)
        assertTrue(event.timestamp <= after)
    }

    @Test
    fun `add sets source`() {
        val store = EventStore()
        val event = store.add(MessageAction(content = "test"), EventSource.AGENT)
        assertEquals(EventSource.AGENT, event.source)
    }

    @Test
    fun `get returns event by ID`() {
        val store = EventStore()
        store.add(MessageAction(content = "zero"), EventSource.USER)
        store.add(MessageAction(content = "one"), EventSource.AGENT)
        val retrieved = store.get(1)
        assertNotNull(retrieved)
        assertTrue(retrieved is MessageAction)
        assertEquals("one", (retrieved as MessageAction).content)
    }

    @Test
    fun `get returns null for invalid ID`() {
        val store = EventStore()
        assertNull(store.get(99))
    }

    @Test
    fun `all returns snapshot of all events`() {
        val store = EventStore()
        store.add(MessageAction(content = "a"), EventSource.USER)
        store.add(MessageAction(content = "b"), EventSource.AGENT)
        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(0, all[0].id)
        assertEquals(1, all[1].id)
    }

    @Test
    fun `size tracks event count`() {
        val store = EventStore()
        assertEquals(0, store.size())
        store.add(MessageAction(content = "test"), EventSource.USER)
        assertEquals(1, store.size())
    }

    @Test
    fun `JSONL persistence round-trips`(@TempDir tempDir: File) {
        val store = EventStore(sessionDir = tempDir)
        store.add(MessageAction(content = "hello"), EventSource.USER)
        store.add(CondensationRequestAction(), EventSource.SYSTEM)
        store.flush()

        val loaded = EventStore.loadFromJsonl(tempDir)
        assertEquals(2, loaded.size())
        val first = loaded.get(0)
        assertTrue(first is MessageAction)
        assertEquals("hello", (first as MessageAction).content)
        val second = loaded.get(1)
        assertTrue(second is CondensationRequestAction)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*EventStoreTest*" -x composedJar`
Expected: Compilation failure

- [ ] **Step 3: Implement EventStore**

Create `context/events/EventStore.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.events

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EventStore(private val sessionDir: File? = null) {
    private val events: MutableList<Event> = mutableListOf()
    private var nextId: Int = 0
    private val lock = ReentrantLock()
    private var persistedCount: Int = 0

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    fun add(event: Event, source: EventSource): Event = lock.withLock {
        val id = nextId++
        val timestamp = Instant.now()
        val withMeta = assignMetadata(event, id, timestamp, source)
        events.add(withMeta)
        withMeta
    }

    fun get(id: Int): Event? {
        if (id < 0 || id >= events.size) return null
        return events[id]
    }

    fun slice(startId: Int, endId: Int): List<Event> {
        val start = startId.coerceAtLeast(0)
        val end = endId.coerceAtMost(events.size)
        if (start >= end) return emptyList()
        return events.subList(start, end).toList()
    }

    fun size(): Int = events.size

    fun all(): List<Event> = events.toList()

    fun appendToJsonl() {
        val dir = sessionDir ?: return
        val file = File(dir, "events.jsonl")
        val newEvents = events.subList(persistedCount, events.size)
        if (newEvents.isEmpty()) return
        file.appendText(newEvents.joinToString("\n") { EventSerializer.serialize(it, json) } + "\n")
        persistedCount = events.size
    }

    fun flush() = appendToJsonl()

    companion object {
        fun loadFromJsonl(sessionDir: File): EventStore {
            val store = EventStore(sessionDir)
            val file = File(sessionDir, "events.jsonl")
            if (!file.exists()) return store
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                val event = EventSerializer.deserialize(line, json)
                if (event != null) {
                    store.events.add(event)
                    store.nextId = maxOf(store.nextId, event.id + 1)
                }
            }
            store.persistedCount = store.events.size
            return store
        }
    }
}
```

Note: `assignMetadata()` uses Kotlin's `copy()` on each data class to set id/timestamp/source. `EventSerializer` handles polymorphic serialization with a `type` discriminator field. These are implementation details the worker will fill in — the key design is: each event type gets a `type` string for JSON discrimination, and `copy()` is used to set metadata fields since data classes are immutable.

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*EventStoreTest*" -x composedJar`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/EventStore.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/EventStoreTest.kt
git commit -m "feat(agent): add EventStore — append-only event stream with JSONL persistence"
```

---

### Task 3: View

**Files:**
- Create: `context/events/View.kt`
- Test: `context/events/ViewTest.kt`

- [ ] **Step 1: Write tests for View.fromEvents()**

```kotlin
class ViewTest {
    @Test
    fun `fromEvents with no condensation returns all events`() {
        val events = listOf(
            systemMsg(0, "prompt"),
            userMsg(1, "hello"),
            agentMsg(2, "hi")
        )
        val view = View.fromEvents(events)
        assertEquals(3, view.size)
        assertFalse(view.unhandledCondensationRequest)
        assertTrue(view.forgottenEventIds.isEmpty())
    }

    @Test
    fun `fromEvents filters out forgotten events from CondensationAction`() {
        val events = listOf(
            systemMsg(0, "prompt"),
            userMsg(1, "hello"),
            agentMsg(2, "response1"),
            userMsg(3, "more"),
            agentMsg(4, "response2"),
            condensationAction(5, forgottenIds = listOf(2, 3, 4), summary = "Summary of turns 2-4", summaryOffset = 1)
        )
        val view = View.fromEvents(events)
        // Kept: event 0 (system), event 1 (user). Events 2,3,4 forgotten. Event 5 (condensation) self-forgotten.
        // Summary inserted at offset 1 in kept events.
        assertEquals(3, view.size) // system + summary + user(1)
        assertTrue(view.events[1] is CondensationObservation)
        assertEquals("Summary of turns 2-4", (view.events[1] as CondensationObservation).content)
        assertEquals(setOf(2, 3, 4, 5), view.forgottenEventIds)
    }

    @Test
    fun `fromEvents detects unhandled condensation request`() {
        val events = listOf(
            systemMsg(0, "prompt"),
            userMsg(1, "hello"),
            condensationRequest(2)
        )
        val view = View.fromEvents(events)
        assertTrue(view.unhandledCondensationRequest)
        // CondensationRequestAction itself is filtered out
        assertEquals(2, view.size)
    }

    @Test
    fun `fromEvents does not flag handled condensation request`() {
        val events = listOf(
            systemMsg(0, "prompt"),
            condensationRequest(1),
            condensationAction(2, forgottenIds = listOf(1), summary = null, summaryOffset = null)
        )
        val view = View.fromEvents(events)
        assertFalse(view.unhandledCondensationRequest)
    }

    @Test
    fun `fromEvents uses last CondensationAction summary`() {
        val events = listOf(
            systemMsg(0, "prompt"),
            userMsg(1, "hello"),
            condensationAction(2, forgottenIds = listOf(1), summary = "Old summary", summaryOffset = 1),
            userMsg(3, "more"),
            condensationAction(4, forgottenIds = listOf(3), summary = "New summary", summaryOffset = 1)
        )
        val view = View.fromEvents(events)
        val summaries = view.events.filterIsInstance<CondensationObservation>()
        assertEquals(1, summaries.size)
        assertEquals("New summary", summaries[0].content)
    }

    // Helper functions to create events with pre-assigned IDs
    private fun systemMsg(id: Int, content: String) = SystemMessageAction(content = content, id = id, timestamp = Instant.now(), source = EventSource.SYSTEM)
    private fun userMsg(id: Int, content: String) = MessageAction(content = content, id = id, timestamp = Instant.now(), source = EventSource.USER)
    private fun agentMsg(id: Int, content: String) = MessageAction(content = content, id = id, timestamp = Instant.now(), source = EventSource.AGENT)
    private fun condensationAction(id: Int, forgottenIds: List<Int>, summary: String?, summaryOffset: Int?) =
        CondensationAction(forgottenEventIds = forgottenIds, summary = summary, summaryOffset = summaryOffset, id = id, timestamp = Instant.now(), source = EventSource.SYSTEM)
    private fun condensationRequest(id: Int) = CondensationRequestAction(id = id, timestamp = Instant.now(), source = EventSource.SYSTEM)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*ViewTest*" -x composedJar`
Expected: Compilation failure

- [ ] **Step 3: Implement View**

Create `context/events/View.kt` — the `fromEvents()` algorithm identical to OpenHands:

```kotlin
package com.workflow.orchestrator.agent.context.events

data class View(
    val events: List<Event>,
    val unhandledCondensationRequest: Boolean = false,
    val forgottenEventIds: Set<Int> = emptySet()
) {
    val size: Int get() = events.size

    operator fun get(index: Int): Event = events[index]

    companion object {
        fun fromEvents(history: List<Event>): View {
            // Step 1: Collect forgotten IDs
            val forgottenIds = mutableSetOf<Int>()
            for (event in history) {
                when (event) {
                    is CondensationAction -> {
                        forgottenIds.addAll(event.forgotten)
                        forgottenIds.add(event.id)
                    }
                    is CondensationRequestAction -> {
                        forgottenIds.add(event.id)
                    }
                    else -> {}
                }
            }

            // Step 2: Filter to kept events
            val keptEvents = history.filter { it.id !in forgottenIds }.toMutableList()

            // Step 3: Find last CondensationAction with summary+offset, insert summary
            for (event in history.reversed()) {
                if (event is CondensationAction && event.summary != null && event.summaryOffset != null) {
                    val summaryObs = CondensationObservation(content = event.summary)
                    val insertAt = event.summaryOffset.coerceAtMost(keptEvents.size)
                    keptEvents.add(insertAt, summaryObs)
                    break
                }
            }

            // Step 4: Detect unhandled condensation request
            var unhandled = false
            for (event in history.reversed()) {
                when (event) {
                    is CondensationAction -> break
                    is CondensationRequestAction -> { unhandled = true; break }
                    else -> {}
                }
            }

            return View(
                events = keptEvents,
                unhandledCondensationRequest = unhandled,
                forgottenEventIds = forgottenIds
            )
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*ViewTest*" -x composedJar`
Expected: All 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/events/View.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/context/events/ViewTest.kt
git commit -m "feat(agent): add View — filtered projection with fromEvents() matching OpenHands"
```

---

## Phase 2: Condenser Pipeline

### Task 4: Condenser Interfaces + NoOp + Pipeline

**Files:**
- Create: `context/condenser/Condenser.kt`
- Create: `context/condenser/CondenserPipeline.kt`
- Create: `context/condenser/NoOpCondenser.kt`
- Test: `context/condenser/CondenserPipelineTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
class CondenserPipelineTest {
    @Test
    fun `NoOpCondenser returns view unchanged`() {
        val view = View(events = listOf(userMsg(0, "hello")))
        val context = CondenserContext(view = view, tokenUtilization = 0.5, effectiveBudget = 190000, currentTokens = 95000)
        val result = NoOpCondenser().condense(context)
        assertTrue(result is CondenserView)
        assertEquals(view, (result as CondenserView).view)
    }

    @Test
    fun `pipeline passes view between stages`() {
        // Two NoOps should just pass through
        val pipeline = CondenserPipeline(listOf(NoOpCondenser(), NoOpCondenser()))
        val view = View(events = listOf(userMsg(0, "hello")))
        val context = CondenserContext(view = view, tokenUtilization = 0.5, effectiveBudget = 190000, currentTokens = 95000)
        val result = pipeline.condense(context)
        assertTrue(result is CondenserView)
        assertEquals(1, (result as CondenserView).view.size)
    }

    @Test
    fun `pipeline short-circuits on Condensation`() {
        val alwaysCondense = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult =
                Condensation(CondensationAction(forgottenEventIds = listOf(0)))
        }
        val shouldNotRun = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult {
                throw AssertionError("Should not have been called")
            }
        }
        val pipeline = CondenserPipeline(listOf(alwaysCondense, shouldNotRun))
        val view = View(events = listOf(userMsg(0, "hello")))
        val context = CondenserContext(view = view, tokenUtilization = 0.9, effectiveBudget = 190000, currentTokens = 171000)
        val result = pipeline.condense(context)
        assertTrue(result is Condensation)
    }

    @Test
    fun `pipeline updates view between stages`() {
        // First condenser filters events, second sees filtered view
        val filterCondenser = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult {
                val filtered = context.view.events.filterIsInstance<MessageAction>()
                return CondenserView(View(events = filtered))
            }
        }
        val countChecker = object : Condenser {
            var receivedCount = 0
            override fun condense(context: CondenserContext): CondenserResult {
                receivedCount = context.view.size
                return CondenserView(context.view)
            }
        }
        val events = listOf(
            SystemMessageAction(content = "sys", id = 0, timestamp = java.time.Instant.now(), source = EventSource.SYSTEM),
            MessageAction(content = "hello", id = 1, timestamp = java.time.Instant.now(), source = EventSource.USER)
        )
        val pipeline = CondenserPipeline(listOf(filterCondenser, countChecker))
        val context = CondenserContext(view = View(events = events), tokenUtilization = 0.5, effectiveBudget = 190000, currentTokens = 95000)
        pipeline.condense(context)
        assertEquals(1, countChecker.receivedCount) // Only MessageAction passed through
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*CondenserPipelineTest*" -x composedJar`

- [ ] **Step 3: Implement Condenser interfaces, NoOp, Pipeline**

Create `context/condenser/Condenser.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*

sealed interface CondenserResult
data class CondenserView(val view: View) : CondenserResult
data class Condensation(val action: CondensationAction) : CondenserResult

data class CondenserContext(
    val view: View,
    val tokenUtilization: Double,
    val effectiveBudget: Int,
    val currentTokens: Int
)

interface Condenser {
    fun condense(context: CondenserContext): CondenserResult
}

abstract class RollingCondenser : Condenser {
    abstract fun shouldCondense(context: CondenserContext): Boolean
    abstract fun getCondensation(context: CondenserContext): Condensation

    override fun condense(context: CondenserContext): CondenserResult {
        return if (shouldCondense(context)) {
            getCondensation(context)
        } else {
            CondenserView(context.view)
        }
    }
}
```

Create `context/condenser/NoOpCondenser.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.condenser

class NoOpCondenser : Condenser {
    override fun condense(context: CondenserContext): CondenserResult {
        return CondenserView(context.view)
    }
}
```

Create `context/condenser/CondenserPipeline.kt`:
```kotlin
package com.workflow.orchestrator.agent.context.condenser

class CondenserPipeline(private val condensers: List<Condenser>) : Condenser {
    override fun condense(context: CondenserContext): CondenserResult {
        var currentContext = context
        for (condenser in condensers) {
            when (val result = condenser.condense(currentContext)) {
                is Condensation -> return result
                is CondenserView -> currentContext = currentContext.copy(view = result.view)
            }
        }
        return CondenserView(currentContext.view)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*CondenserPipelineTest*" -x composedJar`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/condenser/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/context/condenser/
git commit -m "feat(agent): add Condenser interfaces, NoOp, and Pipeline — matching OpenHands pattern"
```

---

### Task 5: ConversationWindowCondenser

**Files:**
- Create: `context/condenser/ConversationWindowCondenser.kt`
- Test: `context/condenser/ConversationWindowCondenserTest.kt`

- [ ] **Step 1: Write tests**

Tests should cover: triggers only on unhandledCondensationRequest, keeps essential initial events (SystemMessage + first user MessageAction), keeps roughly half of remaining events from tail, handles dangling observations, produces contiguous range when possible. Use helper functions from ViewTest pattern.

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement** — faithful port of OpenHands' `conversation_window_condenser.py`
- [ ] **Step 4: Run tests** — all pass
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): add ConversationWindowCondenser — reactive fallback matching OpenHands"
```

---

### Task 6: SmartPrunerCondenser

**Files:**
- Create: `context/condenser/SmartPrunerCondenser.kt`
- Test: `context/condenser/SmartPrunerCondenserTest.kt`

- [ ] **Step 1: Write tests**

Tests should cover: file read deduplication (replaces older read with CondensationObservation), edit resets dedup tracking, failed tool arg truncation after 4 events, write superseding on confirmed edit+read, all replacements produce new events (originals untouched), returns CondenserView (never Condensation).

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement** — port logic from existing `SmartPruner.kt`, adapted for immutable events (returns new View with replacement events, no mutation)
- [ ] **Step 4: Run tests** — all pass
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): add SmartPrunerCondenser — zero-loss dedup/purge/supersede"
```

---

### Task 7: ObservationMaskingCondenser

**Files:**
- Create: `context/condenser/ObservationMaskingCondenser.kt`
- Test: `context/condenser/ObservationMaskingCondenserTest.kt`

- [ ] **Step 1: Write tests**

Tests should cover: observations outside attention window are replaced with CondensationObservation, observations inside window are kept, non-Observation events are untouched, rich placeholder includes tool name and recovery hint, configurable attentionWindow.

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement** — OpenHands pattern with rich placeholders
- [ ] **Step 4: Run tests** — all pass
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): add ObservationMaskingCondenser — cheap masking with rich placeholders"
```

---

### Task 8: LLMSummarizingCondenser

**Files:**
- Create: `context/condenser/LLMSummarizingCondenser.kt`
- Test: `context/condenser/LLMSummarizingCondenserTest.kt`

- [ ] **Step 1: Write tests**

Tests should cover: shouldCondense triggers on tokenUtilization > threshold, on view.size > maxSize, on unhandledCondensationRequest; getCondensation keeps first keepFirst events + tail, includes `<PREVIOUS SUMMARY>` from existing CondensationObservation, formats events as `<EVENT id=N>`, returns Condensation with summary and summaryOffset. Mock the LLM call to return a canned summary.

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement** — OpenHands algorithm with token-aware trigger. Prompt verbatim from OpenHands. LLM call goes through SourcegraphChatClient (message sanitization handles system role wrapping). Cheaper model selection via ModelCache.
- [ ] **Step 4: Run tests** — all pass
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): add LLMSummarizingCondenser — OpenHands prompt with token-aware triggers"
```

---

## Phase 3: ConversationMemory

### Task 9: ConversationMemory

**Files:**
- Create: `context/ConversationMemory.kt`
- Test: `context/ConversationMemoryTest.kt`

- [ ] **Step 1: Write tests**

Tests should cover: system message insertion, initial user message protection (not re-inserted when in forgottenEventIds), tool call pairing by responseGroupId, orphan filtering, ToolAction→assistant message with tool_calls, ToolResultObservation→tool message, CondensationObservation→user message, Sourcegraph sanitization (system→user wrapping, tool→user wrapping, consecutive merge, starts with user), middle truncation at maxMessageChars, AgentThinkAction skipped.

- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement** — matching OpenHands' `conversation_memory.py` algorithm, with Sourcegraph sanitization from existing `SourcegraphChatClient.sanitizeMessages()`
- [ ] **Step 4: Run tests** — all pass
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): add ConversationMemory — event→message conversion with Sourcegraph sanitization"
```

---

## Phase 4: Integration

### Task 10: Configuration

**Files:**
- Create: `context/ContextManagementConfig.kt`
- Modify: `settings/AgentSettings.kt` — add ContextManagementConfig fields

- [ ] **Step 1: Create ContextManagementConfig**

```kotlin
data class ContextManagementConfig(
    val observationMaskingWindow: Int = 30,
    val smartPrunerEnabled: Boolean = true,
    val llmSummarizingMaxSize: Int = 150,
    val llmSummarizingKeepFirst: Int = 4,
    val llmSummarizingTokenThreshold: Double = 0.75,
    val llmSummarizingMaxEventLength: Int = 10_000,
    val rotationThreshold: Double = 0.97,
    val condensationLoopThreshold: Int = 10,
    val useCheaperModelForSummarization: Boolean = true
)
```

- [ ] **Step 2: Add to AgentSettings** — add `contextManagement: ContextManagementConfig` field
- [ ] **Step 3: Commit**

```bash
git commit -m "feat(agent): add ContextManagementConfig with production defaults"
```

---

### Task 11: Pipeline Factory

**Files:**
- Create: `context/condenser/CondenserFactory.kt`
- Test: `context/condenser/CondenserFactoryTest.kt`

- [ ] **Step 1: Write tests** — factory creates correct pipeline from config, cheaper model selection from model list
- [ ] **Step 2: Implement** — builds the 4-stage pipeline from ContextManagementConfig, handles model selection for summarization
- [ ] **Step 3: Run tests**
- [ ] **Step 4: Commit**

```bash
git commit -m "feat(agent): add CondenserFactory — builds pipeline from config with model selection"
```

---

### Task 12: Stuck Detector — Condensation Loop

**Files:**
- Modify: `runtime/LoopGuard.kt` — add `isCondensationLooping()` method
- Test: Modify `runtime/LoopGuardTest.kt` — add condensation loop tests

- [ ] **Step 1: Write tests** — 10+ consecutive CondensationObservations with no real work = stuck, fewer than 10 = not stuck, work between condensations = not stuck
- [ ] **Step 2: Implement** — matching OpenHands' scenario 5 detection algorithm
- [ ] **Step 3: Run tests**
- [ ] **Step 4: Commit**

```bash
git commit -m "feat(agent): add condensation loop detection to LoopGuard — 10 consecutive threshold"
```

---

### Task 13: Wire into SingleAgentSession

**Files:**
- Modify: `runtime/SingleAgentSession.kt` — replace ContextManager with EventStore + Condenser + ConversationMemory
- Modify: `runtime/ConversationSession.kt` — own EventStore instead of ContextManager
- Test: Modify `runtime/SingleAgentSessionTest.kt`

- [ ] **Step 1: Update ConversationSession** — replace ContextManager with EventStore, create condenser pipeline from config
- [ ] **Step 2: Update SingleAgentSession** — new loop structure:
  - Build CondenserContext from EventStore state
  - Call condenser.condensedHistory()
  - On Condensation: add to EventStore, continue loop
  - On CondenserView: processEvents → LLM call
  - On ContextWindowExceeded: add CondensationRequestAction, continue
  - 97% rotation check stays
- [ ] **Step 3: Update tool result handling** — add ToolAction + ToolResultObservation events instead of ChatMessages
- [ ] **Step 4: Update all event injection points** — LoopGuard, BackpressureGate, SelfCorrectionGate now add SystemMessageAction events
- [ ] **Step 5: Run all agent tests**

Run: `./gradlew :agent:test -x composedJar`
Expected: Existing tests may need updates. New tests pass.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(agent): wire event-sourced context management into SingleAgentSession"
```

---

### Task 14: Wire into WorkerSession

**Files:**
- Modify: `runtime/WorkerSession.kt` — same changes as SingleAgentSession with tighter limits

- [ ] **Step 1: Update WorkerSession** — own EventStore, condenser pipeline with maxSize=50, tokenThreshold=0.70
- [ ] **Step 2: Run tests**
- [ ] **Step 3: Commit**

```bash
git commit -m "feat(agent): wire event-sourced context management into WorkerSession"
```

---

### Task 15: Delete Old System

**Files:**
- Delete: `context/ContextManager.kt`
- Delete: `context/SmartPruner.kt`
- Delete: `context/ToolResultCompressor.kt`
- Delete: `context/TokenEstimator.kt` (typealias — core's TokenEstimator still used)
- Delete: `runtime/BudgetEnforcer.kt`
- Delete: `runtime/ConversationStore.kt`
- Delete: corresponding test files for deleted classes

- [ ] **Step 1: Delete old files**
- [ ] **Step 2: Fix any remaining compilation errors** — grep for imports of deleted classes, update
- [ ] **Step 3: Run full test suite**

Run: `./gradlew :agent:test -x composedJar`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(agent): delete old ContextManager, BudgetEnforcer, SmartPruner, ConversationStore"
```

---

### Task 16: Move Sanitization from SourcegraphChatClient to ConversationMemory

**Files:**
- Modify: `api/SourcegraphChatClient.kt` — remove `sanitizeMessages()`, make it a pure HTTP transport
- Modify: `context/ConversationMemory.kt` — ensure sanitization is applied in processEvents output

- [ ] **Step 1: Verify ConversationMemory already handles sanitization** (from Task 9)
- [ ] **Step 2: Remove sanitizeMessages() from SourcegraphChatClient** — just send messages as-is
- [ ] **Step 3: Run all tests**
- [ ] **Step 4: Commit**

```bash
git commit -m "refactor(agent): move message sanitization from SourcegraphChatClient to ConversationMemory"
```

---

### Task 17: Update Documentation

**Files:**
- Modify: `agent/CLAUDE.md` — update Context Management section, remove references to old ContextManager/BudgetEnforcer
- Modify: root `CLAUDE.md` — if any agent architecture references changed

- [ ] **Step 1: Update agent/CLAUDE.md** — document new EventStore + View + CondenserPipeline + ConversationMemory architecture
- [ ] **Step 2: Commit**

```bash
git commit -m "docs(agent): update CLAUDE.md for event-sourced context management"
```

---

### Task 18: Final Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache -x composedJar
```
Expected: All tests pass

- [ ] **Step 2: Run verifyPlugin**

```bash
./gradlew verifyPlugin
```
Expected: No API compatibility issues

- [ ] **Step 3: Build plugin**

```bash
./gradlew buildPlugin
```
Expected: ZIP builds successfully
