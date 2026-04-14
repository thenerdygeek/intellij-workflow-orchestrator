# Tooling Architecture TDD Refactors

Date: 2026-04-14
Status: Draft
Scope: 3 independent refactors in the `:agent` module, each driven by TDD

## Context

Code review of the `feature/tooling-architecture-enhancements` branch identified 6 structural
quality issues. Three were selected for careful refactoring based on risk and impact:

1. **ToolResult parameter sprawl** — 16 fields, 10 mutually exclusive boolean flags, 988 constructor call sites
2. **Streaming parse O(n²)** — full re-parse of accumulated text on every streaming chunk
3. **SubagentModels stringly-typed status** — raw strings where enums should be

These are independent and can be implemented in any order. Each follows a strict TDD cycle:
write tests defining the new contract, then implement to pass them.

---

## Refactor 1: ToolResult Companion Factory + Internal Sealed Type

### Problem

`ToolResult` is a data class with 16 fields. Only 4 (`content`, `summary`, `tokenEstimate`,
`artifacts`) are used by multiple tools. The remaining 12 are mutually exclusive flags and
associated data used by exactly one tool each. This creates two problems:

- **No type safety on flag combinations.** Nothing prevents `ToolResult(isCompletion = true, isPlanResponse = true)`.
- **Constructor fragility.** Adding a new field touches 988 call sites (every tool must acknowledge the new default).

The `AgentLoop` dispatches on these flags with an `if/else` chain that's error-prone and not
exhaustive.

### Approach: Companion Factory + Internal Sealed Type (Option C)

Keep the external `ToolResult` constructor signature unchanged so 988 call sites don't need
modification. Introduce a `ToolResultType` sealed class as a single `type` field that replaces
the boolean flags. Only the ~6 tools that set special flags need updating to use factory methods.

**Key constraint:** The existing constructor parameters (`artifacts`, `diff`, `artifact`,
`verifyCommand`) remain on the data class — they are NOT moved into the sealed type. Only the
boolean dispatch flags (`isCompletion`, `isPlanResponse`, etc.) are replaced. This means
call sites that set `verifyCommand = null` or `artifacts = listOf(...)` continue to work.

### New Types

```kotlin
// In AgentTool.kt

sealed class ToolResultType {
    /** Normal tool result (success). Default for all tools. */
    object Standard : ToolResultType()

    /** Tool execution failed. */
    object Error : ToolResultType()

    /** Agent is requesting task completion (AttemptCompletionTool). */
    object Completion : ToolResultType()

    /** Agent is responding in plan mode, optionally with plan steps (PlanModeRespondTool). */
    data class PlanResponse(
        val needsMoreExploration: Boolean,
        val steps: List<String> = emptyList(),
    ) : ToolResultType()

    /** Agent activated a skill (UseSkillTool). */
    data class SkillActivation(
        val skillName: String,
        val skillContent: String,
    ) : ToolResultType()

    /** Agent requested session handoff (NewTaskTool). */
    data class SessionHandoff(val context: String) : ToolResultType()

    /** Agent toggled plan mode (EnablePlanModeTool). */
    object PlanModeToggle : ToolResultType()
}
```

Note: `PlanCreated` was merged into `PlanResponse` because both are set exclusively by
`PlanModeRespondTool` — there is no separate `CreatePlanTool`.

### ToolResult Changes

The constructor keeps ALL existing fields. The `type` field is added with a default
that derives from `isError`:

