# Architecture Audit Remediation — Design Spec

**Date:** 2026-04-03
**Scope:** Fix all 33 issues from comprehensive architecture audit (7 CRITICAL + 8 HIGH + 18 MEDIUM)
**Approach:** Monolithic, dependency-ordered plan with subagent-driven parallel execution
**Audit reference:** `docs/audits/2026-04-03-comprehensive-architecture-audit.md`

---

## 1. Overview

This spec covers the complete remediation of all findings from the 2026-04-03 architecture audit. The 33 issues span 6 subsystems: context management, tool architecture, sub-agent orchestration, planning/completion, prompt engineering, and service wiring.

Issues are organized into 9 work units with explicit dependency ordering. Work units without dependencies can execute in parallel via subagents.

### Issue Index

| ID | Severity | Summary | Work Unit |
|----|----------|---------|-----------|
| C1 | CRITICAL | EventStore.all() thread-safety bug | WU4 |
| C2 | CRITICAL | FileOwnershipRegistry.claim() TOCTOU race | WU4 |
| C3 | CRITICAL | Budget exhaustion bypasses completion gates | WU3 |
| C4 | CRITICAL | AgentService God Object (20+ @Volatile fields) | WU1 |
| C5 | CRITICAL | processLlmSuccess 630+ lines | WU2 |
| C6 | CRITICAL | Anchor tokens invisible to condenser | WU5 |
| C7 | CRITICAL | Static callbacks on tool classes (multi-project bug) | WU6 |
| H1 | HIGH | SelfCorrectionGate state not restored on resume | WU8 |
| H2 | HIGH | No API retry in WorkerSession | WU8 |
| H3 | HIGH | Recency zone overloaded (11 sections) | WU7 |
| H4 | HIGH | Parameter naming inconsistency in meta-tools | WU7 |
| H5 | HIGH | %s format string vulnerability | WU4 |
| H6 | HIGH | ApprovalGate no timeout | WU4 |
| H7 | HIGH | Background worker scope detached | WU4 |
| H8 | HIGH | LLM summarization cap too low (500 tokens) | WU4 |
| M1 | MEDIUM | PlanManager dual approval mechanism | WU6 |
| M2 | MEDIUM | forceTextOnly irreversible | WU3 |
| M3 | MEDIUM | Worker personas missing worker_complete | WU7 |
| M4 | MEDIUM | Tool names in skills/personas outdated | WU7 |
| M5 | MEDIUM | DynamicToolSelector false positive keywords | WU7 |
| M6 | MEDIUM | extractJsonString regex fragile | WU4 |
| M7 | MEDIUM | File.renameTo not atomic on Windows | WU4 |
| M8 | MEDIUM | Planning category bundles memory tools | WU6 |
| M9 | MEDIUM | ConversationSession creates bridge twice | WU1 |
| M10 | MEDIUM | noOpCondensation creates wasteful events | WU4 |
| M11 | MEDIUM | CommandSafetyAnalyzer no priority escalation | WU4 |
| M12 | MEDIUM | View.fromEvents() no caching | WU5 |
| M13 | MEDIUM | Callback wiring duplication | WU6 |
| M14 | MEDIUM | AgentStartupActivity swallows exceptions | WU4 |
| M15 | MEDIUM | Persistence instruction duplicated 3x | WU7 |
| M16 | MEDIUM | Custom agents default to ORCHESTRATOR | WU4 |
| M17 | MEDIUM | Resume replays full transcript without budget check | WU8 |
| M18 | MEDIUM | Condenser inter-stage token update | WU5 |

### Dependency Graph

```
WU1 (SessionScope) ──┬──→ WU2 (processLlmSuccess decomposition)
                      │         │
                      │         └──→ WU3 (Completion simplification)
                      │
                      ├──→ WU6 (Static callbacks + wiring)
                      │
                      └──→ WU8 (Session resume, partially)

WU4 (Surgical fixes) ──→ independent, can run in parallel with WU1
WU5 (Context budget) ──→ independent, can run in parallel with WU1
WU7 (Prompt tightening) ──→ independent, can run in parallel with WU1
```

### Execution Order

**Phase A (parallel):** WU1, WU4, WU5, WU7
**Phase B (depends on WU1):** WU2, WU6, WU8
**Phase C (depends on WU2):** WU3
**Phase D:** Update tests, update CLAUDE.md, verify build

---

