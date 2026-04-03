# Ralph Loop Adaptation — Design Spec

**Date:** 2026-04-03
**Status:** Draft
**Approach:** Ralph Loop + Reviewer Gate (Approach B)

## 1. Overview

Adapt the Ralph Loop pattern (self-referential iterative improvement) for the Workflow Orchestrator plugin's AI agent. After the agent completes a task, it automatically starts a **new session** with the **same original prompt**. The agent reads its prior work from the filesystem (the "self-referential" mechanism), reviews it, and improves. A **reviewer subagent** evaluates each iteration and decides whether to accept or request improvements.

### Core Ralph Invariants (preserved from the original)

1. **Same prompt re-fed** — the user's original message is the task for every iteration
2. **Fresh context** — each iteration is a new `ConversationSession` (clean context window)
3. **Self-referential via filesystem** — the agent sees prior work by reading files and git history
4. **Completion gate** — loop only exits when quality criteria are met (reviewer ACCEPT) or safety limits hit

### What's Added Beyond Pure Ralph

- **Reviewer gate** — a `reviewer` subagent evaluates work between iterations (SWE-agent pattern)
- **Feedback injection** — reviewer's IMPROVE feedback is injected into the next iteration's system prompt
- **Cost budget** — cumulative cost tracking with hard cap across all iterations
- **Rollback via LocalHistory** — IntelliJ's LocalHistory checkpoints per iteration (no git dependency)
- **Crash recovery** — loop state survives IDE restarts
- **Auto-expand** — extends max_iterations when active progress is detected (OmX pattern)

## 2. State Machine

```
IDLE ──[user starts loop]──→ EXECUTING
                                │
                     [attempt_completion] 
                                │
                                ▼
                          AWAITING_REVIEW ──[spawn reviewer]──→ REVIEWING
                                                                   │
                                                    ┌──────────────┼──────────────┐
                                                    │              │              │
                                                 ACCEPT        IMPROVE     REVIEWER_ERROR
                                                    │              │              │
                                                    ▼              ▼              ▼
                                               COMPLETED      EXECUTING    EXECUTING
                                                             (iteration++)  (skip review,
                                                                            continue)

Safety exits from ANY state:
  ──[user cancel]──→ CANCELLED
  ──[IDE crash]──→ INTERRUPTED ──[IDE restart + resume]──→ EXECUTING
  ──[budget exhausted]──→ FORCE_COMPLETED
  ──[max iterations + no auto-expand]──→ FORCE_COMPLETED
  ──[3 consecutive IMPROVE without progress]──→ FORCE_COMPLETED
```

## 3. Data Model

### RalphLoopState

```kotlin
@Serializable
data class RalphLoopState(
    // Identity
    val loopId: String,                           // UUID
    val projectPath: String,

    // Configuration (immutable after start)
    val originalPrompt: String,                   // User's message, re-fed verbatim each iteration
    val maxIterations: Int,                       // Default 10. 0 = unlimited (auto-expand only)
    val maxCostUsd: Double,                       // Default 10.0. Budget cap for entire loop
    val reviewerEnabled: Boolean,                 // Default true (Approach B)

    // Current state
    val phase: RalphPhase,
    val iteration: Int,                           // 1-based
    val totalCostUsd: Double,                     // Accumulated across all iterations
    val totalTokensUsed: Long,

    // Cross-iteration context
    val reviewerFeedback: String?,                // Latest reviewer feedback → injected into next iteration
    val priorAccomplishments: String?,            // One-line summary of cumulative work

    // History
    val iterationHistory: List<RalphIterationRecord>,

    // Safety
    val autoExpandCount: Int,                     // Times max_iterations was auto-expanded
    val consecutiveImprovesWithoutProgress: Int,  // Reviewer always says IMPROVE but no files change

    // Timestamps
    val startedAt: String,                        // ISO-8601
    val lastIterationAt: String?,
    val completedAt: String?,

    // Session tracking
    val currentSessionId: String?,                // Active session in this iteration
    val allSessionIds: List<String>,              // For metrics aggregation
)
```

### RalphPhase

```kotlin
@Serializable
enum class RalphPhase {
    EXECUTING,          // Agent working (inside SingleAgentSession)
    AWAITING_REVIEW,    // Agent completed, about to spawn reviewer
    REVIEWING,          // Reviewer evaluating
    COMPLETED,          // Reviewer accepted
    FORCE_COMPLETED,    // Safety limit hit (budget, iterations, stuck)
    CANCELLED,          // User cancelled
    INTERRUPTED,        // IDE crashed mid-loop
}
```