```kotlin
data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),  // Kept — used by AgentLoop for modified-file tracking
    val isError: Boolean = false,
    val verifyCommand: String? = null,          // Kept — used by AttemptCompletionTool + SpawnAgentTool
    val diff: String? = null,                   // Kept — used by edit_file/create_file
    val artifact: ArtifactPayload? = null,      // Kept — used by RenderArtifactTool (typed, not Any?)
    // New: replaces boolean dispatch flags
    val type: ToolResultType = if (isError) ToolResultType.Error else ToolResultType.Standard,

    // ── Legacy boolean flags (deprecated, backward-compat) ────────────────
    // These remain as constructor parameters so existing call sites that set
    // them explicitly (e.g., tests with `isCompletion = true`) still compile.
    // They are ignored by the AgentLoop dispatch — only `type` is used.
    // Removal is a separate follow-up after all callers migrate.
    @Deprecated("Use type = ToolResultType.Completion") val isCompletion: Boolean = false,
    @Deprecated("Use ToolResult.planResponse() factory") val isPlanResponse: Boolean = false,
    @Deprecated("Use ToolResult.planResponse() factory") val needsMoreExploration: Boolean = false,
    @Deprecated("Use ToolResult.planResponse() factory") val planSteps: List<String> = emptyList(),
    @Deprecated("Use ToolResult.skillActivation() factory") val isSkillActivation: Boolean = false,
    @Deprecated("Use ToolResult.skillActivation() factory") val activatedSkillName: String? = null,
    @Deprecated("Use ToolResult.skillActivation() factory") val activatedSkillContent: String? = null,
    @Deprecated("Use ToolResult.sessionHandoff() factory") val isSessionHandoff: Boolean = false,
    @Deprecated("Use ToolResult.sessionHandoff() factory") val handoffContext: String? = null,
    @Deprecated("Use type = ToolResultType.PlanModeToggle") val enablePlanMode: Boolean = false,
) {
    companion object {
        const val ERROR_TOKEN_ESTIMATE = 5  // Must match existing value

        /** Standard error result. Existing factory — unchanged. */
        fun error(message: String, summary: String = message): ToolResult =
            ToolResult(message, summary, ERROR_TOKEN_ESTIMATE, isError = true)

        /** Task completion (AttemptCompletionTool). */
        fun completion(
            content: String,
            summary: String,
            tokenEstimate: Int,
            verifyCommand: String? = null,
        ): ToolResult = ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = tokenEstimate,
            verifyCommand = verifyCommand,
            type = ToolResultType.Completion,
        )

        /** Plan mode response (PlanModeRespondTool). */
        fun planResponse(
            content: String,
            summary: String,
            tokenEstimate: Int,
            needsMoreExploration: Boolean,
            steps: List<String> = emptyList(),
        ): ToolResult = ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = tokenEstimate,
            type = ToolResultType.PlanResponse(needsMoreExploration, steps),
        )

        /** Skill activation (UseSkillTool). */
        fun skillActivation(
            content: String,
            summary: String,
            tokenEstimate: Int,
            skillName: String,
            skillContent: String,
        ): ToolResult = ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = tokenEstimate,
            type = ToolResultType.SkillActivation(skillName, skillContent),
        )

        /** Session handoff (NewTaskTool). */
        fun sessionHandoff(
            content: String,
            summary: String,
            tokenEstimate: Int,
            context: String,
        ): ToolResult = ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = tokenEstimate,
            type = ToolResultType.SessionHandoff(context),
        )

        /** Enable plan mode (EnablePlanModeTool). */
        fun planModeToggle(
            content: String,
            summary: String,
            tokenEstimate: Int,
        ): ToolResult = ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = tokenEstimate,
            type = ToolResultType.PlanModeToggle,
        )
    }
}
```

### AgentLoop Dispatch Change

The `if/else` chain in the tool result handling section becomes a `when` block:

```kotlin
when (toolResult.type) {
    is ToolResultType.Completion -> {
        // existing completion handling (CompletionGatekeeper)
    }
    is ToolResultType.PlanResponse -> {
        val pr = toolResult.type as ToolResultType.PlanResponse
        // existing plan response handling using pr.needsMoreExploration, pr.steps
    }
    is ToolResultType.SkillActivation -> {
        val activation = toolResult.type as ToolResultType.SkillActivation
        // existing skill activation using activation.skillName, activation.skillContent
    }
    is ToolResultType.SessionHandoff -> {
        val handoff = toolResult.type as ToolResultType.SessionHandoff
        // existing session handoff using handoff.context
    }
    is ToolResultType.PlanModeToggle -> {
        // existing plan mode enable handling
    }
    is ToolResultType.Standard, is ToolResultType.Error -> {
        // normal tool result — no special dispatch
    }
}
```

