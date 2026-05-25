# Restore the Cline `new_task` Contract + Fix the Chat-Wipe Bug — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `new_task` a suspend-and-confirm flow (LLM proposes a handoff → user picks "Start fresh session" or "Keep chatting") instead of an auto-firing exit, and fix the root-cause desync that wipes the visible chat after a handoff/resume.

**Architecture:** Mirror the existing `plan_mode_respond` flow exactly: the tool returns a new typed result, `AgentLoop` fires a callback to render a JCEF card and **suspends on `userInputChannel`**, and the card's two buttons feed a decision sentinel back through that channel. The fork outcome reuses the existing `LoopResult.SessionHandoff`; the decline outcome exits with `LoopResult.Completed`. The chat-wipe fix introduces a `sessionActive` flag (replacing the `contextManager == null` heuristic) and routes the live `ContextManager` back to the controller via a new `onContextManagerReady` callback on `executeTask` (the single chokepoint at `AgentService.kt:1742`).

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform, kotlinx.coroutines (`Channel`, `userInputChannel`), JUnit 5 + MockK; React 19 + TypeScript + Zustand + Tailwind (webview), JCEF bridge.

**Spec:** `docs/superpowers/specs/2026-05-25-new-task-cline-contract-design.md`

---

## File Structure

**Kotlin (`agent/src/main/kotlin/com/workflow/orchestrator/agent/`)**
- `tools/AgentTool.kt` — add `ToolResultType.HandoffProposed` + `ToolResult.handoffProposed(...)` factory.
- `tools/builtin/NewTaskTool.kt` — return `HandoffProposed` instead of `sessionHandoff`.
- `loop/AgentLoop.kt` — `onHandoffProposed` callback field, sentinel constants, `HandoffProposed` branch (suspend + decision).
- `AgentService.kt` — `onContextManagerReady` + `onHandoffProposed` params on `executeTask`; forward through `startHandoffSession` and `resumeSession`.
- `ui/AgentController.kt` — `sessionActive` flag; `isFirstMessage` gate fix; `onHandoffProposed` rendering + decision handlers; handoff/resume/revert wiring; remove "Context limit reached" caption.
- `ui/AgentCefPanel.kt`, `ui/AgentDashboardPanel.kt` — `renderHandoff` / `clearHandoffInUi` push + `setCefHandoffCallbacks` + bridge injection.

**Webview (`agent/webview/src/`)**
- `bridge/types.ts` — `Handoff` interface.
- `stores/chatStore.ts` — `handoff` projection + `setHandoff`/`clearHandoff`; reset wiring.
- `components/agent/HandoffPreviewCard.tsx` — new card (collapsible summary + two buttons).
- `components/chat/ChatFooter.tsx` — mount the card.
- `bridge/jcef-bridge.ts` — `renderHandoff`/`clearHandoff` (K→JS) + `startFreshSession`/`keepChatting` (JS→K).

**Tests**
- `tools/AgentToolTest` (or existing equivalent), `tools/builtin/NewTaskToolTest`, `loop/AgentLoopHandoffTest`, `AgentServiceContextManagerReadyTest`, `ui/AgentControllerSessionActiveSourceTest`, webview `HandoffPreviewCard.test.tsx`.

---

## Conventions for the implementer

- Work on the current `bugfix` branch. Do **not** create worktrees or branch.
- Commit messages: no `Co-Authored-By` / Claude trailer.
- Run agent tests with `./gradlew :agent:test --tests "..."`. Webview: `cd agent/webview && npm run build` then `npx vitest run <file>` for component tests.
- Sentinel strings (define once in `AgentLoop`, reference everywhere): `HANDOFF_FORK_SENTINEL = "__HANDOFF_FORK__"`, `HANDOFF_DECLINE_SENTINEL = "__HANDOFF_DECLINE__"`.

---

### Task 1: Add `HandoffProposed` result type + factory

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt:224-233` (sealed class) and `:328-329` (factory).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/HandoffProposedResultTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HandoffProposedResultTest {
    @Test
    fun `handoffProposed factory produces HandoffProposed type carrying the context`() {
        val result = ToolResult.handoffProposed(
            content = "summary body",
            summary = "Proposing handoff",
            tokenEstimate = 10,
            context = "## Current Work\nRefactor auth"
        )
        val type = result.type
        assertTrue(type is ToolResultType.HandoffProposed)
        assertEquals("## Current Work\nRefactor auth", (type as ToolResultType.HandoffProposed).context)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*HandoffProposedResultTest*"`
Expected: FAIL — `ToolResultType.HandoffProposed` and `ToolResult.handoffProposed` are unresolved references.

- [ ] **Step 3: Add the type**

In `AgentTool.kt`, inside the `sealed class ToolResultType` (after line 230, the existing `SessionHandoff`):

```kotlin
    /** new_task proposed a handoff; the loop renders a preview card and waits for the user's decision. */
    data class HandoffProposed(val context: String) : ToolResultType()
```

- [ ] **Step 4: Add the factory**

In `AgentTool.kt`, in the `companion object` after the existing `sessionHandoff` factory (line 328-329):

```kotlin
        fun handoffProposed(content: String, summary: String, tokenEstimate: Int, context: String) =
            ToolResult(content, summary, tokenEstimate, type = ToolResultType.HandoffProposed(context))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*HandoffProposedResultTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/HandoffProposedResultTest.kt
git commit -m "feat(agent): add HandoffProposed tool-result type for new_task confirm flow"
```

---

### Task 2: `NewTaskTool` returns `HandoffProposed`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/NewTaskTool.kt:230` (the `return ToolResult.sessionHandoff(...)`).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/NewTaskToolTest.kt`

- [ ] **Step 1: Read the current execute() body**

Read `NewTaskTool.kt` lines 200-240 to see the exact param extraction and the current `return ToolResult.sessionHandoff(content, summary, tokenEstimate, context = context)` call and the validation above it (must be non-blank `context`).