## 2. Work Unit 1: SessionScope Extraction (C4, M9)

**Issues:** C4 (AgentService God Object), M9 (double bridge creation)
**Files modified:**
- NEW: `agent/src/main/kotlin/.../runtime/SessionScope.kt`
- NEW: `agent/src/main/kotlin/.../runtime/UiCallbacks.kt`
- MODIFIED: `agent/src/main/kotlin/.../AgentService.kt`
- MODIFIED: `agent/src/main/kotlin/.../runtime/ConversationSession.kt`
- MODIFIED: `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt`
- MODIFIED: `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt`
- MODIFIED: `agent/src/main/kotlin/.../ui/AgentController.kt`
- MODIFIED: `agent/src/main/kotlin/.../tools/builtin/SpawnAgentTool.kt`
- MODIFIED: Various tools that currently access `AgentService` for session state

### 2.1 New: SessionScope

```kotlin
// runtime/SessionScope.kt
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import java.io.File

/**
 * Immutable container for all per-session state. Created by [ConversationSession.create],
 * passed to all session participants. Replaces the 20+ @Volatile fields on [AgentService].
 *
 * Lifecycle: created at session start, immutable reference held by ConversationSession,
 * cleared (set to null on AgentService) at session end.
 */
data class SessionScope(
    val sessionId: String,
    val sessionDir: File,
    val bridge: EventSourcedContextBridge,
    val planManager: PlanManager,
    val questionManager: QuestionManager,
    val skillManager: SkillManager,
    val changeLedger: ChangeLedger,
    val rollbackManager: AgentRollbackManager,
    val selfCorrectionGate: SelfCorrectionGate,
    val backpressureGate: BackpressureGate,
    val completionGatekeeper: CompletionGatekeeper,
    val loopGuard: LoopGuard,
    val fileOwnership: FileOwnershipRegistry,
    val workerMessageBus: WorkerMessageBus,
    val metrics: AgentMetrics,
    val uiCallbacks: UiCallbacks?
)
```

### 2.2 New: UiCallbacks Interface

```kotlin
// runtime/UiCallbacks.kt
package com.workflow.orchestrator.agent.runtime

/**
 * Interface for UI operations that tools need. Implemented by AgentController.
 * Replaces static callbacks on tool companion objects.
 */
interface UiCallbacks {
    suspend fun showInputDialog(prompt: String, placeholder: String?): String?
    fun streamCommandOutput(processId: String, output: String)
    fun notifyToolProgress(toolName: String, message: String)
}
```

### 2.3 AgentService Slimming

Remove all per-session `@Volatile var` fields. Replace with:

```kotlin
@Volatile var activeScope: SessionScope? = null
```

Keep only project-level concerns:
- `val toolRegistry: ToolRegistry` (lazy)
- `val agentDefinitionRegistry: AgentDefinitionRegistry`
- `val brain: LlmBrain` (lazy)
- `var activeController: AgentController?`
- `val backgroundWorkerScope: CoroutineScope` (tied to service Disposable lifecycle)
- Companion: `planModeActive: AtomicBoolean` (unchanged)

### 2.4 ConversationSession Changes

`ConversationSession.create()` builds `SessionScope` as a single construction site. Remove the placeholder bridge. Return the session with scope attached.

`ConversationSession.load()` reconstructs `SessionScope` from persisted state (JSONL messages, plan.json, changes.jsonl, checkpoint.json).

### 2.5 Consumer Migration

All consumers that currently do `AgentService.getInstance(project).currentXxx` change to receive `SessionScope` as a constructor/method parameter. Pattern:

```kotlin
// Before:
val bridge = AgentService.getInstance(project).currentContextBridge ?: return
// After (in tool execute):
val scope = AgentService.getInstance(project).activeScope ?: return error("No active session")
val bridge = scope.bridge
```

Tools access scope via `AgentService.getInstance(project).activeScope`. The project reference is already available in every tool's `execute()` context.

---

## 3. Work Unit 2: processLlmSuccess Decomposition (C5)

**Issues:** C5 (630+ line method)
**Depends on:** WU1 (SessionScope — tools reference scope instead of service fields)
**Files modified:**
- MODIFIED: `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt`
- NEW: `agent/src/main/kotlin/.../runtime/ParsedResponse.kt` (sealed class)

### 3.1 ParsedResponse Sealed Class

