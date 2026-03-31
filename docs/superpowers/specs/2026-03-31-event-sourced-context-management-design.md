# Event-Sourced Context Management Redesign

**Date:** 2026-03-31
**Status:** Approved
**Branch:** `feature/context-management`
**Worktree:** `.worktrees/context-management`

## Summary

Complete redesign of the agent's context management system, replacing the mutation-based `ContextManager` (764 lines) with an event-sourced architecture modeled after OpenHands. Events are append-only and never modified. A `View` layer projects the raw history into what the LLM sees. A composable condenser pipeline handles compression at multiple stages, with token-aware triggers adapted for Sourcegraph's 190K context window.

## Research Basis

- Source-code analysis of 13 enterprise agentic tools (Cline, Aider, OpenHands, Codex CLI, SWE-agent, Goose, Amazon Q, LangGraph, CrewAI, Anthropic SDK, Google ADK, AutoGen)
- 20+ academic papers and industry publications (2025-2026)
- Full document: `docs/research/2026-03-31-context-management-deep-analysis.md`
- OpenHands blueprint: cloned repo, every class/field/method/prompt mapped

## Design Decisions

| Decision | Choice | Reasoning |
|---|---|---|
| Backward compatibility | Clean break | Freedom to build right; no shims or fallbacks |
| Architecture | Event-sourced (OpenHands model) | Battle-tested, full audit trail, rollback, composable condensers |
| LLM provider | Sourcegraph-only | Single provider, no fallback. If Sourcegraph rejects, plugin rejects. |
| Summarization prompt | OpenHands verbatim | Battle-tested across thousands of sessions. Adapt later if needed. |
| Summarization model | Cheaper model auto-selected | From `/api/llm/models`, pick haiku-tier for summarization |
| Tool result aging | Dropped (3-tier) | Problems outweigh benefits. Replaced by ObservationMaskingCondenser. |
| SmartPruner | Kept as condenser | Zero-loss strategies are genuinely unique and valuable |
| Trigger mechanism | Token-aware (Approach B) | Event-count too crude for 190K window. Token-based is more accurate. |

## Sourcegraph API Constraints

| Feature | Supported | Impact |
|---|---|---|
| tools (function definitions) | Yes | Tool calls work normally |
| tool_choice | **No** | Cannot use StructuredSummaryCondenser (forced tool call) |
| response_format / response_schema | **No** | Cannot use LLMAttentionCondenser (structured output) |
| cache_control | **No** | No prompt caching. Structure context for future support. |
| system role | **No** | Wrap in `<system_instructions>` tags in user messages |
| streaming | Yes | With edge case handling (streaming drop fallback) |
| usage/token counts | Yes | promptTokens for reconciliation |
| model listing | Yes | `/api/llm/models` for cheaper model selection |

---

## Architecture

### Three Layers

```
Layer 1: EventStore (append-only history)
    ↓
Layer 2: View (filtered projection via View.fromEvents())
    ↓
Layer 3: ConversationMemory (events → LLM messages → Sourcegraph format)
```

### Data Flow

```
SingleAgentSession.execute():
  loop:
    1. Build CondenserContext(view, tokenUtilization, budget, tokens)
    2. condenser.condensedHistory(context) →
         Condensation(action) → eventStore.add(action), continue loop
         CondenserView(view) → proceed to LLM call
    3. conversationMemory.processEvents(view.events) → List<ChatMessage>
    4. sourcegraphClient.sendMessageStream(messages) → response
    5. Parse response → add events to eventStore
    6. Execute tool calls → add result events to eventStore
    7. Reconcile tokens from usage.promptTokens
    8. Check stuck detector
    9. On ContextWindowExceeded: add CondensationRequestAction, continue loop
```

---

## Event Model

### Event Hierarchy

```kotlin
sealed interface Event {
    val id: Int                    // Monotonic, gapless, assigned by EventStore
    val timestamp: Instant         // Set on add
    val source: EventSource        // AGENT, USER, SYSTEM
}

enum class EventSource { AGENT, USER, SYSTEM }

sealed interface Action : Event

sealed interface Observation : Event {
    val content: String
}
```