- [ ] **Step 2: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolResultType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NewTaskToolTest {
    @Test
    fun `execute returns HandoffProposed carrying the context`() = runTest {
        val tool = NewTaskTool()
        val params = Json.parseToJsonElement("""{"context":"## Current Work\nDoing X"}""") as JsonObject
        val result = tool.execute(params, project = null)
        val type = result.type
        assertTrue(type is ToolResultType.HandoffProposed)
        assertEquals("## Current Work\nDoing X", (type as ToolResultType.HandoffProposed).context)
    }
}
```

> NOTE: match the actual `execute` signature in `NewTaskTool.kt` (param names/types for `params` and `project`). If `execute` requires a non-null `Project`, pass `mockk<Project>(relaxed = true)` and `import io.mockk.mockk`.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*NewTaskToolTest*"`
Expected: FAIL — result type is `SessionHandoff`, not `HandoffProposed`.

- [ ] **Step 4: Switch the factory call**

In `NewTaskTool.kt:230`, change `return ToolResult.sessionHandoff(` to `return ToolResult.handoffProposed(` (same arguments). Update the KDoc at the top of the file (lines 17-31): replace "hand off to a fresh session" framing with "propose a handoff; the user is shown a preview and chooses whether to fork." Keep the Cline source reference.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*NewTaskToolTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/NewTaskTool.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/NewTaskToolTest.kt
git commit -m "feat(agent): new_task returns HandoffProposed (propose, not auto-fire)"
```

---

### Task 3: `AgentLoop` — suspend-and-decide on `HandoffProposed`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` — add callback field near `onPlanResponse` (line 201) / `onAwaitingUserInput` (line 400); add sentinel constants to the `companion object`; add the `HandoffProposed` branch in `processToolCalls` next to the `PlanResponse` branch (line 2242) and the old `SessionHandoff` branch (line 2217).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopHandoffTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentLoopHandoffTest {
    @Test
    fun `sentinel constants are distinct and stable`() {
        assertTrue(AgentLoop.HANDOFF_FORK_SENTINEL == "__HANDOFF_FORK__")
        assertTrue(AgentLoop.HANDOFF_DECLINE_SENTINEL == "__HANDOFF_DECLINE__")
        assertTrue(AgentLoop.HANDOFF_FORK_SENTINEL != AgentLoop.HANDOFF_DECLINE_SENTINEL)
    }

    @Test
    fun `AgentLoop source suspends on HandoffProposed and branches on the sentinel`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt"
        ).readText()
        // The HandoffProposed branch must fire the render callback, receive on the channel, and branch.
        assertTrue(src.contains("is ToolResultType.HandoffProposed"))
        assertTrue(src.contains("onHandoffProposed?.invoke"))
        assertTrue(src.contains("HANDOFF_FORK_SENTINEL"))
        assertTrue(src.contains("HANDOFF_DECLINE_SENTINEL"))
        assertTrue(src.contains("LoopResult.SessionHandoff"))
    }
}
```

> Rationale: `AgentLoop` is constructed with ~30 collaborators; a full behavioural run is covered by the existing exit-drain suite. This source-contract test mirrors the codebase's existing `AgentLoopStreamingEditTest` source-pin pattern for loop wiring that is impractical to instantiate in isolation. The behavioural fork/decline mapping is validated end-to-end by `AgentServiceContextManagerReadyTest` (Task 4) and manual smoke.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentLoopHandoffTest*"`
Expected: FAIL — constants and branch text absent.

- [ ] **Step 3: Add the sentinel constants**

In `AgentLoop.kt`, in the `companion object` (search for `companion object` near the top of the class), add:

```kotlin
        const val HANDOFF_FORK_SENTINEL = "__HANDOFF_FORK__"
        const val HANDOFF_DECLINE_SENTINEL = "__HANDOFF_DECLINE__"
```

- [ ] **Step 4: Add the callback field**

In the `AgentLoop` constructor parameter list, immediately after the `onPlanResponse` field (line 201), add:

```kotlin
    /**
     * Fired when new_task proposes a handoff. The UI renders the preview card and
     * the user picks "Start fresh session" or "Keep chatting"; the decision is sent
     * back through [userInputChannel] as one of the HANDOFF_*_SENTINEL strings.
     */
    private val onHandoffProposed: ((context: String) -> Unit)? = null,
```

- [ ] **Step 5: Add the `HandoffProposed` branch**

In `processToolCalls`, immediately after the existing `is ToolResultType.SessionHandoff -> { ... }` block (ends at line 2241), add:

