# Robust Agent Completion Detection

**Date:** 2026-03-26
**Status:** Approved (with expert review changes incorporated)
**Scope:** `:agent` module — `SingleAgentSession`, new `CompletionGatekeeper`, new `AttemptCompletionTool`

## Problem

The orchestrator returns `Completed` whenever the LLM responds without tool calls (line 685 of `SingleAgentSession.kt`). This is the same "no tool calls = done" pattern used by Claude Code and Codex CLI, and both have documented premature completion bugs (Codex issues #5264, #14414 with 30-50% occurrence).

Common failure modes:
1. **Post-compression stop** — after context compression, LLM loses detailed context and responds with a vague summary instead of continuing work
2. **Plan abandonment** — LLM completes 2 of 7 plan steps, then stops
3. **Output truncation masking** — `finishReason=length` is handled, but `finishReason=stop` with a partial response is accepted as completion
4. **Unfulfilled intent without pattern match** — LLM says "here's what I did" (passive summary) instead of "let me check" (active intent), bypassing `TOOL_INTENT_PATTERNS`

## Research Summary

Analysis of 10 production agents (Claude Code, Codex CLI, Cline, Cursor, Aider, SWE-agent, OpenHands, AutoGen, LangGraph, Devin) identified 5 completion patterns. Full research at `docs/research/agent-completion-decision-research.md`.

**Most robust pattern:** Explicit completion tool (Cline's `attempt_completion`, SWE-agent's `submit`). The model must actively call a tool to declare completion — a response without tool calls does NOT end the loop.

**Novel additions (not found in any existing agent):**
- Plan-aware completion gate (check incomplete plan steps before accepting)
- Post-compression continuation gate (block completion immediately after context compression)

## Design

### The `attempt_completion` Tool

```
name: attempt_completion
description: "Declare that you have finished the user's request. Call this ONLY when the
entire task is fully resolved — not when completing individual plan steps (use update_plan_step
for that). Your completion may be blocked if there is unfinished work."

parameters:
  result: String (required) — summary of what was accomplished
  command: String? (optional) — a command for the user to verify the result
```

- Always available (cannot be disabled via ToolPreferences)
- Added to protected tools list in ContextManager (never pruned during compression)
- Registered in AgentService alongside other always-on tools
- **Excluded from WorkerSession tool set** — subagents use the simple "no tool calls = done" pattern (short-lived 10-iteration scoped sessions don't need gating)

### System Prompt Addition

```
When you have fully completed ALL parts of the user's request, call the attempt_completion tool.
Do not end your response without either calling a tool or calling attempt_completion.
If you stop without calling any tool, you will be asked to continue.
```

### Completion Flow

```
LLM responds:

├── Has attempt_completion alongside OTHER tool calls?
│   → Execute other tools first, DISCARD the attempt_completion call
│   → Inject: "You called attempt_completion alongside other tools. Complete your
│     tool calls first, then call attempt_completion separately when truly done."
│   → continue loop
│
├── Has attempt_completion as the ONLY tool call?
│   → Run CompletionGatekeeper
│   → All gates pass? → return SingleAgentResult.Completed
│   → Gate blocks? → return gate block as ToolResult.error, continue loop
│
├── Has other tool calls (no attempt_completion)?
│   → Execute normally (existing behavior)
│   → Reset consecutiveNoToolResponses = 0
│
└── No tool calls at all?
    → Run TOOL_INTENT_PATTERNS check (existing, catches "let me check" etc.)
    → Check confused-response heuristic (see below)
    → consecutiveNoToolResponses++
    → First time (consecutiveNoToolResponses == 1)?
       → Inject nudge: "You responded without calling any tools. If you've completed
          the task, call attempt_completion with a summary. If you have more work to do,
          make your next tool call now."
       → continue loop
    → Second consecutive time (consecutiveNoToolResponses >= 2)?
       → Run CompletionGatekeeper as implicit completion attempt
          (use LLM's text as the result summary)
       → All gates pass? → return SingleAgentResult.Completed
       → Gate blocks? → inject continuation message, continue loop
```

### Confused-Response Heuristic

Before treating a no-tool response as a potential completion, check if the LLM is confused rather than done:

```kotlin
fun isConfusedResponse(content: String): Boolean {
    val trimmed = content.trim()
    return trimmed.length < 100 || trimmed.count { it == '?' } >= 2
}
```

If confused: inject "What specifically are you unsure about? Describe the problem and I'll help." instead of running the nudge/gatekeeper path. Reset `consecutiveNoToolResponses` to 0.

### CompletionGatekeeper

New class that orchestrates all completion gates. Gates run in order — first block wins.

```kotlin
class CompletionGatekeeper(
    private val planManager: PlanManager?,
    private val selfCorrectionGate: SelfCorrectionGate,
    private val loopGuard: LoopGuard,
    private val iterationsSinceCompression: () -> Int,
    private val postCompressionCompletionAttempted: () -> Boolean,
    private val onPostCompressionAttempted: () -> Unit
) {
    /** Tracks how many times each gate has blocked without progress. */
    private var planGateBlockCount = 0
    private var lastPlanIncompleteCount = Int.MAX_VALUE
    private var totalCompletionAttempts = 0
    private val MAX_PLAN_BLOCKS_WITHOUT_PROGRESS = 3
    private val MAX_TOTAL_COMPLETION_ATTEMPTS = 5
}
```

**Gate execution order** (PostCompression first — re-orient before checking plan):

1. PostCompression
2. Plan Completion
3. SelfCorrectionGate (existing)
4. LoopGuard (existing)

**Gate 1: Post-Compression Continuation**

```kotlin
fun checkPostCompression(): ChatMessage? {
    if (iterationsSinceCompression() > 2) return null
    if (postCompressionCompletionAttempted()) return null

    onPostCompressionAttempted()
    return ChatMessage(
        role = "user",
        content = "COMPLETION BLOCKED: Context was compressed recently. You may have lost " +
            "track of the task. Review the [CONTEXT COMPRESSED] summary above and the " +
            "active plan (if any). If there is remaining work, continue. " +
            "If truly done, call attempt_completion again."
    )
}
```

- More than 2 iterations since compression → gate passes
- Already nudged once for this compression event → gate passes
- Recent compression + first attempt → blocks once

**Gate 2: Plan Completion (with escalation cap)**

```kotlin
fun checkPlanCompletion(): ChatMessage? {
    val plan = planManager?.currentPlan ?: return null
    val incomplete = plan.steps.filter {
        it.status != PlanStepStatus.COMPLETED && it.status != PlanStepStatus.SKIPPED
    }
    if (incomplete.isEmpty()) return null

    // Track progress: if same number of incomplete steps, no progress was made
    if (incomplete.size == lastPlanIncompleteCount) {
        planGateBlockCount++
    } else {
        planGateBlockCount = 0  // Progress made, reset
    }
    lastPlanIncompleteCount = incomplete.size

    // After MAX_PLAN_BLOCKS_WITHOUT_PROGRESS with no progress, escalate
    if (planGateBlockCount >= MAX_PLAN_BLOCKS_WITHOUT_PROGRESS) {
        return ChatMessage(
            role = "user",
            content = "COMPLETION BLOCKED (${planGateBlockCount}x): ${incomplete.size} plan steps " +
                "still incomplete with no progress. To proceed, call update_plan_step for each:\n" +
                incomplete.joinToString("\n") { "- update_plan_step(step=\"${it.title}\", status=\"skipped\", comment=\"Not needed\")" } +
                "\n\nOr continue working on them."
        )
    }

    // First block: detailed message
    return ChatMessage(
        role = "user",
        content = "COMPLETION BLOCKED: Your plan has ${incomplete.size} incomplete steps:\n" +
            incomplete.mapIndexed { i, step ->
                "${i+1}. [${step.status}] ${step.title}"
            }.joinToString("\n") +
            "\n\nContinue working on the next incomplete step. " +
            "If a step is no longer needed, call update_plan_step to mark it as skipped."
    )
}
```

- No plan → gate passes
- All steps completed/skipped → gate passes
- Incomplete steps, first block → detailed list
- Incomplete steps, 3+ blocks without progress → escalated message with exact skip commands
- **After MAX_TOTAL_COMPLETION_ATTEMPTS (5) across all gates → force-accept** (see orchestration)

**Gate 3: SelfCorrectionGate (existing)**

```kotlin
fun checkSelfCorrection(): ChatMessage? {
    return selfCorrectionGate.checkCompletionReadiness()
}
```

**Gate 4: LoopGuard (existing)**

```kotlin
fun checkLoopGuard(): ChatMessage? {
    return loopGuard.beforeCompletion()
}
```

**Orchestration method with force-accept cap:**

```kotlin
fun checkCompletion(): ChatMessage? {
    totalCompletionAttempts++

    // Force-accept after too many blocked attempts (prevents infinite blocking)
    if (totalCompletionAttempts > MAX_TOTAL_COMPLETION_ATTEMPTS) {
        LOG.warn("CompletionGatekeeper: force-accepting after $totalCompletionAttempts attempts")
        return null  // All gates bypassed
    }

    checkPostCompression()?.let { return it }
    checkPlanCompletion()?.let { return it }
    checkSelfCorrection()?.let { return it }
    checkLoopGuard()?.let { return it }
    return null  // All gates passed
}
```

### Budget TERMINATE Override

When `BudgetEnforcer` reaches `TERMINATE` status, bypass the CompletionGatekeeper entirely. In `SingleAgentSession`, the TERMINATE handler (lines 306-369) already runs before the completion path. Add this logic:

```kotlin
BudgetEnforcer.BudgetStatus.TERMINATE -> {
    // If the LLM's last response was attempt_completion or text-only (implicit completion),
    // accept it as completed rather than failing or rotating.
    if (lastResponseWasCompletionAttempt || consecutiveNoToolResponses > 0) {
        return SingleAgentResult.Completed(
            content = lastResponseContent,
            summary = "Completed (budget exhausted, gates bypassed)",
            tokensUsed = totalTokensUsed,
            artifacts = allArtifacts,
            scorecard = buildScorecard(sessionId, "completed_forced", ...)
        )
    }
    // Otherwise: existing TERMINATE behavior (context rotation or fail)
}
```

### AttemptCompletionTool Execution

The tool returns a special `ToolResult` variant that signals completion:

```kotlin
class AttemptCompletionTool(
    private val gatekeeper: CompletionGatekeeper
) : AgentTool {
    override val name = "attempt_completion"

    override suspend fun execute(args: JsonObject): ToolResult<String> {
        val result = args["result"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required parameter: result")
        val command = args["command"]?.jsonPrimitive?.content

        val block = gatekeeper.checkCompletion()
        if (block != null) {
            return ToolResult.error(block.content ?: "Completion blocked")
        }

        // Signal completion via special result type
        return ToolResult.completion(
            data = result,
            summary = "Task completed: $result",
            verifyCommand = command
        )
    }
}
```

**Signaling mechanism:** `ToolResult.completion()` is a new sealed variant of `ToolResult`. After tool execution in `SingleAgentSession`, check:

```kotlin
when (toolResult) {
    is ToolResult.Completion -> {
        // Skip remaining tool calls in this batch, exit loop
        return SingleAgentResult.Completed(
            content = toolResult.data,
            summary = toolResult.summary,
            ...
        )
    }
    else -> { /* normal tool result processing */ }
}
```

No callbacks, no flags, no exceptions.

### State Variables in SingleAgentSession

```kotlin
private var consecutiveNoToolResponses = 0
private var iterationsSinceCompression = Int.MAX_VALUE
private var postCompressionCompletionAttempted = false
```

Reset `consecutiveNoToolResponses = 0` on any tool call.
Reset `iterationsSinceCompression = 0` and `postCompressionCompletionAttempted = false` on any compression event.
Increment `iterationsSinceCompression` at the top of each iteration.

### Observability

New metrics tracked in `AgentMetrics`:
- `completionAttemptCount` — total `attempt_completion` calls per session
- `completionGateBlocks` — map of gate name → block count
- `forcedCompletions` — count of force-accepted completions (budget override or max attempts)
- `nudgeCount` — times the "no tool calls" nudge was injected

Logged at INFO level for expected gate blocks, WARN for forced completions.

Gate block messages shorten after first occurrence per gate:
- First block: full detailed message (step list, instructions)
- Subsequent blocks for same gate: terse "COMPLETION BLOCKED: N steps still incomplete. Mark as skipped or complete them."

## Files Changed

### New Files
- `agent/src/main/kotlin/.../runtime/CompletionGatekeeper.kt` — gate orchestration with escalation cap
- `agent/src/main/kotlin/.../tools/builtin/AttemptCompletionTool.kt` — the tool
- `agent/src/test/kotlin/.../runtime/CompletionGatekeeperTest.kt`
- `agent/src/test/kotlin/.../tools/builtin/AttemptCompletionToolTest.kt`

### Modified Files
- `agent/src/main/kotlin/.../runtime/SingleAgentSession.kt` — new state variables, modified completion path (lines 685-789), nudge logic, compression tracking, confused-response heuristic, budget TERMINATE override
- `agent/src/main/kotlin/.../AgentService.kt` — register AttemptCompletionTool (exclude from WorkerSession)
- `agent/src/main/kotlin/.../context/ContextManager.kt` — add `attempt_completion` to protected tools list
- `agent/src/main/kotlin/.../orchestrator/AgentOrchestrator.kt` — pass PlanManager to SingleAgentSession
- `agent/src/main/kotlin/.../tools/ToolResult.kt` — add `Completion` sealed variant
- `agent/src/main/kotlin/.../runtime/AgentMetrics.kt` — add completion-related counters
- System prompt assembly — add `attempt_completion` instruction

### Not Changed
- `SelfCorrectionGate` — used as-is by CompletionGatekeeper
- `LoopGuard` — used as-is by CompletionGatekeeper
- `OutputValidator` — stays after all gates pass
- `BudgetEnforcer` — unchanged except TERMINATE path gains completion override
- `TOOL_INTENT_PATTERNS` — stays in place, runs before nudge path
- `WorkerSession` — keeps existing "no tool calls = done" pattern

## Expert Review Summary

Reviewed by enterprise agentic AI architecture expert. Verdict: **APPROVED WITH CHANGES** (all changes incorporated above).

| # | Severity | Issue | Resolution |
|---|----------|-------|------------|
| C1 | CRITICAL | `attempt_completion` + other tools in same response | Execute other tools first, discard completion call, inject "finish tools first" |
| C2 | CRITICAL | No cap on plan gate blocks — infinite loop | `totalCompletionAttempts` cap at 5, plan gate progress tracking |
| I1 | IMPORTANT | Budget TERMINATE during gate blocking | Bypass gatekeeper, accept as completed with forced flag |
| I2 | IMPORTANT | Signaling mechanism unspecified | `ToolResult.Completion` sealed variant, checked in tool-result loop |
| I3 | IMPORTANT | WorkerSession should not have tool | Excluded from worker tool set |
| I4 | IMPORTANT | Gate messages verbose on repeat | Shortened after first block per gate |
| M1 | MINOR | Gate order suboptimal | Reordered: PostCompression → Plan → SelfCorrection → LoopGuard |
| M2 | MINOR | Prompt may cause eager calls | Softened to "When you have fully completed ALL parts" |
| M3 | MINOR | No completion metrics | Added to AgentMetrics |
| M4 | MINOR | Implicit path accepts confused responses | Added confused-response heuristic (short + question marks) |