```kotlin
// runtime/ParsedResponse.kt
sealed class ParsedResponse {
    abstract val content: String?
    abstract val tokensUsed: TokenUsage?

    data class Empty(
        override val content: String?,
        override val tokensUsed: TokenUsage?
    ) : ParsedResponse()

    data class Malformed(
        override val content: String?,
        override val tokensUsed: TokenUsage?,
        val rawToolCalls: List<ToolCall>,
        val finishReason: String?
    ) : ParsedResponse()

    data class TextOnly(
        override val content: String,
        override val tokensUsed: TokenUsage?
    ) : ParsedResponse()

    data class WithToolCalls(
        override val content: String?,
        override val tokensUsed: TokenUsage?,
        val toolCalls: List<ToolCall>,
        val hasCompletion: Boolean,
        val completionCall: ToolCall?
    ) : ParsedResponse()
}
```

### 3.2 Method Decomposition

The current `processLlmSuccess` becomes a router:

```kotlin
private suspend fun processLlmResponse(response: ChatCompletion): LoopAction {
    updateTokenAccounting(response.usage)
    val parsed = parseResponse(response)
    return when (parsed) {
        is ParsedResponse.Empty -> handleEmptyResponse(parsed)
        is ParsedResponse.Malformed -> handleMalformedResponse(parsed)
        is ParsedResponse.TextOnly -> handleTextOnlyResponse(parsed)
        is ParsedResponse.WithToolCalls -> handleToolCalls(parsed)
    }
}
```

Each handler method:

| Method | Responsibility | Max Lines |
|--------|---------------|-----------|
| `parseResponse()` | Classify response, filter empty tool calls, detect truncation | 60 |
| `updateTokenAccounting()` | Token reconciliation, bridge update, UI progress | 20 |
| `handleEmptyResponse()` | Nudge or accept based on consecutive count | 30 |
| `handleMalformedResponse()` | Retry counter, JSON repair attempt, escalation | 60 |
| `handleTextOnlyResponse()` | Implicit completion check via gatekeeper | 40 |
| `handleToolCalls()` | Parallel reads, sequential writes, doom loop, approval, completion extraction | 120 |
| `executeToolBatch()` | Extracted from handleToolCalls — runs the actual tool calls | 80 |
| `recordToolResults()` | Change ledger, self-correction, backpressure, checkpoint, facts | 60 |

### 3.3 LoopAction Enum

```kotlin
enum class LoopAction {
    CONTINUE,           // Next iteration
    COMPLETED,          // Task done (explicit or implicit)
    FORCE_COMPLETED,    // Gate force-accept
    CANCELLED,          // User cancelled
    BUDGET_EXHAUSTED    // Context budget hit TERMINATE
}
```

---

## 4. Work Unit 3: Completion Simplification (C3, M2)

**Issues:** C3 (budget exhaustion bypasses gates), M2 (forceTextOnly irreversible)
**Depends on:** WU2 (processLlmSuccess decomposition)
**Files modified:**
- MODIFIED: `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt`
- MODIFIED: `agent/src/main/kotlin/.../runtime/CompletionGatekeeper.kt`

### 4.1 Three Clear Completion Paths

**Path 1 — Explicit:** LLM calls `attempt_completion` → `CompletionGatekeeper.check()` with mode=NORMAL. Three gates run. Force-accept after 5 total blocked attempts. Unchanged.

**Path 2 — Implicit:** 2 consecutive text-only responses (no tool calls) → `CompletionGatekeeper.check()` with mode=LENIENT. Only plan gate runs (skip self-correction and loop guard — implicit completion is the LLM saying "I'm done", not a verification signal).

**Path 3 — Budget exhaustion:** `BudgetEnforcer.TERMINATE` → `CompletionGatekeeper.check()` with mode=FORCED. All gates run but cannot block — they log findings to scorecard/metrics only. This ensures quality signal recording without preventing exit.

### 4.2 Removed Mechanisms

- `forceTextOnly` as a session-level boolean field — REMOVED. Replace with a per-check function:
  ```kotlin
  private fun shouldForceTextOnly(): Boolean =
      iterationPercent() >= 0.95 || malformedRetryCount >= MAX_MALFORMED_RETRIES
  ```
  This is checked at each LLM call to decide whether to strip tools from the schema. It's stateless — recovery is automatic when conditions clear.