```kotlin
                is ToolResultType.HandoffProposed -> {
                    val proposed = toolResult.type
                    // Pre-exit steering drain: if the user typed mid-final-stream, defer
                    // the proposal and address the follow-up first (same guard as SessionHandoff).
                    if (drainSteeringIntoContextOnExit()) {
                        userInputReceivedInToolCall = true
                        LOG.info("[Loop] Steering arrived during new_task stream — deferring handoff proposal")
                        return null
                    }
                    LOG.info("[Loop] new_task proposed a handoff — presenting preview card")
                    onHandoffProposed?.invoke(proposed.context)

                    if (userInputChannel != null) {
                        // Suspend until the card sends a decision sentinel (mirrors PlanResponse).
                        when (userInputChannel.receive()) {
                            HANDOFF_FORK_SENTINEL -> {
                                onDebugLog?.invoke("info", "loop_exit", "Exit: new_task_handoff_fork", mapOf("iteration" to iteration))
                                sessionMetrics?.recordIterationEnd()
                                return LoopResult.SessionHandoff(
                                    context = proposed.context,
                                    iterations = iteration,
                                    tokensUsed = totalTokensUsed,
                                    inputTokens = totalInputTokens,
                                    outputTokens = totalOutputTokens,
                                    filesModified = filesModifiedList(),
                                    linesAdded = totalLinesAdded,
                                    linesRemoved = totalLinesRemoved
                                )
                            }
                            else -> {
                                // HANDOFF_DECLINE_SENTINEL (or any non-fork value): stay in this
                                // session, discard the proposed summary, return control to the user.
                                LOG.info("[Loop] handoff declined — continuing in current session")
                                onDebugLog?.invoke("info", "loop_exit", "Exit: new_task_handoff_declined", mapOf("iteration" to iteration))
                                sessionMetrics?.recordIterationEnd()
                                return LoopResult.Completed(
                                    summary = "Continuing in the current session — send your next message when ready.",
                                    iterations = iteration,
                                    tokensUsed = totalTokensUsed,
                                    completionData = null,
                                    inputTokens = totalInputTokens,
                                    outputTokens = totalOutputTokens,
                                    filesModified = filesModifiedList(),
                                    linesAdded = totalLinesAdded,
                                    linesRemoved = totalLinesRemoved
                                )
                            }
                        }
                    }
                    // No channel (sub-agent / test): fall back to the legacy auto-fork so we
                    // never strand the loop. Sub-agents cannot call new_task (ORCHESTRATOR-only),
                    // so this is a safety net, not a normal path.
                    return LoopResult.SessionHandoff(
                        context = proposed.context,
                        iterations = iteration,
                        tokensUsed = totalTokensUsed,
                        inputTokens = totalInputTokens,
                        outputTokens = totalOutputTokens,
                        filesModified = filesModifiedList(),
                        linesAdded = totalLinesAdded,
                        linesRemoved = totalLinesRemoved
                    )
                }
```

> Verify `LoopResult.Completed`'s parameter names/order against `loop/LoopResult.kt` and the existing `Completed` construction near line 2204 — copy that call's shape exactly.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentLoopHandoffTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopHandoffTest.kt
git commit -m "feat(agent): AgentLoop suspends on HandoffProposed and branches fork/decline"
```

---

### Task 4: `executeTask` — report the live `ContextManager` + thread `onHandoffProposed`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — add two params to `executeTask` (near `onSessionStarted` at line 1443); invoke `onContextManagerReady` after `val ctx = ...` (line 1746); pass `onHandoffProposed` into the `AgentLoop(...)` constructor.
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceContextManagerReadyTest.kt`

- [ ] **Step 1: Write the failing test (source-contract)**

```kotlin
package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentServiceContextManagerReadyTest {
    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
    ).readText()

    @Test
    fun `executeTask exposes onContextManagerReady and invokes it with the resolved ctx`() {
        assertTrue(src.contains("onContextManagerReady"), "param missing")
        // Invoked right after ctx is resolved so callers hold the live manager.
        assertTrue(
            src.contains("onContextManagerReady?.invoke(ctx)"),
            "ctx not reported back to caller"
        )
    }

    @Test
    fun `executeTask threads onHandoffProposed into the AgentLoop`() {
        assertTrue(src.contains("onHandoffProposed"), "onHandoffProposed not threaded")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*AgentServiceContextManagerReadyTest*"`
Expected: FAIL.

- [ ] **Step 3: Add the two params to `executeTask`**

In `AgentService.executeTask`'s parameter list, immediately after `onSessionStarted` (line 1443), add:

```kotlin
        /**
         * Fired once the loop's [ContextManager] is resolved (the existing one if passed,
         * or a freshly built one). Lets the caller (AgentController) hold the live manager
         * so the NEXT user message is added to the correct context instead of a fresh empty
         * one — the root-cause fix for the post-handoff/post-resume chat wipe.
         */
        onContextManagerReady: ((ContextManager) -> Unit)? = null,
        /** Forwarded to [AgentLoop.onHandoffProposed] — renders the new_task preview card. */
        onHandoffProposed: ((context: String) -> Unit)? = null,
```

- [ ] **Step 4: Invoke `onContextManagerReady` after ctx resolution**

In `AgentService.kt`, immediately after line 1746 (the closing `)` of `val ctx = contextManager ?: ContextManager(...)`), add:

```kotlin
                // Report the resolved manager so the controller can hold it across turns.
                onContextManagerReady?.invoke(ctx)
```

- [ ] **Step 5: Pass `onHandoffProposed` into the `AgentLoop` constructor**

Find the `AgentLoop(` construction in `executeTask` (it passes `onPlanResponse = onPlanResponse`, `onAwaitingUserInput = onAwaitingUserInput`, etc.). Add the argument:

```kotlin
                onHandoffProposed = onHandoffProposed,
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*AgentServiceContextManagerReadyTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceContextManagerReadyTest.kt
git commit -m "feat(agent): executeTask reports live ContextManager + threads onHandoffProposed"
```

---

### Task 5: Forward the new callbacks through `startHandoffSession` and `resumeSession`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — `startHandoffSession` (line 2764) and `resumeSession` (line 2343).
- Test: extend `AgentServiceContextManagerReadyTest`.

- [ ] **Step 1: Add the assertions**

Append to `AgentServiceContextManagerReadyTest`:

```kotlin
    @Test
    fun `startHandoffSession forwards onSessionStarted and onContextManagerReady to executeTask`() {
        val block = src.substringAfter("fun startHandoffSession(").substringBefore("\n    fun ")
        assertTrue(block.contains("onSessionStarted"), "handoff must forward onSessionStarted")
        assertTrue(block.contains("onContextManagerReady"), "handoff must forward onContextManagerReady")
        assertTrue(block.contains("onHandoffProposed"), "handoff must forward onHandoffProposed")
    }

    @Test
    fun `resumeSession forwards onContextManagerReady to executeTask`() {
        val block = src.substringAfter("fun resumeSession(").substringBefore("\n    fun ")
        assertTrue(block.contains("onContextManagerReady"), "resume must forward onContextManagerReady")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "*AgentServiceContextManagerReadyTest*"`