The `when` is exhaustive — the compiler enforces all types are handled.

### Files Changed

| File | Change | Call sites affected |
|------|--------|---------------------|
| `tools/AgentTool.kt` | Add `ToolResultType` sealed class, add `type` field, add companion factories, deprecate boolean flags | 0 (additive) |
| `tools/builtin/AttemptCompletionTool.kt` | Use `ToolResult.completion()` factory | 1 |
| `tools/builtin/PlanModeRespondTool.kt` | Use `ToolResult.planResponse()` factory (covers both plan steps and exploration) | 1 |
| `tools/builtin/UseSkillTool.kt` | Use `ToolResult.skillActivation()` factory | 1 |
| `tools/builtin/NewTaskTool.kt` | Use `ToolResult.sessionHandoff()` factory | 1 |
| `tools/builtin/EnablePlanModeTool.kt` | Use `ToolResult.planModeToggle()` factory | 1 |
| `tools/builtin/SpawnAgentTool.kt` | Line 311: remove explicit `verifyCommand = null` (it's already the default) | 1 |
| `loop/AgentLoop.kt` | Replace `if/else` flag chain with `when (toolResult.type)` | 1 block |
| `testing/ToolTestingPanel.kt` | Acknowledged consumer — works via deprecated properties, no change needed now | 0 |
| All other tools (988 sites) | **No change** | 0 |

### TDD Test Plan

Tests written BEFORE implementation:

1. **Factory construction tests** — each factory method produces the correct `type` variant
2. **Default type derivation** — `ToolResult("c", "s", 10)` → `Standard`; `ToolResult(..., isError=true)` → `Error`; `ToolResult.error(...)` → `Error`
3. **Backward-compat deprecated properties** — `ToolResult.completion(...)` has `isCompletion == true`; `ToolResult.planResponse(...)` has `isPlanResponse == true`, etc.
4. **Mutual exclusivity** — a `ToolResult` created via `completion()` has `isPlanResponse == false`, `isSkillActivation == false`, etc.
5. **Field preservation** — `ToolResult.completion(..., verifyCommand="cmd")` preserves `verifyCommand`; standard `ToolResult(..., artifacts=listOf("a"))` preserves `artifacts`; `ToolResult(..., diff="...")` preserves `diff`
6. **When-dispatch exhaustiveness** — test function using `when (type)` that returns a unique value per variant, verify all branches reachable

---

## Refactor 2: Streaming Parse — Conditional Re-Parse

### Problem

In `AgentLoop.kt`, the `onChunk` callback calls `AssistantMessageParser.parse()` on the full
accumulated text for every streaming chunk. With ~300 batched chunks per response, this is
O(n²) total work in the response length.

### Approach: Always Parse First Chunk, Then Skip When Safe

The key insight from code review: skipping parse based ONLY on the current chunk's content
is insufficient — if the accumulated text has never been parsed (e.g., first chunk has no `<`),
the cached blocks would be empty and no text would stream to the user.

**Corrected approach:** Parse on every chunk UNTIL the first successful parse produces blocks.
After that, only re-parse when a new `<` appears in the chunk (indicating possible tool call
XML). Also cache `stripPartialTag` output for the no-reparse path.

### Implementation

```kotlin
var cachedBlocks: List<ContentBlock>? = null  // null = never parsed yet
var cachedStrippedText: String = ""

brain.chatStream(..., onChunk = { chunk ->
    val text = chunk.choices.firstOrNull()?.delta?.content ?: return@chatStream
    accumulatedText.append(text)

    val needsParse = cachedBlocks == null       // Never parsed yet — must parse
        || text.contains('<')                    // Possible XML structure change

    val blocks = if (needsParse) {
        AssistantMessageParser.parse(accumulatedText.toString(), toolNames, paramNames)
            .also { cachedBlocks = it }
    } else {
        cachedBlocks!!
    }

    val visibleText = blocks.filterIsInstance<TextContent>().joinToString("\n\n") { it.content }
    val stripped = if (needsParse) {
        AssistantMessageParser.stripPartialTag(visibleText).also { cachedStrippedText = it }
    } else {
        // No structural change — append new text to cached stripped result
        // The stripped text grows by exactly the new chunk's text content
        (cachedStrippedText + text).also { cachedStrippedText = it }
    }

    if (stripped.length > lastPresentedTextLength) {
        val delta = stripped.substring(lastPresentedTextLength)
        onStreamChunk(delta)
        lastPresentedTextLength = stripped.length
    }
})
```

### Safety Analysis

- **First chunk (no `<`):** `cachedBlocks == null` → forces parse → produces `[TextContent("hello")]` → text streams correctly.
- **Subsequent plain text chunks:** `cachedBlocks != null && no '<'` → skip parse → append chunk text to `cachedStrippedText` → delta emitted. Correct.
- **Chunk with `<`:** Forces full re-parse. If `<` starts a tool call, parser produces the correct block structure. If `<` is prose (e.g., "x < y"), re-parse produces the same TextContent blocks — extra work but correct.
- **Split XML tags:** Chunk 1 has `<to` (forces re-parse, partial tag stripped). Chunk 2 has `ol_call>` (no `<`, but cachedBlocks is non-null → skips parse). This is safe because the partial tag is already stripped by chunk 1's parse, and chunk 2's text (`ol_call>`) would be appended to `cachedStrippedText` — but it's XML fragment text, not user text. **Wait — this is wrong.** When chunks 2+ complete the XML tag, we need to re-parse.

**Revised heuristic:** Also re-parse when the accumulated text ends with an incomplete XML-like pattern (i.e., there's an unmatched `<` in the accumulated text). This is exactly what `stripPartialTag` already detects. So:

```kotlin
val needsParse = cachedBlocks == null
    || text.contains('<')
    || text.contains('>')  // Could be closing a previously opened tag
```

Adding `>` to the trigger is conservative (more re-parses) but catches split tags correctly.

### Files Changed

| File | Change |
|------|--------|
| `loop/AgentLoop.kt` | ~15 lines in `onChunk` lambda |

### TDD Test Plan

Tests written BEFORE implementation:

1. **Pure text streaming** — 10 plain text chunks with no `<` or `>`, verify all text streams correctly and parse is called exactly once (first chunk)
2. **Tool call in middle** — 5 text chunks, then chunk with `<tool_call`, then more text. Verify text before tool call streams, tool call is parsed, text after tool call streams
3. **Split XML tag** — chunk 1: `"text <to"`, chunk 2: `"ol_call>"`, chunk 3: `"more text"`. Verify the tool tag is correctly assembled across chunks
4. **`<` in prose** — chunk contains `"x < y"`, verify re-parse fires and output is correct plain text
5. **All-text response** — 300 plain text chunks, verify parse called exactly once (first chunk) and all text emitted
6. **Correctness equivalence** — for any sequence of chunks, the optimized path produces identical `visibleText` output as the unoptimized full-reparse-every-chunk path

---

## Refactor 3: SubagentModels Status Enum

### Problem

`SubagentProgressUpdate.status` and `SubagentStatusItem.status` use raw strings (`"pending"`,
`"running"`, `"completed"`, `"failed"`). A typo like `"complted"` compiles but produces
silent bugs.

### Approach: Reuse Existing `SubagentExecutionStatus` Enum

The codebase already has `enum class SubagentExecutionStatus { PENDING, RUNNING, COMPLETED, FAILED }`
at `SubagentModels.kt` line 17 — the exact 4 values needed. Instead of creating a new enum,
reuse this one. It needs `@Serializable` and `@SerialName` annotations added for JSON
compatibility with `SubagentStatusItem` (which is `@Serializable`).

### Changes to Existing Enum

```kotlin
@Serializable
enum class SubagentExecutionStatus {
    @SerialName("pending") PENDING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
}
```

### Field Changes

```kotlin
// SubagentProgressUpdate (NOT @Serializable — @SerialName only matters for SubagentStatusItem)
data class SubagentProgressUpdate(
    val status: SubagentExecutionStatus? = null,  // Was: String? = null
    ...
)

// SubagentStatusItem (@Serializable — @SerialName matters here)
@Serializable
data class SubagentStatusItem(
    @Volatile var status: SubagentExecutionStatus = SubagentExecutionStatus.PENDING,  // Was: String = "pending"
    ...
)
```

### Call Site Changes

| File | Line(s) | Change |
|------|---------|--------|
| `SubagentRunner.kt` | 91 | `status = "running"` → `SubagentExecutionStatus.RUNNING` |
| `SubagentRunner.kt` | 212 | `if (...) "completed" else "failed"` → `if (...) SubagentExecutionStatus.COMPLETED else SubagentExecutionStatus.FAILED` |
| `SubagentRunner.kt` | 229, 247 | `status = "failed"` → `SubagentExecutionStatus.FAILED` |
| `SpawnAgentTool.kt` | 284 | `status = "running"` → `SubagentExecutionStatus.RUNNING` |
| `SpawnAgentTool.kt` | 290 | `progress.status == "running"` → `progress.status == SubagentExecutionStatus.RUNNING` |
| `SpawnAgentTool.kt` | 339 | `SubagentStatusItem(...)` — `status` field now uses enum default |
| `SpawnAgentTool.kt` | 375 | `status = "running"` → `SubagentExecutionStatus.RUNNING` |
| `SpawnAgentTool.kt` | 377 | `entries[idx].status = "running"` → `SubagentExecutionStatus.RUNNING` |
| `SpawnAgentTool.kt` | 397 | `progress.status == "running"` → `progress.status == SubagentExecutionStatus.RUNNING` |
| `SpawnAgentTool.kt` | 404, 408, 414, 419 | `entries[idx].status = "completed"/"failed"` → enum values |
| `AgentController.kt` | 1102-1124 | `when (update.status)` string comparisons → enum comparisons |

### AgentController Dispatch Change

```kotlin
// Before:
when (update.status) {
    "running" -> dashboard.spawnSubAgent(...)
    "completed" -> dashboard.completeSubAgent(..., isError = false)
    "failed" -> dashboard.completeSubAgent(..., isError = true)
}

// After:
when (update.status) {
    SubagentExecutionStatus.RUNNING -> dashboard.spawnSubAgent(...)
    SubagentExecutionStatus.COMPLETED -> dashboard.completeSubAgent(..., isError = false)
    SubagentExecutionStatus.FAILED -> dashboard.completeSubAgent(..., isError = true)
    SubagentExecutionStatus.PENDING, null -> { /* no-op, initial state */ }
}
```

### TDD Test Plan

Tests written BEFORE implementation:

1. **Serialization round-trip** — `SubagentExecutionStatus.COMPLETED` serializes to `"completed"` (lowercase via `@SerialName`) and deserializes back
2. **SubagentProgressUpdate construction** — construct with each enum value, verify field access; construct with null, verify null
3. **SubagentStatusItem volatile mutation** — set status from multiple coroutines, verify final state is always a valid enum value
4. **AgentController dispatch coverage** — each enum value maps to the correct bridge call (RUNNING → spawnSubAgent, COMPLETED → completeSubAgent(isError=false), FAILED → completeSubAgent(isError=true), PENDING/null → no-op)
5. **Exhaustive when** — verify compiler enforces all enum values handled in dispatch

---

## Implementation Order

1. **SubagentModels enum** (smallest, ~14 call sites, establishes TDD pattern)
2. **Streaming parse optimization** (self-contained, single file, ~15 lines changed)
3. **ToolResult sealed type** (largest, ~8 files changed, benefits from TDD discipline established in 1 and 2)

Each refactor is an independent commit. Tests are written first, then implementation to pass them.

## Testing Strategy

All refactors use the same TDD cycle:

1. **Write failing tests** that define the new contract (enum values, factory methods, parse behavior)
2. **Run tests** — confirm they fail for the right reason (type doesn't exist yet, method missing, etc.)
3. **Implement** the minimum code to pass the tests
4. **Run full `./gradlew :agent:test`** after each refactor to catch regressions
5. **Verify compilation** of the full project (`./gradlew :agent:compileKotlin`)

No changes to the webview TypeScript code. No changes to the JCEF bridge. No changes to the
system prompt or tool schemas.