- 4-nudge escalation sequence — REMOVED. Replaced by: 1st text-only response → nudge ("Use attempt_completion or a tool"). 2nd consecutive text-only → implicit completion via gatekeeper.

- `MAX_NO_TOOL_NUDGES` constant — REMOVED.
- `consecutiveNoToolResponses` field — simplified to a boolean `lastResponseWasTextOnly`.

### 4.3 CompletionGatekeeper Mode

```kotlin
enum class CompletionMode {
    NORMAL,   // All gates active, can block
    LENIENT,  // Only plan gate active
    FORCED    // All gates run but only log, cannot block
}

fun check(mode: CompletionMode, ...): GatekeeperResult {
    // ...
    if (mode == CompletionMode.FORCED) {
        // Run gates, record findings, but always return PASS
    }
}
```

---

## 5. Work Unit 4: Surgical Safety Fixes (C1, C2, H5, H6, H7, H8, M6, M7, M10, M11, M14, M16)

**Issues:** 12 independent fixes, all 1-20 lines
**Depends on:** Nothing (fully independent)
**Files modified:** 12 files

### 5.1 C1 — EventStore Thread Safety

**File:** `context/events/EventStore.kt`

```kotlin
// Before:
fun all(): List<Event> = ArrayList(events)

// After:
fun all(): List<Event> = lock.withLock { ArrayList(events) }
```

Also fix `size()` and `slice()` if they access `events` outside the lock. Check `get()` — index-based access on ArrayList is safe if we accept stale reads, but wrapping in lock is safer.

### 5.2 C2 — FileOwnershipRegistry TOCTOU

**File:** `runtime/FileOwnershipRegistry.kt`

```kotlin
// Before:
fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
    val canonical = canonicalize(filePath)
    val existing = fileOwners[canonical]
    if (existing != null && existing.agentId != agentId) {
        return ClaimResponse(ClaimResult.DENIED, existing.agentId)
    }
    fileOwners[canonical] = OwnershipRecord(agentId, workerType)
    return ClaimResponse(ClaimResult.GRANTED)
}

// After:
fun claim(filePath: String, agentId: String, workerType: WorkerType): ClaimResponse {
    val canonical = canonicalize(filePath)
    val record = OwnershipRecord(agentId, workerType)
    var denied: String? = null
    fileOwners.compute(canonical) { _, existing ->
        when {
            existing == null -> record
            existing.agentId == agentId -> record // idempotent re-claim
            else -> { denied = existing.agentId; existing }
        }
    }
    return if (denied != null) ClaimResponse(ClaimResult.DENIED, denied)
    else ClaimResponse(ClaimResult.GRANTED)
}
```

### 5.3 H5 — Format String Vulnerability

**File:** `orchestrator/PromptAssembler.kt`

```kotlin
// Before:
sections.add(SKILL_RULES_TEMPLATE.format(skillDescriptions))

// After:
sections.add(buildSkillRulesSection(skillDescriptions))

private fun buildSkillRulesSection(descriptions: String): String = """
<skill_rules>
...
$descriptions
...
</skill_rules>
""".trimIndent()
```

### 5.4 H6 — ApprovalGate Timeout

**File:** `runtime/ApprovalGate.kt`

```kotlin
// Before:
val result = deferred.await()

// After:
val result = withTimeoutOrNull(300_000L) { deferred.await() }
if (result == null) {
    LOG.warn("Approval gate timed out after 5 minutes — auto-rejecting")
    return ApprovalResult.Rejected("Approval timed out")
}
```

### 5.5 H7 — Background Worker Scope Lifecycle

**File:** `tools/builtin/SpawnAgentTool.kt`

```kotlin
// Before:
val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

// After:
val parentScope = AgentService.getInstance(project).backgroundWorkerScope
val job = parentScope.launch(Dispatchers.IO) {
    // worker execution
}
```

`AgentService.backgroundWorkerScope` is cancelled in `dispose()`, ensuring all background workers terminate on IDE shutdown.

### 5.6 H8 — Summarization Output Cap

**File:** `context/condenser/LlmBrainSummarizationClient.kt`

```kotlin
// Before:
maxOutputTokens = 500

// After:
maxOutputTokens = 1500
```

### 5.7 M6 — extractJsonString Replacement

**File:** `context/EventSourcedContextBridge.kt`