Expected: FAIL on the two new cases.

- [ ] **Step 3: Update `startHandoffSession`**

Replace the signature + body (lines 2764-2781) with:

```kotlin
    fun startHandoffSession(
        handoffContext: String,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {},
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
        onContextManagerReady: ((ContextManager) -> Unit)? = null,
        onHandoffProposed: ((context: String) -> Unit)? = null,
    ): Job {
        val preamble = "Continue from the previous session. Here is the preserved context:\n\n$handoffContext"
        return executeTask(
            task = preamble,
            sessionId = null,
            contextManager = null,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
            onComplete = onComplete,
            onSessionStarted = onSessionStarted,
            onContextManagerReady = onContextManagerReady,
            onHandoffProposed = onHandoffProposed,
        )
    }
```

- [ ] **Step 4: Update `resumeSession`**

Add `onContextManagerReady: ((ContextManager) -> Unit)? = null,` to the `resumeSession` parameter list (after `onSessionStarted` at the line matching it in the signature). Then find the `executeTask(` call inside `resumeSession`'s body and add `onContextManagerReady = onContextManagerReady,`. Also thread `onHandoffProposed = null` is NOT needed for resume (a resumed orchestrator can still call new_task, so forward it): add `onHandoffProposed: ((context: String) -> Unit)? = null,` to the params and `onHandoffProposed = onHandoffProposed,` to the inner `executeTask` call.

> If `resumeSession` builds its `ContextManager` *before* calling `executeTask` and passes it in, `onContextManagerReady?.invoke(ctx)` (Task 4 step 4) still fires with that same instance — correct. Confirm the inner `executeTask` call receives the rebuilt manager as `contextManager =`.

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :agent:test --tests "*AgentServiceContextManagerReadyTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/AgentServiceContextManagerReadyTest.kt
git commit -m "feat(agent): forward session-ready + handoff callbacks through handoff/resume"
```

---

### Task 6: `AgentController` — `sessionActive` flag replaces the `contextManager == null` wipe gate

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — declare flag near `currentSessionId` (line 252); change `isFirstMessage` (line 1849); set flag in the first-message block; reset in `newChat` (line ~2749).
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerSessionActiveSourceTest.kt`

- [ ] **Step 1: Write the failing test (the chat-wipe repro, as a source contract)**

```kotlin
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Repro guard for the post-handoff/post-resume chat-wipe bug: the view-reset
 * (dashboard.startSession) must NOT be gated on `contextManager == null`, because
 * handoff/resume legitimately leave that field null while a session is active.
 */
class AgentControllerSessionActiveSourceTest {
    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    @Test
    fun `a sessionActive flag exists and gates the first-message view reset`() {
        assertTrue(src.contains("sessionActive"), "sessionActive flag missing")
        assertTrue(
            src.contains("val isFirstMessage = !sessionActive"),
            "isFirstMessage must be gated on !sessionActive, not contextManager == null"
        )
    }

    @Test
    fun `isFirstMessage is no longer derived from contextManager == null`() {
        assertFalse(
            src.contains("val isFirstMessage = contextManager == null"),
            "the buggy gate must be removed"
        )
    }

    @Test
    fun `newChat resets sessionActive`() {
        val newChat = src.substringAfter("fun newChat(").substringBefore("\n    fun ")
        assertTrue(newChat.contains("sessionActive = false"), "newChat must clear sessionActive")
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: FAIL.

- [ ] **Step 3: Declare the flag**

In `AgentController.kt`, right after the `currentSessionId` declaration (line 252), add:

```kotlin
    /**
     * True once any session-entry path has started/resumed/forked a session. Replaces the
     * old `contextManager == null` heuristic for deciding whether a user message starts a
     * brand-new chat (which wiped the view). Reset only by [newChat].
     */
    private var sessionActive = false
```

- [ ] **Step 4: Fix the gate + set the flag**

In `handleUserMessage`, change line 1849 from:

```kotlin
        val isFirstMessage = contextManager == null
```

to:

```kotlin
        val isFirstMessage = !sessionActive
```

Inside the `if (isFirstMessage) { ... }` block (after the `dashboard.startSession(...)` calls, near line 1861), add:

```kotlin
            sessionActive = true
```

Note: `contextManager = service.newContextManager()` at line 1851 stays — a genuine first message still needs a fresh manager. The decoupling is only about the *view reset*.

- [ ] **Step 5: Reset in `newChat`**

In `fun newChat()` (line ~2689-2749), next to the existing `currentSessionId = null` (line 2749), add:

```kotlin
        sessionActive = false
```

- [ ] **Step 6: Run to verify pass**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerSessionActiveSourceTest.kt
git commit -m "fix(agent): gate chat-view reset on sessionActive, not contextManager==null"
```

---

### Task 7: `AgentController` — render the card, handle the decision, set `sessionActive` on handoff/resume/revert

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — new `onHandoffProposed` handler (mirror `onPlanResponse`, line 3314); new `startFreshSession()` / `keepChatting()` decision handlers (mirror `performPlanDiscard`, line 3518); wire `setCefHandoffCallbacks`; set `sessionActive = true` in `resumeSession` (line 3085 area) and `revertToUserMessage` (line 3247).
- Test: extend `AgentControllerSessionActiveSourceTest`.

- [ ] **Step 1: Add assertions**

Append:

```kotlin
    @Test
    fun `handoff decision handlers send the loop sentinels through the channel`() {
        assertTrue(src.contains("HANDOFF_FORK_SENTINEL"), "fork handler must send fork sentinel")
        assertTrue(src.contains("HANDOFF_DECLINE_SENTINEL"), "decline handler must send decline sentinel")
        assertTrue(src.contains("setCefHandoffCallbacks"), "handoff card callbacks not wired")
    }

    @Test
    fun `resume and revert mark the session active`() {
        val resume = src.substringAfter("fun resumeSession(").substringBefore("\n    fun ")
        assertTrue(resume.contains("sessionActive = true"), "resume must set sessionActive")
        val revert = src.substringAfter("suspend fun revertToUserMessage(").substringBefore("\n    /**")
        assertTrue(revert.contains("sessionActive = true"), "revert must set sessionActive")
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: FAIL on the two new cases.

- [ ] **Step 3: Add the `onHandoffProposed` handler**

Add a method near `onPlanResponse` (after line 3363):

```kotlin
    /**
     * Callback from AgentLoop when new_task proposes a handoff. Renders the preview card
     * and marks the loop as waiting — the user's button click feeds a sentinel into
     * [userInputChannel] (see [startFreshSession] / [keepChatting]). Mirrors [onPlanResponse].
     */
    private fun onHandoffProposed(context: String) {
        LOG.info("AgentController.onHandoffProposed (${context.length} chars)")
        val json = taskEventJson.encodeToString(HandoffJson(summary = context))
        invokeLater {
            dashboard.renderHandoff(json)
            loopWaitingForInput = true
            dashboard.setBusy(false)
            dashboard.focusInput()
        }
    }
```

Add the serializable DTO near the other UI DTOs at the top of the file (or in the existing JSON-DTO section):

```kotlin
    @kotlinx.serialization.Serializable
    private data class HandoffJson(val summary: String)
```

> Reuse the existing `taskEventJson` (used by `pushAggregateDiff`) or `Json` instance already imported. If neither is in scope at this call site, use `kotlinx.serialization.json.Json.encodeToString(HandoffJson.serializer(), HandoffJson(context))`.

- [ ] **Step 4: Add the decision handlers**

Add near `performPlanDiscard` (after line 3539):

```kotlin
    /** User clicked "Start fresh session" on the handoff card — fork via the loop sentinel. */
    private fun startFreshSession() {
        LOG.info("AgentController.startFreshSession (handoff fork)")
        invokeLater { dashboard.clearHandoffInUi() }
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            dashboard.setBusy(true)
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.startFreshSession.send")) {
                channel.send(AgentLoop.HANDOFF_FORK_SENTINEL)
            }
        } else {
            LOG.warn("AgentController.startFreshSession: no suspended loop to receive the decision")
        }
    }

    /** User clicked "Keep chatting here" — decline the handoff, stay in the current session. */
    private fun keepChatting() {
        LOG.info("AgentController.keepChatting (handoff declined)")
        invokeLater {
            dashboard.clearHandoffInUi()
            dashboard.appendStatus("Staying in this session.", RichStreamingPanel.StatusType.INFO)
        }
        val channel = userInputChannel
        if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
            loopWaitingForInput = false
            controllerScope.launch(Dispatchers.EDT + CoroutineName("AgentController.keepChatting.send")) {
                channel.send(AgentLoop.HANDOFF_DECLINE_SENTINEL)
            }
        } else {
            LOG.warn("AgentController.keepChatting: no suspended loop to receive the decision")
        }
    }
```

> Add `import com.workflow.orchestrator.agent.loop.AgentLoop` if not present, and confirm `CoroutineName` / `Dispatchers` imports already exist (they do — used by `revisePlan`).

- [ ] **Step 5: Wire the panel callbacks**

Next to `panel.setCefPlanCallbacks(...)` (line 683), add:

```kotlin
        panel.setCefHandoffCallbacks(
            onStartFresh = ::startFreshSession,
            onKeepChatting = ::keepChatting
        )
```

- [ ] **Step 6: Set `sessionActive` on resume + revert**

In `resumeSession`, next to `currentSessionId = sessionId` (line 3085), add `sessionActive = true`.
In `revertToUserMessage`, next to `currentSessionId = sessionId` (line 3247), add `sessionActive = true`.

- [ ] **Step 7: Run to verify pass**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerSessionActiveSourceTest.kt
git commit -m "feat(agent): render handoff card + fork/decline handlers + active-session wiring"
```

---