### RalphIterationRecord

```kotlin
@Serializable
data class RalphIterationRecord(
    val iteration: Int,
    val sessionId: String,
    val costUsd: Double,
    val tokensUsed: Long,
    val durationMs: Long,
    val reviewerVerdict: String?,   // "ACCEPT" | "IMPROVE" | null (no reviewer)
    val reviewerFeedback: String?,
    val filesChanged: List<String>,
)
```

### Persistence

- **Location:** `~/.workflow-orchestrator/{proj}/agent/ralph/{loopId}/ralph-state.json`
- **Written:** After every state transition (phase change, iteration increment)
- **Read:** On IDE startup (crash recovery), on loop resume
- **Deleted:** On COMPLETED, FORCE_COMPLETED, or CANCELLED (after final metrics saved)

## 4. Components

### 4.1 RalphLoopOrchestrator

**New file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopOrchestrator.kt`

The core loop manager. Sits between `AgentController` and the session system.

```kotlin
class RalphLoopOrchestrator(
    private val project: Project,
    private val agentService: AgentService,
) {
    private var state: RalphLoopState? = null

    /** Start a new Ralph Loop. Creates state, tags baseline, returns initial state. */
    fun startLoop(prompt: String, config: RalphLoopConfig): RalphLoopState

    /**
     * Called by AgentController after each session completes (attempt_completion).
     * Runs the reviewer, decides whether to continue, and returns the decision.
     * This is the heart of the loop.
     */
    suspend fun onSessionCompleted(
        result: AgentResult.Completed,
        scorecard: SessionScorecard?,
    ): RalphLoopDecision

    /** Cancel the active loop. */
    fun cancel(): RalphLoopState?

    /** Resume an interrupted loop (called from AgentStartupActivity). */
    fun resumeInterrupted(state: RalphLoopState)

    /** Build the <ralph_iteration> system prompt section for the current iteration. */
    fun buildIterationContext(): String

    /** Current state for UI display. Null if no active loop. */
    fun getCurrentState(): RalphLoopState?
}

data class RalphLoopConfig(
    val maxIterations: Int = 10,
    val maxCostUsd: Double = 10.0,
    val reviewerEnabled: Boolean = true,
)