### Action Types

```kotlin
// Messages
data class MessageAction(val content: String, val imageUrls: List<String>? = null) : Action
data class SystemMessageAction(val content: String) : Action
data class AgentThinkAction(val thought: String) : Action
data class AgentFinishAction(val finalThought: String, val outputs: Map<String, Any> = emptyMap()) : Action
data class DelegateAction(val agentType: String, val prompt: String, val thought: String? = null) : Action

// Condensation
data class CondensationAction(
    val forgottenEventIds: List<Int>? = null,           // Explicit list
    val forgottenEventsStartId: Int? = null,            // OR contiguous range start (inclusive)
    val forgottenEventsEndId: Int? = null,              // Contiguous range end (inclusive)
    val summary: String? = null,                         // Text summary of forgotten events
    val summaryOffset: Int? = null                       // Position in kept events to insert summary
) : Action {
    // Validation: exactly one of (list) XOR (range) must be set
    // If summary is set, summaryOffset must be set (and vice versa)
    val forgotten: List<Int> get() = forgottenEventIds
        ?: (forgottenEventsStartId!!..forgottenEventsEndId!!).toList()
}
data class CondensationRequestAction : Action           // Signal: context window exceeded

// Typed tool calls (high-frequency tools that condensers pattern-match on)
sealed interface ToolAction : Action {
    val toolCallId: String
    val responseGroupId: String     // Groups tool calls from same LLM response
}
data class FileReadAction(val path: String, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class FileEditAction(val path: String, val oldStr: String?, val newStr: String?, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class CommandRunAction(val command: String, val cwd: String?, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class SearchCodeAction(val query: String, val path: String?, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class DiagnosticsAction(val path: String?, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class GenericToolAction(val toolName: String, val arguments: String, override val toolCallId: String, override val responseGroupId: String) : ToolAction
data class MetaToolAction(val toolName: String, val actionName: String, val arguments: String, override val toolCallId: String, override val responseGroupId: String) : ToolAction

// Compression-proof (never forgotten by condensers)
data class FactRecordedAction(val factType: FactType, val path: String?, val content: String) : Action
data class PlanUpdatedAction(val planJson: String) : Action
data class SkillActivatedAction(val skillName: String, val content: String) : Action
data class SkillDeactivatedAction(val skillName: String) : Action
data class GuardrailRecordedAction(val rule: String) : Action
data class MentionAction(val paths: List<String>, val content: String) : Action
```

### Observation Types

```kotlin
data class ToolResultObservation(val toolCallId: String, override val content: String, val isError: Boolean, val toolName: String) : Observation
data class CondensationObservation(override val content: String) : Observation    // Summary text inserted by View
data class ErrorObservation(override val content: String, val errorId: String? = null) : Observation
data class SuccessObservation(override val content: String) : Observation
```

### Never-Forgotten Event Types

Condensers exclude these from `forgottenEventIds`:

```kotlin
val NEVER_FORGET_TYPES: Set<KClass<out Event>> = setOf(
    FactRecordedAction::class,
    PlanUpdatedAction::class,
    SkillActivatedAction::class,
    GuardrailRecordedAction::class,
    MentionAction::class
)
```

Old versions of the same anchor CAN be forgotten — only the latest matters. Implementation: when building forgotten set, exclude events matching these types.

---

## EventStore

```kotlin
class EventStore(private val sessionDir: File?) {
    private val events: MutableList<Event> = mutableListOf()
    private var nextId: Int = 0
    private val lock = ReentrantLock()

    fun add(event: Event, source: EventSource): Event   // Assign ID + timestamp under lock, append
    fun get(id: Int): Event?                             // O(1) index lookup
    fun slice(startId: Int, endId: Int): List<Event>     // Range query
    fun size(): Int
    fun all(): List<Event>                               // Snapshot for View.fromEvents()

    // Persistence
    fun appendToJsonl()                                  // Incremental append of new events
    fun flush()                                          // Force write

    companion object {
        fun loadFromJsonl(sessionDir: File): EventStore  // Rebuild from disk
    }
}
```