```kotlin
// Before:
private fun extractJsonString(json: String, key: String): String? {
    val regex = Regex(""""$key"\s*:\s*"([^"]*?)"""")
    return regex.find(json)?.groupValues?.get(1)
}

// After:
private fun extractJsonString(json: String, key: String): String? = try {
    Json.parseToJsonElement(json).jsonObject[key]?.jsonPrimitive?.content
} catch (_: Exception) { null }
```

### 5.8 M7 — Atomic File Rename

**Files:** `context/CoreMemory.kt`, `context/ArchivalMemory.kt`, `context/GuardrailStore.kt`

```kotlin
// Before (CoreMemory, ArchivalMemory):
tmpFile.renameTo(file)

// After:
java.nio.file.Files.move(
    tmpFile.toPath(), file.toPath(),
    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    java.nio.file.StandardCopyOption.ATOMIC_MOVE
)
```

Add atomic write to `GuardrailStore.save()` (currently uses `file.writeText()` directly — change to tmp+move pattern).

### 5.9 M10 — noOpCondensation Fix

**File:** `context/condenser/ConversationWindowCondenser.kt`

```kotlin
// Before:
return Condensation(noOpCondensation())

// After (when nothing to forget):
return CondenserView(context.view)
```

### 5.10 M11 — CommandSafetyAnalyzer Priority Escalation

**File:** `security/CommandSafetyAnalyzer.kt`

```kotlin
// Before: first match wins
// After: collect all matches, return highest severity
fun analyze(command: String): RiskLevel {
    val matches = patterns.filter { it.regex.containsMatchIn(command) }
    return matches.maxByOrNull { it.riskLevel.ordinal }?.riskLevel ?: RiskLevel.SAFE
}
```

### 5.11 M14 — AgentStartupActivity Exception Logging

**File:** `listeners/AgentStartupActivity.kt`

Replace all `catch (_: Exception) {}` with `catch (e: Exception) { LOG.debug("Context", e) }`.

### 5.12 M16 — Custom Agent Default Type

**File:** `tools/builtin/SpawnAgentTool.kt`

```kotlin
// Before:
workerType = WorkerType.ORCHESTRATOR  // for custom agents

// After:
workerType = if (definition.frontmatter["can-delegate"] == "true")
    WorkerType.ORCHESTRATOR
else
    WorkerType.CODER
```

Also update `AgentDefinitionRegistry` to parse the `can-delegate` frontmatter field.

---

## 6. Work Unit 5: Context Budget Visibility (C6, M12, M18)

**Issues:** C6 (anchor tokens invisible), M12 (View caching), M18 (inter-stage token update)
**Depends on:** Nothing (fully independent)
**Files modified:**
- MODIFIED: `context/EventSourcedContextBridge.kt`
- MODIFIED: `context/condenser/CondenserPipeline.kt`
- MODIFIED: `context/condenser/Condenser.kt` (CondenserContext)

### 6.1 C6 — Anchor Token Tracking

Add to `EventSourcedContextBridge`:

```kotlin
private var anchorTokenEstimate: Int = 0

private fun recalculateAnchorTokens() {
    anchorTokenEstimate = getAnchorMessages().sumOf {
        TokenEstimator.estimate(it.content ?: "")
    }
}
```

Call `recalculateAnchorTokens()` from every anchor setter: `setPlanAnchor()`, `setSkillAnchor()`, `setMentionAnchor()`, `setFactsAnchor()`, `setGuardrailsAnchor()`, `setChangeLedgerAnchor()`.

Update `estimateCurrentTokens()`:
```kotlin
fun estimateCurrentTokens(): Int {
    val viewTokens = getView().estimatedTokens
    return viewTokens + anchorTokenEstimate
}
```

Update `CondenserContext` construction in `getMessagesViaCondenser()` to include anchor tokens in `currentTokens`.

### 6.2 M12 — View Caching

```kotlin
private var cachedView: View? = null
private var cachedViewEventCount: Int = -1

private fun getView(): View {
    val currentSize = eventStore.size()
    val cached = cachedView
    if (cached != null && cachedViewEventCount == currentSize) return cached
    val view = View.fromEvents(eventStore.all())
    cachedView = view
    cachedViewEventCount = currentSize
    return view
}
```

Replace all `View.fromEvents(eventStore.all())` calls with `getView()`.

### 6.3 M18 — Inter-Stage Token Update

**File:** `context/condenser/CondenserPipeline.kt`