sealed class RalphLoopDecision {
    /** Continue to next iteration. iterationContext is the <ralph_iteration> block. */
    data class Continue(val iterationContext: String) : RalphLoopDecision()
    /** Loop completed successfully. */
    data class Completed(val summary: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
    /** Loop force-stopped by a safety limit. */
    data class ForcedCompletion(val reason: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
}
```

### 4.2 onSessionCompleted — The Core Logic

```
1. Update cost tracking from scorecard
2. Create LocalHistory checkpoint: "Ralph iteration {N}"
3. Record RalphIterationRecord
4. Budget check: if totalCost >= maxCostUsd * 0.95 → FORCE_COMPLETED
5. Max iterations check:
   a. If iteration >= maxIterations AND shouldAutoExpand() → extend by 5, continue
   b. If iteration >= maxIterations AND !shouldAutoExpand() → FORCE_COMPLETED
   c. Auto-expand capped at 3 expansions (max +15 extra iterations)
6. If reviewerEnabled:
   a. Set phase = REVIEWING
   b. Spawn RalphReviewer (WorkerSession, reviewer type, read-only tools)
   c. Reviewer returns ACCEPT or IMPROVE(feedback)
   d. ACCEPT → phase = COMPLETED, return Completed
   e. IMPROVE:
      - Check stuck detection: if 3 consecutive IMPROVEs with no files changed → FORCE_COMPLETED
      - Update state: iteration++, reviewerFeedback = feedback, phase = EXECUTING
      - Return Continue(buildIterationContext())
7. If !reviewerEnabled (pure Ralph, no reviewer):
   - Always continue until max_iterations or budget
   - Return Continue(buildIterationContext())
```

### 4.3 Auto-Expand Logic

Borrowed from OmX. When the agent hits max_iterations but is making active progress:

```
shouldAutoExpand():
  - autoExpandCount >= 3 → false (hard cap)
  - Last iteration's git diff is empty → false (no progress)
  - Last iteration's reviewerFeedback mentions "minor" or "polish" → true (almost done)
  - Last iteration changed files → true (active progress)
```

When auto-expand triggers: `maxIterations += 5`, `autoExpandCount++`.

### 4.4 RalphReviewer

**New file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphReviewer.kt`

Lightweight evaluation using existing `WorkerSession` + `reviewer` worker type.

**Reviewer prompt:**

```
You are reviewing code changes made by another agent. Evaluate quality against the original task.

<original_task>
{originalPrompt}
</original_task>

<iteration>{N} of {maxIterations}</iteration>

<completion_summary>
{attempt_completion summary from the agent}
</completion_summary>

<files_changed>
{list of files modified across all iterations}
</files_changed>

<plan_status>
{plan steps with done/pending status, if a plan exists}
</plan_status>

<prior_reviewer_feedback>
{what you said last time, if iteration > 1}
</prior_reviewer_feedback>

Instructions:
1. Read the changed files to evaluate the actual code quality
2. Run diagnostics to check for errors
3. Assess whether the work fully satisfies the original task
4. Check for bugs, missing edge cases, or incomplete implementations
5. If you previously requested improvements, verify they were addressed

Respond with EXACTLY one of:
  ACCEPT — work meets requirements, no further iteration needed.
  IMPROVE: <specific, actionable feedback about what to change>

Be pragmatic. Don't request perfection — request correctness.
```

**Execution:**
- `WorkerSession` with `workerType = WorkerType.REVIEWER`
- Tools: `read_file`, `search_code`, `diagnostics`, `find_definition`, `find_references`, `run_inspections`
- `maxIterations = 10` (reviewer's own ReAct loop — usually 1-3 turns of reading files)
- Timeout: extracted from WorkerSession's iteration limit, no wall-clock timeout (per existing design)

**Response parsing:**
- First word: `ACCEPT` → ReviewVerdict.ACCEPT
- First word: `IMPROVE` → ReviewVerdict.IMPROVE, everything after `IMPROVE:` is the feedback
- Neither → default to IMPROVE with full response as feedback
- Reviewer error/timeout → skip review, continue to next iteration (log warning)

**Cost tracking:** Reviewer's token usage added to `totalCostUsd` via estimated cost computation.

### 4.5 System Prompt Injection

**Modified file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

Add new parameter `ralphIterationContext: String? = null` to `buildSingleAgentPrompt()`. Inject in the **context zone** between `<previous_results>` and the recency zone:

```kotlin
if (!ralphIterationContext.isNullOrBlank()) {
    sections.add(ralphIterationContext)
}
```

The content is built by `RalphLoopOrchestrator.buildIterationContext()`:

```xml
<ralph_iteration>
You are on iteration 3 of a self-improvement loop.
Your task is to review and improve upon work done in previous iterations.

## Original Task
{user's original prompt, verbatim}

## What Was Done in Previous Iterations
- Iteration 1: Created REST API endpoints for /users and /tasks
- Iteration 2: Added input validation and error handling

## Reviewer Feedback (from iteration 2)
IMPROVE: The /tasks endpoint doesn't handle pagination. The error responses
don't follow the project's ErrorResponse DTO pattern. Add unit tests for
the validation logic.

## Instructions
1. Read the files that were modified in previous iterations
2. Review the current state of the code against the original task
3. Address the reviewer's feedback specifically
4. Make improvements and call attempt_completion when done
</ralph_iteration>
```

### 4.6 AgentController Integration

**Modified file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

#### Starting a Ralph Loop

New method triggered by UI (toolbar button or `/ralph-loop` chat command):

```kotlin
fun startRalphLoop(prompt: String, config: RalphLoopConfig) {
    val state = ralphOrchestrator.startLoop(prompt, config)
    dashboard.appendStatus(
        "🔄 Ralph Loop started — iteration 1/${state.maxIterations} | Budget: $${state.maxCostUsd}",
        RichStreamingPanel.StatusType.INFO
    )
    // Execute first iteration normally
    executeTask(prompt, ralphIterationContext = ralphOrchestrator.buildIterationContext())
}
```

#### Intercepting Completion

In `handleResult()`, before the existing `AgentResult.Completed` handling:

```kotlin
is AgentResult.Completed -> {
    dashboard.flushStreamBuffer()

    val ralph = ralphOrchestrator.getCurrentState()
    if (ralph != null && ralph.phase == RalphPhase.EXECUTING) {
        // Ralph Loop active — don't end, enter review phase
        dashboard.appendStatus(
            "🔍 Reviewing iteration ${ralph.iteration}...",
            RichStreamingPanel.StatusType.INFO
        )

        scope.launch {
            val decision = ralphOrchestrator.onSessionCompleted(result, lastScorecard)
            withContext(Dispatchers.EDT) {
                when (decision) {
                    is RalphLoopDecision.Continue -> {
                        session = null  // Clear old session → next executeTask creates fresh one
                        dashboard.appendStatus(
                            "🔄 Ralph iteration ${ralph.iteration + 1} — reviewer requested improvements",
                            RichStreamingPanel.StatusType.INFO
                        )
                        executeTask(ralph.originalPrompt, ralphIterationContext = decision.iterationContext)
                    }
                    is RalphLoopDecision.Completed -> {
                        dashboard.appendStatus(
                            "✅ Ralph Loop completed after ${decision.iterations} iterations | Cost: $${String.format("%.2f", decision.totalCost)}",
                            RichStreamingPanel.StatusType.SUCCESS
                        )
                        dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                    }
                    is RalphLoopDecision.ForcedCompletion -> {
                        dashboard.appendStatus(
                            "⚠️ Ralph Loop stopped: ${decision.reason} | Cost: $${String.format("%.2f", decision.totalCost)}",
                            RichStreamingPanel.StatusType.WARNING
                        )
                        dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                    }
                }
            }
        }
        return  // Don't fall through to normal completion handling
    }

    // Normal (non-Ralph) completion
    dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
    // ... existing code ...
}
```

#### Cancellation

In `cancelTask()`, add:

```kotlin
ralphOrchestrator.cancel()?.let { finalState ->
    dashboard.appendStatus(
        "Ralph Loop cancelled at iteration ${finalState.iteration}",
        RichStreamingPanel.StatusType.WARNING
    )
}
```

#### executeTask Modification

Add `ralphIterationContext` parameter to `executeTask()`. Pass it through to `ConversationSession.create()` or inject via the session's system prompt builder.

### 4.7 Plan Continuity

When starting iteration N+1:

1. Load `plan.json` from iteration N's session directory
2. Pass to new `ConversationSession` via a new `initialPlan: AgentPlan?` parameter on `create()`
3. The new session's `PlanManager` restores the plan
4. Agent sees plan steps with their current done/pending status
5. Plan step status carries forward — iteration 2 doesn't redo iteration 1's completed steps

### 4.8 Crash Recovery

**Modified file:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt`

After existing interrupted session detection, add:

```kotlin
// Check for interrupted Ralph loops
val ralphDir = File(ProjectIdentifier.projectDir(projectPath), "agent/ralph")
if (ralphDir.exists()) {
    val activeLoops = ralphDir.listFiles()
        ?.mapNotNull { dir -> RalphLoopState.load(dir)?.takeIf { it.phase !in terminalPhases } }
        ?: emptyList()

    for (loop in activeLoops) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("workflow.agent")
            .createNotification(
                "Ralph Loop Interrupted",
                "Ralph Loop was interrupted at iteration ${loop.iteration}/${loop.maxIterations}. Cost so far: $${loop.totalCostUsd}",
                NotificationType.INFORMATION
            )
            .addAction(NotificationAction.createSimple("Resume") {
                agentService.ralphOrchestrator.resumeInterrupted(loop)
            })
            .addAction(NotificationAction.createSimple("Cancel") {
                RalphLoopState.delete(loop.loopId, projectPath)
            })
            .notify(project)
    }
}
```

### 4.9 Rollback via LocalHistory

Before each iteration begins, create a LocalHistory checkpoint using the existing `RollbackManager`:

```kotlin
private fun createIterationCheckpoint(iteration: Int) {
    val rollback = agentService.currentRollbackManager ?: return
    rollback.createCheckpoint("Ralph iteration $iteration")
}
```

Uses IntelliJ's built-in LocalHistory — no git operations, no commit pollution. Users can revert to any iteration's state via Edit > LocalHistory or the existing `rollback_changes` tool.

The `RollbackManager` already tracks file changes per checkpoint and supports reverting to any prior state. No new infrastructure needed.

### 4.10 Cost Budget

Budget is tracked per-loop across all iterations:

```
iteration 1: session cost $1.20 + reviewer cost $0.30 = $1.50
iteration 2: session cost $1.40 + reviewer cost $0.25 = $1.65
iteration 3: session cost $0.90 + reviewer cost $0.20 = $1.10
Total: $4.25
```

Budget check runs **before** spawning the reviewer (to avoid wasting cost on review when budget is nearly exhausted). If remaining budget < estimated reviewer cost (~$0.50), skip review and force-complete.

### 4.11 Observability

**Per-iteration logging** (to agent JSONL log):
```json
{"event": "ralph_iteration", "loopId": "abc123", "iteration": 3, "phase": "REVIEWING", "costUsd": 1.65, "filesChanged": ["src/api/UserController.kt"]}
{"event": "ralph_review", "loopId": "abc123", "iteration": 3, "verdict": "IMPROVE", "feedback": "Missing pagination..."}
```

**Loop-level metrics** (saved on completion):
- `RalphLoopScorecard` aggregates all `SessionScorecard`s for the loop's sessions
- Persisted at `~/.workflow-orchestrator/{proj}/agent/metrics/ralph-{loopId}.json`
- Fields: totalIterations, totalCost, totalTokens, totalDuration, reviewerAcceptRate, filesModified

### 4.12 UI Integration

| State | Chat UI | Status Bar |
|---|---|---|
| Loop starting | "🔄 Ralph Loop started — iteration 1/10 \| Budget: $10.00" | "Ralph 1/10" |
| Agent executing | Normal agent UI (streaming, tool calls) | "Ralph 1/10 ⚡" |
| Reviewing | "🔍 Reviewing iteration 1..." (spinner) | "Ralph 1/10 🔍" |
| Reviewer improve | "🔄 Ralph iteration 2 — reviewer requested improvements" | "Ralph 2/10" |
| Completed | "✅ Ralph Loop completed after 3 iterations \| Cost: $4.25" | (cleared) |
| Force completed | "⚠️ Ralph Loop stopped: Budget exhausted ($9.80/$10.00)" | (cleared) |
| Cancelled | "Ralph Loop cancelled at iteration 3" | (cleared) |

**Activation:** 
- Chat command: `/ralph-loop` or typing a message with the Ralph toggle enabled
- Toolbar: Toggle button next to Plan Mode button
- Settings: Default values for maxIterations, maxCostUsd, reviewerEnabled under AI & Advanced

### 4.13 Race Condition Handling

While Ralph is between iterations (reviewing, creating new session):
- **User sends message:** Queued via existing `pendingUserMessages` mechanism. Processed after current iteration completes.
- **User clicks cancel:** `cancelTask()` cancels current session + sets ralph phase to CANCELLED
- **User clicks new chat:** Calls `cancel()` on ralph orchestrator first, then `newChat()`

## 5. File Layout

### New Files

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/
├── RalphLoopState.kt          # Data classes (state, config, record, phase) + persistence
├── RalphLoopOrchestrator.kt   # Core loop logic, state machine, auto-expand
├── RalphReviewer.kt           # Reviewer prompt template + response parsing
└── RalphLoopScorecard.kt      # Cross-session metrics aggregation
```

### Modified Files

| File | Change |
|---|---|
| `AgentController.kt` | Add `ralphOrchestrator` field, intercept `handleResult()`, modify `cancelTask()`, add `startRalphLoop()` |
| `PromptAssembler.kt` | Add `ralphIterationContext: String?` parameter, inject `<ralph_iteration>` section |
| `AgentOrchestrator.kt` | Pass `ralphIterationContext` through to `PromptAssembler` |
| `ConversationSession.kt` | Add `initialPlan: AgentPlan?` parameter to `create()` for plan continuity |
| `AgentStartupActivity.kt` | Add interrupted Ralph loop detection + resume notification |
| `AgentService.kt` | Hold `ralphOrchestrator` reference |
| Settings page (AI & Advanced) | Add Ralph Loop defaults: maxIterations, maxCostUsd, reviewerEnabled |

## 6. Safety Guarantees

| Risk | Mitigation |
|---|---|
| Infinite loop | `maxIterations` (default 10) + auto-expand capped at 3 expansions (+15 max) |
| Cost runaway | `maxCostUsd` (default $10) checked before each iteration AND before reviewer |
| Reviewer always says IMPROVE | 3 consecutive IMPROVEs with no files changed → force-complete |
| Reviewer errors/timeouts | Skip review, continue to next iteration, log warning |
| IDE crash mid-loop | State persisted to disk, detected on restart, resume offered |
| Agent makes code worse | LocalHistory checkpoints per iteration enable rollback to any prior state |
| User wants to stop | Cancel button works at any point — cancels session + loop |
| Concurrent user messages | Queued via `pendingUserMessages`, processed after iteration |

## 7. What This Design Does NOT Include

- **Shell-based verification** (Goose pattern) — could be added as a pre-reviewer check in future
- **Config diversity** (SWE-agent pattern) — each iteration uses the same agent config. Could rotate models/tools in future
- **Parallel attempts** (SWE-agent best-of-N) — sequential only. Could add parallel iterations with chooser in future
- **Lint/test auto-feedback** (Aider pattern) — the reviewer is the quality gate, not automated tests. Could add as a reviewer enhancement

These are explicitly deferred, not forgotten. Each can be layered on top of this foundation.