- Monotonic gapless IDs starting at 0
- ID assigned under `ReentrantLock` (thread safety for background agents)
- Events immutable after add
- JSONL persistence — incremental append, not full rewrite
- On session resume: replay JSONL to rebuild EventStore, View.fromEvents() reconstructs state

---

## View

```kotlin
data class View(
    val events: List<Event>,
    val unhandledCondensationRequest: Boolean = false,
    val forgottenEventIds: Set<Int> = emptySet()
) {
    val size: Int get() = events.size

    companion object {
        fun fromEvents(history: List<Event>): View
    }
}
```

### View.fromEvents() Algorithm (identical to OpenHands)

1. **Collect forgotten IDs:** Scan all events. For each `CondensationAction`: add `action.forgotten` + action's own ID to forgotten set. For each `CondensationRequestAction`: add its ID to forgotten set.
2. **Filter:** `keptEvents = history.filter { it.id !in forgottenEventIds }`
3. **Insert summary:** Scan events in REVERSE. Find last `CondensationAction` with both `summary` and `summaryOffset` non-null. Insert `CondensationObservation(content=summary)` at `summaryOffset` in keptEvents.
4. **Detect unhandled request:** Scan events in reverse. If `CondensationRequestAction` appears before any `CondensationAction`, set `unhandledCondensationRequest = true`.
5. **Return** `View(events=keptEvents, unhandledCondensationRequest, forgottenEventIds)`

### Caching