```kotlin
// After each CondenserView result, recalculate before passing to next stage:
fun condense(context: CondenserContext): CondenserResult {
    var currentContext = context
    for (condenser in condensers) {
        when (val result = condenser.condense(currentContext)) {
            is Condensation -> return result // short-circuit
            is CondenserView -> {
                // Recalculate utilization for next stage
                val updatedTokens = result.view.estimatedTokens + context.anchorTokens
                currentContext = currentContext.copy(
                    view = result.view,
                    currentTokens = updatedTokens,
                    tokenUtilization = updatedTokens.toFloat() / context.maxTokens
                )
            }
        }
    }
    return CondenserView(currentContext.view)
}
```

Add `anchorTokens: Int` field to `CondenserContext`.

---

## 7. Work Unit 6: Static Callbacks + Wiring Cleanup (C7, M1, M8, M13)

**Issues:** C7 (static callbacks), M1 (dual approval), M8 (category split), M13 (wiring dedup)
**Depends on:** WU1 (SessionScope — UiCallbacks interface)
**Files modified:**
- MODIFIED: `ui/AgentController.kt`
- MODIFIED: `tools/builtin/AskUserInputTool.kt` (remove static callback)
- MODIFIED: `tools/builtin/RunCommandTool.kt` (remove static callback)
- MODIFIED: `runtime/PlanManager.kt`
- MODIFIED: `tools/builtin/CreatePlanTool.kt`
- MODIFIED: `tools/ToolCategoryRegistry.kt`

### 7.1 C7 — Static Callback Elimination

Remove `AskUserInputTool.showInputCallback` companion field. In `execute()`:
```kotlin
val scope = AgentService.getInstance(project).activeScope
    ?: return ToolResult.error("No active session")
val result = scope.uiCallbacks?.showInputDialog(prompt, placeholder)
    ?: return ToolResult.error("UI not available")
```

Same pattern for `RunCommandTool.streamCallback`.

### 7.2 M1 — PlanManager Dual Approval Consolidation

Remove `CompletableFuture<PlanApprovalResult>` field and `submitPlan()` method.
Keep only `CompletableDeferred<PlanApprovalResult>` via `submitPlanAndWait()`.