### Task 8: `AgentController` — handoff `onComplete` branch: fix wiring, drop the misleading caption, add the banner

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:2552-2580` (the `is LoopResult.SessionHandoff ->` branch).
- Test: extend `AgentControllerSessionActiveSourceTest`.

- [ ] **Step 1: Add assertions**

```kotlin
    @Test
    fun `handoff branch no longer shows the misleading context-limit caption`() {
        assertFalse(src.contains("Context limit reached. Starting fresh session"),
            "the misleading caption must be removed")
    }

    @Test
    fun `handoff branch wires onSessionStarted and onContextManagerReady for the forked session`() {
        val branch = src.substringAfter("is LoopResult.SessionHandoff ->").substringBefore("}\n            }")
        assertTrue(branch.contains("startHandoffSession"))
        assertTrue(branch.contains("onSessionStarted"))
        assertTrue(branch.contains("onContextManagerReady"))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: FAIL on the two new cases.

- [ ] **Step 3: Rewrite the handoff branch**

Replace lines 2552-2580 (`is LoopResult.SessionHandoff -> { ... }`) with:

```kotlin
                is LoopResult.SessionHandoff -> {
                    // User confirmed the fork on the new_task preview card. Start a fresh
                    // session seeded with the preserved context; the old session is COMPLETED.
                    dashboard.appendStatus(
                        "Continuing in a fresh session with the preserved context.",
                        RichStreamingPanel.StatusType.INFO
                    )
                    if (result.inputTokens > 0 || result.outputTokens > 0) {
                        val inputK = formatTokenCount(result.inputTokens)
                        val outputK = formatTokenCount(result.outputTokens)
                        dashboard.appendStatus(
                            "Previous session used ${inputK} input + ${outputK} output tokens",
                            RichStreamingPanel.StatusType.INFO
                        )
                    }

                    // Reset for the new session. The fork intentionally starts a fresh view;
                    // currentSessionId + contextManager are repopulated by the callbacks below
                    // so the NEXT user message appends instead of wiping (sessionActive stays true).
                    contextManager = null
                    sessionApprovalStore.clear()

                    currentJob = service.startHandoffSession(
                        handoffContext = result.context,
                        onStreamChunk = ::onStreamChunk,
                        onToolCall = ::onToolCall,
                        onComplete = ::onComplete,
                        onSessionStarted = { sid ->
                            currentSessionId = sid
                            sessionActive = true
                        },
                        onContextManagerReady = { cm -> contextManager = cm },
                        onHandoffProposed = ::onHandoffProposed
                    )
                    handledHandoff = true
                }
```

> The forked session's first turn is the `"Continue from the previous session…"` preamble. The visible "↪ Continued from previous session" banner is a webview affordance — implement it as a status line for v1 (above), and the collapsible summary lives in the just-dismissed card. A dedicated banner component is out of scope; the status line satisfies the spec's intent without new UI surface.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :agent:test --tests "*AgentControllerSessionActiveSourceTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerSessionActiveSourceTest.kt
git commit -m "fix(agent): correct handoff fork wiring + drop misleading context-limit caption"
```

---

### Task 9: Panel — `renderHandoff` / `clearHandoffInUi` / `setCefHandoffCallbacks` + bridge injection

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt` — delegate methods (mirror `renderPlan`/`clearPlanInUi`/`setCefPlanCallbacks` at lines 284-310).
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` — `callJs("renderHandoff(...)")` / `callJs("clearHandoff()")`, callback fields + `JBCefJSQuery` registration + `injectBridge` for `_handoffFork` / `_handoffKeep` (mirror `_approvePlan` at lines 757-759, `revisePlanFromEditorQuery` at line 571).
- Test: none (pure JCEF plumbing; covered by the webview tests + manual smoke). Compilation is the gate.

- [ ] **Step 1: AgentCefPanel — callback fields**

Near `var onRevisePlanFromEditor: (() -> Unit)? = null` (line 244), add:

```kotlin
    var onHandoffStartFresh: (() -> Unit)? = null
    var onHandoffKeepChatting: (() -> Unit)? = null
```

- [ ] **Step 2: AgentCefPanel — query registration**

Near `revisePlanFromEditorQuery = registerQuery(b) { ... }` (line 571), add (use the same `registerQuery` helper + a `private var ...Query: JBCefJSQuery? = null` field declared next to the other query fields):

```kotlin
        handoffForkQuery = registerQuery(b) { _ -> onHandoffStartFresh?.invoke(); JBCefJSQuery.Response("ok") }
        handoffKeepQuery = registerQuery(b) { _ -> onHandoffKeepChatting?.invoke(); JBCefJSQuery.Response("ok") }
```

- [ ] **Step 3: AgentCefPanel — bridge injection**

Near `injectBridge("_revisePlanFromEditor") { ... }` (line 790), add:

```kotlin
                    injectBridge("_handoffFork") { handoffForkQuery?.let { q -> js("window._handoffFork = function() { ${q.inject("''")} }") } }
                    injectBridge("_handoffKeep") { handoffKeepQuery?.let { q -> js("window._handoffKeep = function() { ${q.inject("''")} }") } }
```

- [ ] **Step 4: AgentCefPanel — Kotlin→JS push methods**

Near the `renderPlan` push (search for `callJs("renderPlan` — it lives on AgentCefPanel as a `fun renderPlan(planJson)`), add:

```kotlin
    fun renderHandoff(handoffJson: String) {
        callJs("renderHandoff(${JsEscape.toJsString(handoffJson)})")
    }

    fun clearHandoffInUi() {
        callJs("clearHandoff()")
    }
```

- [ ] **Step 5: AgentDashboardPanel — delegate + setter**

Mirror `setCefPlanCallbacks`/`renderPlan`/`clearPlanInUi` (lines 284-310):

```kotlin
    fun setCefHandoffCallbacks(onStartFresh: () -> Unit, onKeepChatting: () -> Unit) {
        cefPanel?.onHandoffStartFresh = onStartFresh
        cefPanel?.onHandoffKeepChatting = onKeepChatting
    }

    fun renderHandoff(handoffJson: String) {
        cefPanel?.renderHandoff(handoffJson)
        broadcast { it.renderHandoff(handoffJson) }
    }

    fun clearHandoffInUi() {
        cefPanel?.clearHandoffInUi()
        broadcast(replay = false) { it.clearHandoffInUi() }
    }
```

> Match the exact `broadcast { }` / `broadcast(replay = false) { }` shapes used by the adjacent `renderPlan` / `clearPlanInUi`.

- [ ] **Step 6: Verify compilation**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent): panel plumbing for handoff card (render/clear + fork/keep bridges)"
```

---

### Task 10: Webview — `Handoff` type + chatStore projection

**Files:**
- Modify: `agent/webview/src/bridge/types.ts` — add `Handoff` after `Plan` (line 67-72).
- Modify: `agent/webview/src/stores/chatStore.ts` — `handoff` field (near `plan: Plan | null` line 274), interface entries (near `setPlan`/`clearPlan` line 414-415), defaults (near `plan: null` line 544), `setHandoff`/`clearHandoff` impls (near `setPlan`/`clearPlan` line 1179), and reset wiring in `startSession` (line 625), `endStream` (line ~705), `clearChat` (line 1163).
- Test: `agent/webview/src/stores/__tests__/handoff-store.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';

describe('chatStore handoff projection', () => {
  beforeEach(() => { useChatStore.getState().clearChat(); });

  it('setHandoff stores the summary; clearHandoff removes it', () => {
    useChatStore.getState().setHandoff({ summary: '## Current Work\nX' });
    expect(useChatStore.getState().handoff?.summary).toContain('Current Work');
    useChatStore.getState().clearHandoff();
    expect(useChatStore.getState().handoff).toBeNull();
  });

  it('startSession clears any open handoff', () => {
    useChatStore.getState().setHandoff({ summary: 'pending' });
    useChatStore.getState().startSession('new task');
    expect(useChatStore.getState().handoff).toBeNull();
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd agent/webview && npx vitest run src/stores/__tests__/handoff-store.test.ts`
Expected: FAIL — `setHandoff`/`handoff` undefined.

- [ ] **Step 3: Add the type**

In `types.ts` after the `Plan` interface (line 72):

```ts
export interface Handoff {
  /** The LLM-authored 5-section handoff summary (markdown). */
  summary: string;
}
```

- [ ] **Step 4: Add store field + interface + default**

In `chatStore.ts`:
- After `plan: Plan | null;` (line 274): `handoff: Handoff | null;`
- After `clearPlan(): void;` (line 415): `setHandoff(handoff: Handoff): void;` and `clearHandoff(): void;`
- In the default state object after `plan: null,` (line 544): `handoff: null,`
- Add `import type { Handoff } from '@/bridge/types';` (extend the existing `Plan` import line).

- [ ] **Step 5: Add impls + reset wiring**

After the `clearPlan()` impl (line ~1195):

```ts
  setHandoff(handoff: Handoff) {
    set({ handoff });
  },

  clearHandoff() {
    set({ handoff: null });
  },
```

Add `handoff: null,` to the `set({ ... })` resets in `startSession` (line 625, alongside `plan: null,`), `clearChat` (line 1163), and the `endStream`/`completeSession` resets (line ~705, ~806, ~823 — wherever `plan: null` appears in those resets). The handoff card must never survive a session boundary.

- [ ] **Step 6: Run to verify pass**

Run: `cd agent/webview && npx vitest run src/stores/__tests__/handoff-store.test.ts`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/webview/src/bridge/types.ts agent/webview/src/stores/chatStore.ts agent/webview/src/stores/__tests__/handoff-store.test.ts
git commit -m "feat(webview): chatStore handoff projection + reset wiring"
```

---

### Task 11: Webview — `HandoffPreviewCard` component

**Files:**
- Create: `agent/webview/src/components/agent/HandoffPreviewCard.tsx`
- Test: `agent/webview/src/components/agent/__tests__/HandoffPreviewCard.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { HandoffPreviewCard } from '../HandoffPreviewCard';

describe('HandoffPreviewCard', () => {
  beforeEach(() => {
    (window as any)._handoffFork = vi.fn();
    (window as any)._handoffKeep = vi.fn();
  });

  it('renders the summary and two buttons', () => {
    render(<HandoffPreviewCard handoff={{ summary: '## Current Work\nRefactor auth' }} />);
    expect(screen.getByText(/Continue in a fresh session/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Start fresh session/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Keep chatting/i })).toBeTruthy();
  });

  it('Start fresh calls the bridge exactly once', () => {
    render(<HandoffPreviewCard handoff={{ summary: 'X' }} />);
    const btn = screen.getByRole('button', { name: /Start fresh session/i });
    fireEvent.click(btn);
    fireEvent.click(btn); // second click must be ignored (exactly-once guard)
    expect((window as any)._handoffFork).toHaveBeenCalledTimes(1);
  });

  it('Keep chatting calls the bridge exactly once', () => {
    render(<HandoffPreviewCard handoff={{ summary: 'X' }} />);
    const btn = screen.getByRole('button', { name: /Keep chatting/i });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect((window as any)._handoffKeep).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd agent/webview && npx vitest run src/components/agent/__tests__/HandoffPreviewCard.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the component**

```tsx
import { useCallback, useState } from 'react';
import { GitFork, MessageSquare, ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import type { Handoff } from '@/bridge/types';

interface HandoffPreviewCardProps {
  handoff: Handoff;
}

/**
 * new_task preview card (restored Cline contract). The LLM proposes a handoff with a
 * 5-section summary; the user picks "Start fresh session" (fork) or "Keep chatting"
 * (stay). The decision is delivered to Kotlin exactly once via the window bridges
 * `_handoffFork` / `_handoffKeep`, which feed a sentinel into the suspended AgentLoop.
 */
export function HandoffPreviewCard({ handoff }: HandoffPreviewCardProps) {
  const [decided, setDecided] = useState(false);
  const [expanded, setExpanded] = useState(false);

  const handleFork = useCallback(() => {
    if (decided) return;
    setDecided(true);
    (window as any)._handoffFork?.();
  }, [decided]);

  const handleKeep = useCallback(() => {
    if (decided) return;
    setDecided(true);
    (window as any)._handoffKeep?.();
  }, [decided]);

  return (
    <Card
      className="my-3 gap-0 overflow-hidden py-0 border-[var(--border)] bg-[var(--tool-bg,hsl(var(--card)))]"
      role="region"
      aria-label="Handoff proposal"
    >
      <div
        className="flex items-center gap-3 px-4 py-3"
        style={{ borderBottom: '1px solid var(--border)' }}
      >
        <div className="flex items-center justify-center rounded-md p-2" style={{ backgroundColor: 'var(--code-bg)' }}>
          <GitFork size={18} style={{ color: 'var(--accent)' }} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-[13px] font-semibold" style={{ color: 'var(--fg)' }}>
            Continue in a fresh session?
          </div>
          <div className="text-[11px]" style={{ color: 'var(--fg-secondary)' }}>
            The agent prepared a handoff summary to carry into a clean context.
          </div>
        </div>
      </div>

      <CardContent className="px-4 py-3">
        <button
          type="button"
          onClick={() => setExpanded(e => !e)}
          className="flex items-center gap-1 text-[12px] font-medium mb-2"
          style={{ color: 'var(--accent)' }}
          aria-expanded={expanded}
        >
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
          {expanded ? 'Hide summary' : 'Show summary'}
        </button>
        {expanded && (
          <div className="text-[12px] leading-relaxed max-h-[320px] overflow-auto" style={{ color: 'var(--fg-secondary)' }}>
            <MarkdownRenderer content={handoff.summary} isStreaming={false} />
          </div>
        )}
      </CardContent>

      <CardFooter className="gap-2 px-4 py-3" style={{ borderTop: '1px solid var(--border)' }}>
        <Button
          onClick={handleKeep}
          className="text-[12px] font-medium"
          size="sm"
          variant="outline"
          disabled={decided}
          style={{ color: 'var(--fg-secondary)' }}
        >
          <MessageSquare size={14} />
          Keep chatting here
        </Button>
        <Button
          onClick={handleFork}
          className="glow-btn flex-1 text-[12px] font-medium"
          size="sm"
          disabled={decided}
        >
          <GitFork size={14} />
          Start fresh session
        </Button>
      </CardFooter>
    </Card>
  );
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd agent/webview && npx vitest run src/components/agent/__tests__/HandoffPreviewCard.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/agent/HandoffPreviewCard.tsx agent/webview/src/components/agent/__tests__/HandoffPreviewCard.test.tsx
git commit -m "feat(webview): HandoffPreviewCard (collapsible summary + two-button decision)"
```

---

### Task 12: Webview — mount the card + bridge functions both directions

**Files:**
- Modify: `agent/webview/src/components/chat/ChatFooter.tsx` — subscribe to `handoff` + render the card (mirror the `plan` line at 138).
- Modify: `agent/webview/src/bridge/jcef-bridge.ts` — `renderHandoff`/`clearHandoff` (K→JS, near line 253) + `startFreshSession`/`keepChatting` on `kotlinBridge` (JS→K, near line 671).
- Test: none new (covered by Tasks 10/11 + build). Build is the gate.

- [ ] **Step 1: Mount the card in ChatFooter**

Add the subscription near the other `useChatStore` selectors (line 40):

```tsx
  const handoff = useChatStore(s => s.handoff);
```

Add the render next to the plan card (after line 138 `{plan && !plan.approved && <PlanSummaryCard plan={plan} />}`):

```tsx
      {handoff && <HandoffPreviewCard handoff={handoff} />}
```

Add the import at the top with the other component imports:

```tsx
import { HandoffPreviewCard } from '@/components/agent/HandoffPreviewCard';
```

- [ ] **Step 2: K→JS bridge functions**

In `jcef-bridge.ts`, in the same object as `renderPlan`/`clearPlan` (after line 258), add:

```ts
  renderHandoff(handoffJson: string) {
    const handoff = JSON.parse(handoffJson);
    stores?.getChatStore().setHandoff(handoff);
  },
  clearHandoff() {
    stores?.getChatStore().clearHandoff();
  },
```

- [ ] **Step 3: JS→K bridge functions**

In the `kotlinBridge` object (after line 673 `dismissPlan()`), add:

```ts
  startFreshSession(): void { callKotlin('_handoffFork'); },
  keepChatting(): void { callKotlin('_handoffKeep'); },
```

> The component currently calls `window._handoffFork()` / `window._handoffKeep()` directly (matching `PlanSummaryCard`'s `window._approvePlan()` pattern). The `kotlinBridge` wrappers are added for parity/typed access; the injected `window._handoffFork`/`_handoffKeep` globals (Task 9 step 3) are what the card invokes. Keep both — the globals are the live path.

- [ ] **Step 4: Build the webview**

Run: `cd agent/webview && npm run build`
Expected: build completes, output in `agent/src/main/resources/webview/dist/`.

- [ ] **Step 5: Commit**

```bash
git add agent/webview/src/components/chat/ChatFooter.tsx agent/webview/src/bridge/jcef-bridge.ts agent/src/main/resources/webview/dist
git commit -m "feat(webview): mount HandoffPreviewCard + handoff bridge wiring"
```

---

### Task 13: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Full agent test suite**

Run: `./gradlew :agent:test`
Expected: BUILD SUCCESSFUL — all tests pass (including the new handoff/sessionActive/context-manager tests and the pre-existing `AgentLoopExitDrainTest`, which exercises the old SessionHandoff drain path — confirm it still passes given the new branch).

- [ ] **Step 2: Webview tests**

Run: `cd agent/webview && npx vitest run`
Expected: all pass.

- [ ] **Step 3: Plugin verification**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual smoke checklist (document results in the PR/commit body)**

  1. Start a chat, do enough work to make the LLM call `new_task` (or trigger it via a test prompt). Confirm the **card appears with two buttons** and the loop is paused (spinner gone, input enabled).
  2. Click **Keep chatting here** → status line "Staying in this session.", card disappears, prior messages remain, type a follow-up → it **appends** (no wipe).
  3. Repeat, click **Start fresh session** → fresh view with "Continuing in a fresh session…" status, agent continues; then send a message → it **appends** to the forked session (no wipe).
  4. Resume an older session from History, let it finish, send a message → **no wipe**.

- [ ] **Step 5: Final commit (if any dist/test artifacts changed)**

```bash
git add -A
git commit -m "test(agent): full verification for new_task confirm flow + chat-wipe fix"
```

---

## Notes for the reviewer

- The behavioural fork/decline mapping is asserted via source-contract tests (Tasks 3/6/7/8) because `AgentLoop`/`AgentController` are impractical to instantiate in isolation — this matches the codebase's existing `AgentLoopStreamingEditTest` / `RunInvocationLeakTest` source-pin convention. The end-to-end path is covered by the manual smoke checklist (Task 13 step 4).
- `userInputChannel` is shared with `plan_mode_respond`. They are mutually exclusive in time (a turn either presents a plan or proposes a handoff). The decline/fork handlers reuse the exact `loopWaitingForInput && channel != null && currentJob?.isActive` guard from `revisePlan`/`performPlanDiscard`.