Cached on session state by `eventStore.size()`. Recomputed only when an event is appended (same as OpenHands' `len(history)` checksum).

---

## Condenser System

### Base Abstractions

```kotlin
sealed interface CondenserResult
data class CondenserView(val view: View) : CondenserResult
data class Condensation(val action: CondensationAction) : CondenserResult

data class CondenserContext(
    val view: View,
    val tokenUtilization: Double,      // 0.0-1.0, API-reconciled
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

### CondenserPipeline

```kotlin
class CondenserPipeline(private val condensers: List<Condenser>) : Condenser {
    override fun condense(context: CondenserContext): CondenserResult {
        var currentContext = context
        for (condenser in condensers) {
            when (val result = condenser.condense(currentContext)) {
                is Condensation -> return result          // Short-circuit
                is CondenserView -> currentContext = currentContext.copy(view = result.view)
            }
        }
        return CondenserView(currentContext.view)
    }
}
```

### Production Default Pipeline

```kotlin
CondenserPipeline(listOf(
    ConversationWindowCondenser(),
    SmartPrunerCondenser(),
    ObservationMaskingCondenser(attentionWindow = 30),
    LLMSummarizingCondenser(keepFirst = 4, maxSize = 150, tokenThreshold = 0.75)
))
```

**Order matters:**
1. ConversationWindow — emergency overflow handling first
2. SmartPruner — zero-loss cleanup reduces noise
3. ObservationMasking — cheap masking reduces tokens for summarizer
4. LLMSummarizing — most expensive, runs on already-optimized events

---

## Condenser Implementations

### 1. ConversationWindowCondenser (Reactive Fallback)

Identical to OpenHands. Reactive only — triggers on `unhandledCondensationRequest`, never proactively.

**shouldCondense:** `context.view.unhandledCondensationRequest`

**getCondensation:**
1. Find essential initial events: first `SystemMessageAction`, first user `MessageAction`, associated first recall/observation
2. Count non-essential events, keep roughly half from the tail
3. Handle dangling observations at slice boundary (skip leading orphaned observations)
4. Build forgotten set from all non-essential, non-recent events
5. Optimize: use range mode if forgotten IDs are contiguous
6. No LLM, no summary

### 2. SmartPrunerCondenser (Zero-Loss Optimization)

Not a RollingCondenser — always runs. Returns new View with replacement events (never mutates originals).

**Three strategies:**

1. **Deduplicate file reads:** Detect multiple `FileReadAction` for same path (without intervening `FileEditAction`). Replace older `ToolResultObservation` with `CondensationObservation("[Deduplicated — 'path' was re-read later]")`.

2. **Purge failed tool inputs:** Detect `ToolResultObservation(isError=true)` older than 4 events. Replace corresponding `ToolAction` with a version carrying truncated arguments (first 200 chars).

3. **Supersede confirmed writes:** Detect `FileEditAction` with successful `ToolResultObservation`, followed by `FileReadAction` for same path. Replace the edit's `ToolResultObservation` with `CondensationObservation("[Write confirmed by subsequent read]")`.

All replacements produce new event objects in the View — originals in EventStore untouched.

### 3. ObservationMaskingCondenser (Cheap Masking)

Matches OpenHands. Not a RollingCondenser — always runs. Returns new View.

**Config:** `attentionWindow: Int = 30`

**Algorithm:**
1. Iterate events with index
2. If event is an `Observation` AND `index < view.size - attentionWindow`: replace with `CondensationObservation` containing rich placeholder (tool name, args preview, disk path from ToolOutputStore, recovery hint)
3. Otherwise: keep original
4. Return new View

**Rich placeholder format (enhancement over OpenHands' bare `<MASKED>`):**
```
[Tool result masked to save context]
Tool: search_code
Args: {"query": "handleAuth", "path": "src/..."}
Preview: Found 12 matches in 4 files...
Disk: ~/.workflow-orchestrator/.../tool-outputs/tc_abc123.txt
Recovery: re-run search_code with the same query to refresh results
```

### 4. LLMSummarizingCondenser (Primary Compressor)

Matches OpenHands with token-aware trigger addition.

**Config:**
```kotlin
keepFirst: Int = 4
maxSize: Int = 150
maxEventLength: Int = 10_000
tokenThreshold: Double = 0.75
```

**shouldCondense:**
```kotlin
context.tokenUtilization > tokenThreshold
    || context.view.size > maxSize
    || context.view.unhandledCondensationRequest
```

**getCondensation:**
1. `head = view[0..keepFirst]`
2. `targetSize = maxSize / 2`
3. `eventsFromTail = targetSize - head.size - 1` (minus 1 for summary slot)
4. Detect existing `CondensationObservation` at `view[keepFirst]` — use as `<PREVIOUS SUMMARY>`
5. Collect forgotten events (excluding `CondensationObservation` instances)
6. Truncate each forgotten event to `maxEventLength` chars
7. Format as `<EVENT id=N>...</EVENT>` blocks
8. Call LLM with summarization prompt + `<PREVIOUS SUMMARY>` + event blocks
9. Return `Condensation(action=CondensationAction(forgottenEventsStartId=..., forgottenEventsEndId=..., summary=response, summaryOffset=keepFirst))`

**Summarization prompt (OpenHands verbatim):**

```
You are maintaining a context-aware state summary for an interactive agent.
You will be given a list of events corresponding to actions taken by the agent,
and the most recent previous summary if one exists.
If the events being summarized contain ANY task-tracking, you MUST include
a TASK_TRACKING section to maintain continuity.
When referencing tasks make sure to preserve exact task IDs and statuses.

Track:

USER_CONTEXT: (Preserve essential user requirements, goals, and clarifications)
TASK_TRACKING: {Active tasks, their IDs and statuses - PRESERVE TASK IDs}
COMPLETED: (Tasks completed so far, with brief results)
PENDING: (Tasks that still need to be done)
CURRENT_STATE: (Current variables, data structures, or relevant state)

For code-specific tasks, also include:
CODE_STATE: {File paths, function signatures, data structures}
TESTS: {Failing cases, error messages, outputs}
CHANGES: {Code edits, variable updates}
DEPS: {Dependencies, imports, external calls}
VERSION_CONTROL_STATUS: {Repository state, current branch, PR status, commit history}

PRIORITIZE:
1. Adapt tracking format to match the actual task type
2. Capture key user requirements and goals
3. Distinguish between completed and pending tasks
4. Keep all sections concise and relevant

SKIP: Tracking irrelevant details for the current task type
```

**Cheaper model selection:** Query `/api/llm/models`, filter for haiku-tier (tier=2). If available, use for summarization. If not, use the agent's main model. Configurable via `useCheaperModelForSummarization`.

**Prompt caching:** Disabled for summarization calls (content changes every time — cache writes would never be read).

### 5. NoOpCondenser (Testing)

Returns view unchanged. For tests and disabling condensation via config.

---

## ConversationMemory

Converts View events into Sourcegraph-compatible `ChatMessage` objects.

```kotlin
class ConversationMemory(
    private val maxMessageChars: Int = 30_000
) {
    fun processEvents(
        condensedHistory: List<Event>,
        initialUserAction: MessageAction,
        forgottenEventIds: Set<Int> = emptySet()
    ): List<ChatMessage>
}
```

### Algorithm (matching OpenHands)

**Step 1: Ensure system message.** If no `SystemMessageAction` in events, create from PromptAssembler, insert at index 0.

**Step 2: Ensure initial user message.** If `initialUserAction.id` is in `forgottenEventIds`, do NOT re-insert (condensed away). Otherwise insert at index 1 if missing.

**Step 3: Iterate events with tool call pairing.**

Two tracking maps:
- `pendingToolCallMessages: Map<responseGroupId, PendingAssistant>` — assistant messages waiting for tool results
- `toolResultMessages: Map<toolCallId, ChatMessage>` — completed tool results

For each event:
- `MessageAction(USER)` → `ChatMessage(role="user")`
- `MessageAction(AGENT)` → `ChatMessage(role="assistant")`
- `SystemMessageAction` → `ChatMessage(role="system")`
- `AgentThinkAction` → skip
- `ToolAction` → hold in pending, grouped by `responseGroupId`
- `ToolResultObservation` → store in toolResultMessages by `toolCallId`
- `CondensationObservation` → `ChatMessage(role="user", content=summary)`
- `ErrorObservation` → `ChatMessage(role="user")`
- `AgentFinishAction` → `ChatMessage(role="assistant")`
- `FactRecordedAction`, `PlanUpdatedAction`, etc. → formatted as system/user messages

After each event: check if pending assistant has all tool results matched. If so, emit assistant + tool messages.

**Step 4: Filter orphans.** Remove unmatched tool calls and tool results.

**Step 5: Sourcegraph sanitization.**
- `system` → `user` with `<system_instructions>` wrapping
- `tool` → `user` with `<tool_result tool_use_id="...">` wrapping
- Merge consecutive same-role messages
- Ensure conversation starts with `user`
- Null/empty assistant content → `<tool_calls/>` placeholder

**Step 6: Truncate long messages.** Middle-truncation at `maxMessageChars` with `[... Observation truncated due to length ...]` marker.

---

## Agent Loop Integration

### SingleAgentSession Changes

```kotlin
// OLD:
val messages = contextManager.getMessages()
val response = brain.chat(messages, tools, maxTokens)
contextManager.addAssistantMessage(response)
contextManager.reconcileWithActualTokens(response.usage.promptTokens)
if (budgetEnforcer.check() == COMPRESS) contextManager.compressWithLlm(brain)

// NEW:
val context = CondenserContext(
    view = state.view,
    tokenUtilization = state.tokenUtilization,
    effectiveBudget = state.effectiveBudget,
    currentTokens = state.currentTokens
)
when (val result = condenser.condensedHistory(context)) {
    is Condensation -> {
        eventStore.add(result.action, EventSource.SYSTEM)
        continue  // Re-step
    }
    is CondenserView -> {
        val messages = conversationMemory.processEvents(result.view.events, initialUserAction, result.view.forgottenEventIds)
        val response = sourcegraphClient.sendMessageStream(messages, tools)
        // Parse response → add events to eventStore
        // Execute tool calls → add result events to eventStore
        state.reconcileTokens(response.usage.promptTokens)
    }
}
```

### ContextWindowExceededError Handling

```kotlin
catch (e: ContextLengthExceededException) {
    eventStore.add(CondensationRequestAction(), EventSource.SYSTEM)
    continue  // Next iteration: condenser detects unhandledCondensationRequest, triggers condensation
}
```

Identical to OpenHands controller lines 930-957. Two re-steps: condense, then retry.

### Context Rotation (97%)

```kotlin
if (state.tokenUtilization > 0.97) {
    val rotationState = RotationState(
        goal = ..., accomplishments = ..., remainingWork = ...,
        modifiedFiles = ..., guardrails = ..., factsSnapshot = ...
    )
    RotationState.save(rotationState, sessionDir)
    return SingleAgentResult.ContextRotated(rotationState)
}
```

### Stuck Detection — Condensation Loop

```kotlin
fun isCondensationLooping(events: List<Event>): Boolean {
    val condensationObs = events.filterIsInstance<CondensationObservation>()
    if (condensationObs.size < 10) return false
    val lastTen = condensationObs.takeLast(10)
    for (i in 0 until lastTen.size - 1) {
        val between = events.filter {
            it.id in (lastTen[i].id + 1) until lastTen[i + 1].id
        }.filterNot {
            it is CondensationObservation || it is CondensationAction || it is CondensationRequestAction
        }
        if (between.isNotEmpty()) return false
    }
    return true  // 10 consecutive condensations with no real work = stuck
}
```

Threshold: 10 consecutive. Identical to OpenHands.

### WorkerSession

Same architecture, tighter limits:
- Own EventStore (context isolation)
- `maxSize = 50`, `tokenThreshold = 0.70`
- Same pipeline, same condensers

---

## What Gets Deleted

| Component | Lines | Replaced By |
|---|---|---|
| `ContextManager.kt` | 764 | EventStore + View + CondenserPipeline + ConversationMemory |
| `BudgetEnforcer.kt` | 62 | Condenser token-aware triggers + 97% rotation check |
| `SmartPruner.kt` | 248 | SmartPrunerCondenser (logic ported, class rewritten) |
| `ToolResultCompressor.kt` | ~100 | Deleted (already deprecated) |
| `ConversationStore.kt` | ~150 | EventStore JSONL persistence |

## What Stays (Unchanged)

| Component | Reason |
|---|---|
| `ToolOutputStore` | Disk spillover for recovery after masking |
| `RotationState` | Context rotation at 97% — already standalone |
| `PromptAssembler` | System prompt construction → feeds SystemMessageAction |
| `FactsStore` | Logic reused in FactRecordedAction events |
| `WorkingSet` | Orthogonal to context management |
| `LoopGuard` | Injects SystemMessageAction events instead of ChatMessages |
| `BackpressureGate` | Same — injects events |
| `SelfCorrectionGate` | Same — injects events |

## Consumers That Need Updating

| Component | Change |
|---|---|
| `SingleAgentSession` | New loop structure: EventStore + condenser instead of ContextManager |
| `ConversationSession` | Owns EventStore instead of ContextManager |
| `WorkerSession` | Same changes as SingleAgentSession |
| `AgentController` | Session resume rebuilds from EventStore |
| `SourcegraphChatClient` | Message sanitization moves to ConversationMemory; client becomes pure HTTP transport |
| `AgentOrchestrator` | Pass EventStore + condenser to sessions |

---

## Configuration

```kotlin
data class ContextManagementConfig(
    // Pipeline
    val observationMaskingWindow: Int = 30,
    val smartPrunerEnabled: Boolean = true,
    val llmSummarizingMaxSize: Int = 150,
    val llmSummarizingKeepFirst: Int = 4,
    val llmSummarizingTokenThreshold: Double = 0.75,
    val llmSummarizingMaxEventLength: Int = 10_000,

    // Rotation
    val rotationThreshold: Double = 0.97,

    // Stuck detection
    val condensationLoopThreshold: Int = 10,

    // Summarization model
    val useCheaperModelForSummarization: Boolean = true
)
```

Exposed in Settings under "Tools > Workflow Orchestrator > AI & Advanced".

---

## Future Extensions (Not In This Build)

- **Multi-tier budget system (Approach C):** Graduated response at 60/75/85/95%. Additive change on top of pipeline.
- **StructuredSummaryCondenser:** If Sourcegraph adds `tool_choice` support.
- **Prompt caching:** If Sourcegraph adds `cache_control` support.
- **Custom condenser plugins:** User-defined condensers via config.
- **Adapted summarization categories:** Jira/Bamboo/Sonar workflow categories in prompt — only if summaries lose workflow context in practice.