Update `CreatePlanTool` to call `planManager.submitPlanAndWait()` directly (it's already in a suspend context).

### 7.3 M8 — Category Split

**File:** `tools/ToolCategoryRegistry.kt`

Move memory tools out of "planning" category into new "memory" category:
```kotlin
"memory" to listOf(
    "core_memory_read", "core_memory_append", "core_memory_replace",
    "archival_memory_insert", "archival_memory_search", "conversation_search", "save_memory"
)
```

Add aliases: "remember" → "memory", "recall" → "memory", "forget" → "memory".

### 7.4 M13 — Callback Wiring Deduplication

Extract from `AgentController`:
```kotlin
private fun wirePanel(panel: AgentDashboardPanel, isMirror: Boolean) {
    // All 30+ callback wiring calls
    // if (!isMirror) { ... state initialization callbacks ... }
}
```

`init` block calls `wirePanel(dashboard, isMirror = false)`.
`wireExtraPanel()` calls `wirePanel(panel, isMirror = true)`.

---

## 8. Work Unit 7: Prompt Tightening (H3, H4, M3, M4, M5, M15)

**Issues:** 6 prompt/schema changes
**Depends on:** Nothing (fully independent)
**Files modified:**
- MODIFIED: `orchestrator/PromptAssembler.kt`
- MODIFIED: `tools/integration/JiraTool.kt`
- MODIFIED: `tools/integration/BambooBuildsTool.kt`
- MODIFIED: `tools/DynamicToolSelector.kt`
- MODIFIED: 8 files in `resources/agents/`
- MODIFIED: Multiple files in `resources/skills/`

### 8.1 H3 — Recency Zone Tightening

Move from recency zone to context zone:
- `MEMORY_RULES` (~117 tokens)
- `CONTEXT_MANAGEMENT_RULES` (~91 tokens)
- `STEERING_RULES` (~225 tokens)
- `RENDERING_RULES_COMPACT` (~815 tokens)

Recency zone after: PLANNING_RULES, DELEGATION_RULES, FEW_SHOT_EXAMPLES, RULES, integration_rules, COMMUNICATION, BOOKEND (7 sections, down from 11).

### 8.2 H4 — Meta-Tool Parameter Normalization

**JiraTool:** Normalize all issue identifiers to `key`. Actions that currently use `issue_key` or `issue_id` accept `key` as primary, with fallback alias parsing:
```kotlin
val key = args["key"] ?: args["issue_key"] ?: args["issue_id"]
    ?: return missingParam("key")
```

Update tool description to list `key` as the parameter name for all actions.

**BambooBuildsTool:** Normalize to `build_key`. Actions using `result_key` accept `build_key` as primary:
```kotlin
val buildKey = args["build_key"] ?: args["result_key"]
    ?: return missingParam("build_key")
```

### 8.3 M3 — Worker Personas: Add worker_complete

Add to each of the 8 persona files in `resources/agents/`:
```markdown
## Completion

When your task is complete, call `worker_complete` with your full findings.
The parent agent ONLY sees your worker_complete output — tool call history is not visible.
Include all relevant details, file paths, and recommendations in your result.
```

### 8.4 M4 — Tool Name Sweep

Search all `.md` files in `resources/agents/` and `resources/skills/` for old-style tool names. Replace with meta-tool syntax:

| Old | New |
|-----|-----|
| `git_blame` | `git(action="blame")` |
| `git_status` | `git(action="status")` |
| `git_diff` | `git(action="diff")` |
| `jira_get_ticket` | `jira(action="get_ticket")` |
| `sonar_issues` | `sonar(action="issues")` |
| `bamboo_build_status` | `bamboo_builds(action="build_status")` |

Also update the `create-skill` SKILL.md tool count from "86 available tools" to "68 registered tools (15 meta-tools with 144 actions)".

### 8.5 M5 — False Positive Keywords

**File:** `tools/DynamicToolSelector.kt`

Remove from keyword groups:
- "run", "test", "tests" from runtime group
- "implement", "implementation", "override" from vcs group

These words appear in virtually every coding conversation and cause unnecessary tool activation. The `request_tools` escape hatch allows the LLM to self-serve if it genuinely needs these tools.

### 8.6 M15 — Persistence Deduplication

**File:** `orchestrator/PromptAssembler.kt`

Remove the persistence sentence from `CORE_IDENTITY` directive 1:
```kotlin
// Before (CORE_IDENTITY):
// "1. Keep working until the user's task is fully resolved. Do not stop early..."
// After (CORE_IDENTITY):
// "1. Verify your work with diagnostics, tests, or inspection before declaring done."
```

The persistence instruction lives authoritatively in `PERSISTENCE_AND_COMPLETION` (primacy zone position 2) and `BOOKEND` (final position). CORE_IDENTITY directive 1 slot freed for verification emphasis.

---

## 9. Work Unit 8: Session Resume Safety (H1, H2, M17)

**Issues:** H1 (SelfCorrectionGate restore), H2 (WorkerSession retry), M17 (resume budget check)
**Depends on:** WU1 partially (SessionScope for H1 wiring)
**Files modified:**
- MODIFIED: `runtime/ConversationSession.kt`
- MODIFIED: `runtime/SelfCorrectionGate.kt`
- MODIFIED: `runtime/WorkerSession.kt`
- MODIFIED: `tools/builtin/SpawnAgentTool.kt`

### 9.1 H1 — SelfCorrectionGate State Restoration

Add to `SelfCorrectionGate`:
```kotlin
fun restoreFromLedger(ledger: ChangeLedger) {
    for (entry in ledger.entries()) {
        if (entry.type == ChangeType.EDIT || entry.type == ChangeType.CREATE) {
            val state = if (entry.verified) FileState.VERIFIED
                        else if (entry.verificationError != null) FileState.FAILED
                        else FileState.PENDING_VERIFICATION
            fileStates[entry.filePath] = FileVerificationState(
                state = state,
                retryCount = entry.retryCount ?: 0,
                lastError = entry.verificationError
            )
        }
    }
}
```

Call from `ConversationSession.load()` after loading the ChangeLedger:
```kotlin
scope.selfCorrectionGate.restoreFromLedger(scope.changeLedger)
```

### 9.2 H2 — WorkerSession API Retry

Add to `WorkerSession.runReactLoop()`:
```kotlin
private suspend fun callBrainWithRetry(messages: List<ChatMessage>, tools: List<ToolDefinition>): ApiResult<ChatCompletion> {
    var lastError: ApiResult.Error? = null
    repeat(3) { attempt ->
        when (val result = brain.chat(messages, tools)) {
            is ApiResult.Success -> return result
            is ApiResult.Error -> {
                lastError = result
                if (result.errorType == ErrorType.RATE_LIMITED || result.statusCode in 500..599) {
                    val delay = (1000L * (1 shl attempt)) + (0..500).random()
                    delay(delay)
                } else {
                    return result // non-retryable error
                }
            }
        }
    }
    return lastError!!
}
```

### 9.3 M17 — Resume Budget Check

In `SpawnAgentTool.executeResume()`, before replaying transcript:
```kotlin
val transcriptTokens = transcript.sumOf { TokenEstimator.estimate(it.content ?: "") }
val workerBudget = AgentSettings.getInstance(project).state.workerMaxInputTokens
if (transcriptTokens > workerBudget * 0.8) {
    // Summarize instead of replaying verbatim
    val summary = LlmBrainSummarizationClient(brain).summarize(transcript)
    // Inject summary as a single context message instead of full replay
    return resumeWithSummary(summary, workerConfig)
}
```

---

## 10. Work Unit 9: Test Updates & Documentation

**Depends on:** All previous work units
**Files modified:**
- MODIFIED: Various test files in `agent/src/test/`
- MODIFIED: `agent/CLAUDE.md`
- MODIFIED: Root `CLAUDE.md` (if needed)
- MODIFIED: `docs/audits/2026-04-03-comprehensive-architecture-audit.md` (mark issues as fixed)

### 10.0 Shared VERIFICATION_TOOLS Constant

The audit found that `SelfCorrectionGate.VERIFICATION_TOOLS` and `BackpressureGate.VERIFICATION_TOOLS` are independently maintained identical lists. Extract to a shared constant:

```kotlin
// runtime/VerificationConstants.kt
object VerificationConstants {
    val VERIFICATION_TOOLS = setOf("diagnostics", "run_inspections", "runtime_exec", "coverage")
}
```

Both gates reference `VerificationConstants.VERIFICATION_TOOLS`.

### 10.1 Test Updates

Each work unit must update its affected tests:

| Work Unit | Test Files |
|-----------|-----------|
| WU1 (SessionScope) | New `SessionScopeTest.kt`, update `ConversationSessionTest.kt` |
| WU2 (processLlmSuccess) | Update `SingleAgentSessionTest.kt`, new `ParsedResponseTest.kt` |
| WU3 (Completion) | Update `CompletionGatekeeperTest.kt`, `SingleAgentSessionTest.kt` |
| WU4 (Surgical) | Update `EventStoreTest.kt`, `FileOwnershipRegistryTest.kt`, etc. |
| WU5 (Context) | Update `EventSourcedContextBridgeTest.kt`, `CondenserPipelineTest.kt` |
| WU6 (Wiring) | Update `PlanManagerTest.kt`, `ToolCategoryRegistryTest.kt` |
| WU7 (Prompts) | Update `PromptAssemblerTest.kt`, `JiraToolTest.kt`, `BambooBuildsToolTest.kt` |
| WU8 (Resume) | Update `SelfCorrectionGateTest.kt`, `WorkerSessionTest.kt`, `SpawnAgentToolTest.kt` |

### 10.2 Documentation Updates

- Update `agent/CLAUDE.md`:
  - Remove references to `forceTextOnly` permanent flag
  - Update completion detection description (3 paths, not 5 mechanisms)
  - Update tool count if any tools added/removed
  - Document `SessionScope` in architecture section
  - Document `UiCallbacks` pattern

- Update audit report: mark all 33 issues as FIXED with commit references

### 10.3 Verification

After all work units complete:
```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
./gradlew :core:test
./gradlew verifyPlugin
./gradlew buildPlugin
```

---

## 11. Execution Summary

| Phase | Work Units | Parallelizable | Est. Tasks |
|-------|-----------|----------------|------------|
| A | WU1 (SessionScope), WU4 (Surgical), WU5 (Context), WU7 (Prompts) | Yes — 4 parallel | 22 |
| B | WU2 (Decompose), WU6 (Wiring), WU8 (Resume) | Yes — 3 parallel | 14 |
| C | WU3 (Completion) | Sequential | 4 |
| D | WU9 (Tests + Docs) | Sequential | 5 |
| **Total** | **9 work units** | | **~45 tasks** |

All 33 audit findings are addressed. No issues deferred.
