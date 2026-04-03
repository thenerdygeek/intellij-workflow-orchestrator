# Ralph Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement iterative self-improvement loop (Ralph Loop + Reviewer Gate) where the agent completes a task, a reviewer evaluates it, and the agent re-runs with feedback until the reviewer accepts.

**Architecture:** New `ralph/` package with 4 files (state, orchestrator, reviewer, scorecard). Hooks into `AgentController.handleResult()` to intercept session completion and optionally re-trigger. Reviewer runs as a `WorkerSession` between iterations. State persisted to disk for crash recovery.

**Tech Stack:** Kotlin, kotlinx.serialization (JSON persistence), IntelliJ Project services, existing WorkerSession/PromptAssembler/RollbackManager infrastructure.

**Spec:** `docs/superpowers/specs/2026-04-03-ralph-loop-adaptation-design.md`

---

### Task 1: RalphLoopState — Data Model + Persistence

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopState.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopStateTest.kt`

- [ ] **Step 1: Write the failing tests for state serialization and persistence**

```kotlin
package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopStateTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save and load round-trips state correctly`() {
        val state = RalphLoopState(
            loopId = "test-123",
            projectPath = "/tmp/project",
            originalPrompt = "Build a REST API",
            maxIterations = 10,
            maxCostUsd = 10.0,
            reviewerEnabled = true,
            phase = RalphPhase.EXECUTING,
            iteration = 3,
            totalCostUsd = 4.50,
            totalTokensUsed = 150_000,
            reviewerFeedback = "Add error handling",
            priorAccomplishments = "Created endpoints for /users and /tasks",
            iterationHistory = listOf(
                RalphIterationRecord(
                    iteration = 1,
                    sessionId = "sess-1",
                    costUsd = 1.50,
                    tokensUsed = 50_000,
                    durationMs = 30_000,
                    reviewerVerdict = "IMPROVE",
                    reviewerFeedback = "Missing validation",
                    filesChanged = listOf("src/UserController.kt")
                )
            ),
            autoExpandCount = 0,
            consecutiveImprovesWithoutProgress = 0,
            startedAt = "2026-04-03T10:00:00Z",
            lastIterationAt = "2026-04-03T10:05:00Z",
            completedAt = null,
            currentSessionId = "sess-3",
            allSessionIds = listOf("sess-1", "sess-2", "sess-3")
        )

        RalphLoopState.save(state, tempDir)
        val loaded = RalphLoopState.load(tempDir)

        assertNotNull(loaded)
        assertEquals(state.loopId, loaded!!.loopId)
        assertEquals(state.originalPrompt, loaded.originalPrompt)
        assertEquals(state.phase, loaded.phase)
        assertEquals(state.iteration, loaded.iteration)
        assertEquals(state.totalCostUsd, loaded.totalCostUsd, 0.001)
        assertEquals(state.reviewerFeedback, loaded.reviewerFeedback)
        assertEquals(1, loaded.iterationHistory.size)
        assertEquals("IMPROVE", loaded.iterationHistory[0].reviewerVerdict)
    }

    @Test
    fun `load returns null for missing file`() {
        assertNull(RalphLoopState.load(tempDir))
    }

    @Test
    fun `delete removes state file`() {
        val state = createMinimalState()
        RalphLoopState.save(state, tempDir)
        assertTrue(RalphLoopState.load(tempDir) != null)

        RalphLoopState.delete(tempDir)
        assertNull(RalphLoopState.load(tempDir))
    }

    @Test
    fun `all phases serialize correctly`() {
        for (phase in RalphPhase.entries) {
            val state = createMinimalState().copy(phase = phase)
            RalphLoopState.save(state, tempDir)
            val loaded = RalphLoopState.load(tempDir)
            assertEquals(phase, loaded?.phase)
        }
    }

    @Test
    fun `isTerminal returns true for terminal phases`() {
        assertTrue(RalphPhase.COMPLETED.isTerminal)
        assertTrue(RalphPhase.FORCE_COMPLETED.isTerminal)
        assertTrue(RalphPhase.CANCELLED.isTerminal)
        assertFalse(RalphPhase.EXECUTING.isTerminal)
        assertFalse(RalphPhase.REVIEWING.isTerminal)
        assertFalse(RalphPhase.INTERRUPTED.isTerminal)
    }

    private fun createMinimalState() = RalphLoopState(
        loopId = "min-123",
        projectPath = "/tmp",
        originalPrompt = "test",
        maxIterations = 5,
        maxCostUsd = 5.0,
        reviewerEnabled = false,
        phase = RalphPhase.EXECUTING,
        iteration = 1,
        totalCostUsd = 0.0,
        totalTokensUsed = 0,
        reviewerFeedback = null,
        priorAccomplishments = null,
        iterationHistory = emptyList(),
        autoExpandCount = 0,
        consecutiveImprovesWithoutProgress = 0,
        startedAt = "2026-04-03T10:00:00Z",
        lastIterationAt = null,
        completedAt = null,
        currentSessionId = null,
        allSessionIds = emptyList()
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*RalphLoopStateTest*" -v`
Expected: FAIL — classes don't exist yet

- [ ] **Step 3: Implement RalphLoopState, RalphPhase, RalphIterationRecord, RalphLoopConfig, RalphLoopDecision**

```kotlin
package com.workflow.orchestrator.agent.ralph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
private const val STATE_FILE = "ralph-state.json"

@Serializable
enum class RalphPhase {
    EXECUTING,
    AWAITING_REVIEW,
    REVIEWING,
    COMPLETED,
    FORCE_COMPLETED,
    CANCELLED,
    INTERRUPTED;

    val isTerminal: Boolean get() = this in setOf(COMPLETED, FORCE_COMPLETED, CANCELLED)
}

@Serializable
data class RalphIterationRecord(
    val iteration: Int,
    val sessionId: String,
    val costUsd: Double,
    val tokensUsed: Long,
    val durationMs: Long,
    val reviewerVerdict: String?,
    val reviewerFeedback: String?,
    val filesChanged: List<String>,
)

@Serializable
data class RalphLoopState(
    val loopId: String,
    val projectPath: String,
    val originalPrompt: String,
    val maxIterations: Int,
    val maxCostUsd: Double,
    val reviewerEnabled: Boolean,
    val phase: RalphPhase,
    val iteration: Int,
    val totalCostUsd: Double,
    val totalTokensUsed: Long,
    val reviewerFeedback: String?,
    val priorAccomplishments: String?,
    val iterationHistory: List<RalphIterationRecord>,
    val autoExpandCount: Int,
    val consecutiveImprovesWithoutProgress: Int,
    val startedAt: String,
    val lastIterationAt: String?,
    val completedAt: String?,
    val currentSessionId: String?,
    val allSessionIds: List<String>,
) {
    companion object {
        fun save(state: RalphLoopState, dir: File) {
            dir.mkdirs()
            File(dir, STATE_FILE).writeText(json.encodeToString(serializer(), state))
        }

        fun load(dir: File): RalphLoopState? {
            val file = File(dir, STATE_FILE)
            if (!file.exists()) return null
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (_: Exception) { null }
        }

        fun delete(dir: File) {
            File(dir, STATE_FILE).delete()
        }
    }
}

data class RalphLoopConfig(
    val maxIterations: Int = 10,
    val maxCostUsd: Double = 10.0,
    val reviewerEnabled: Boolean = true,
)

sealed class RalphLoopDecision {
    data class Continue(val iterationContext: String) : RalphLoopDecision()
    data class Completed(val summary: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
    data class ForcedCompletion(val reason: String, val totalCost: Double, val iterations: Int) : RalphLoopDecision()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*RalphLoopStateTest*" -v`
Expected: PASS (all 4 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopState.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopStateTest.kt
git commit -m "feat(agent): add RalphLoopState data model and persistence"
```

---

### Task 2: RalphReviewer — Reviewer Prompt + Response Parsing

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphReviewer.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphReviewerTest.kt`

- [ ] **Step 1: Write the failing tests for response parsing**

```kotlin
package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RalphReviewerTest {

    @Test
    fun `parses ACCEPT verdict`() {
        val result = RalphReviewer.parseResponse("ACCEPT — work meets requirements")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
        assertNull(result.feedback)
    }

    @Test
    fun `parses ACCEPT with no trailing text`() {
        val result = RalphReviewer.parseResponse("ACCEPT")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
    }

    @Test
    fun `parses IMPROVE verdict with feedback`() {
        val result = RalphReviewer.parseResponse("IMPROVE: Missing error handling in UserController.kt")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertEquals("Missing error handling in UserController.kt", result.feedback)
    }

    @Test
    fun `parses IMPROVE with multiline feedback`() {
        val result = RalphReviewer.parseResponse(
            "IMPROVE: Two issues found:\n1. No input validation\n2. Missing unit tests"
        )
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertTrue(result.feedback!!.contains("No input validation"))
        assertTrue(result.feedback!!.contains("Missing unit tests"))
    }

    @Test
    fun `defaults to IMPROVE for ambiguous response`() {
        val result = RalphReviewer.parseResponse("The code has some issues that need fixing")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertEquals("The code has some issues that need fixing", result.feedback)
    }

    @Test
    fun `detects ACCEPT embedded in response`() {
        val result = RalphReviewer.parseResponse("After reviewing the code, I'll say ACCEPT. It looks good.")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
    }

    @Test
    fun `IMPROVE takes priority when both present`() {
        val result = RalphReviewer.parseResponse("I want to ACCEPT but must IMPROVE: fix the bug first")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
    }

    @Test
    fun `trims whitespace from feedback`() {
        val result = RalphReviewer.parseResponse("IMPROVE:   needs tests   ")
        assertEquals("needs tests", result.feedback)
    }

    @Test
    fun `caps feedback at MAX_FEEDBACK_LENGTH`() {
        val longFeedback = "IMPROVE: " + "x".repeat(3000)
        val result = RalphReviewer.parseResponse(longFeedback)
        assertTrue(result.feedback!!.length <= RalphReviewer.MAX_FEEDBACK_LENGTH)
    }

    @Test
    fun `buildReviewerPrompt includes all sections`() {
        val prompt = RalphReviewer.buildReviewerPrompt(
            originalTask = "Build REST API",
            iteration = 2,
            maxIterations = 10,
            completionSummary = "Added /users endpoint",
            changedFiles = listOf("src/UserController.kt", "src/UserService.kt"),
            planStatus = "Step 1: done, Step 2: pending",
            priorFeedback = "Add validation"
        )

        assertTrue(prompt.contains("Build REST API"))
        assertTrue(prompt.contains("2 of 10"))
        assertTrue(prompt.contains("Added /users endpoint"))
        assertTrue(prompt.contains("UserController.kt"))
        assertTrue(prompt.contains("Step 1: done"))
        assertTrue(prompt.contains("Add validation"))
        assertTrue(prompt.contains("ACCEPT"))
        assertTrue(prompt.contains("IMPROVE"))
    }

    @Test
    fun `buildReviewerPrompt omits optional sections when null`() {
        val prompt = RalphReviewer.buildReviewerPrompt(
            originalTask = "Fix bug",
            iteration = 1,
            maxIterations = 5,
            completionSummary = "Fixed it",
            changedFiles = emptyList(),
            planStatus = null,
            priorFeedback = null
        )

        assertFalse(prompt.contains("plan_status"))
        assertFalse(prompt.contains("prior_reviewer_feedback"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*RalphReviewerTest*" -v`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement RalphReviewer**

```kotlin
package com.workflow.orchestrator.agent.ralph

enum class ReviewVerdict { ACCEPT, IMPROVE }

data class ReviewResult(
    val verdict: ReviewVerdict,
    val feedback: String?,
)

/**
 * Builds reviewer prompts and parses reviewer responses for the Ralph Loop.
 *
 * The reviewer is executed as a WorkerSession (see RalphLoopOrchestrator),
 * but prompt building and response parsing are pure functions testable without IntelliJ.
 */
object RalphReviewer {
    const val MAX_FEEDBACK_LENGTH = 2000

    /** Reviewer system prompt — role and behavioral instructions. */
    const val SYSTEM_PROMPT = """You are a code reviewer evaluating work done by an AI coding agent.
Your job is to determine if the agent's work meets the requirements of the original task.
Be pragmatic — request correctness, not perfection. Focus on bugs, missing requirements, and broken functionality.
Do NOT request stylistic changes, comment additions, or minor refactoring unless they affect correctness."""

    fun buildReviewerPrompt(
        originalTask: String,
        iteration: Int,
        maxIterations: Int,
        completionSummary: String,
        changedFiles: List<String>,
        planStatus: String?,
        priorFeedback: String?,
    ): String = buildString {
        appendLine("Evaluate the following work against the original task.")
        appendLine()
        appendLine("<original_task>")
        appendLine(originalTask)
        appendLine("</original_task>")
        appendLine()
        appendLine("<iteration>$iteration of $maxIterations</iteration>")
        appendLine()
        appendLine("<completion_summary>")
        appendLine(completionSummary)
        appendLine("</completion_summary>")
        if (changedFiles.isNotEmpty()) {
            appendLine()
            appendLine("<files_changed>")
            changedFiles.forEach { appendLine("- $it") }
            appendLine("</files_changed>")
        }
        if (!planStatus.isNullOrBlank()) {
            appendLine()
            appendLine("<plan_status>")
            appendLine(planStatus)
            appendLine("</plan_status>")
        }
        if (!priorFeedback.isNullOrBlank()) {
            appendLine()
            appendLine("<prior_reviewer_feedback>")
            appendLine(priorFeedback)
            appendLine("</prior_reviewer_feedback>")
        }
        appendLine()
        appendLine("Instructions:")
        appendLine("1. Read the changed files to evaluate the actual code quality")
        appendLine("2. Run diagnostics to check for errors")
        appendLine("3. Assess whether the work fully satisfies the original task")
        appendLine("4. Check for bugs, missing edge cases, or incomplete implementations")
        if (!priorFeedback.isNullOrBlank()) {
            appendLine("5. Verify that the previous reviewer feedback was addressed")
        }
        appendLine()
        appendLine("Respond with EXACTLY one of:")
        appendLine("  ACCEPT — work meets requirements, no further iteration needed.")
        appendLine("  IMPROVE: <specific, actionable feedback about what to change>")
    }

    fun parseResponse(content: String): ReviewResult {
        val trimmed = content.trim()
        // Check first word
        if (trimmed.startsWith("ACCEPT")) {
            return ReviewResult(ReviewVerdict.ACCEPT, null)
        }
        if (trimmed.startsWith("IMPROVE")) {
            val feedback = trimmed.removePrefix("IMPROVE:").removePrefix("IMPROVE").trim()
                .take(MAX_FEEDBACK_LENGTH)
                .ifEmpty { trimmed.take(MAX_FEEDBACK_LENGTH) }
            return ReviewResult(ReviewVerdict.IMPROVE, feedback)
        }
        // Check for embedded keywords — IMPROVE takes priority
        val hasImprove = trimmed.contains("IMPROVE")
        val hasAccept = trimmed.contains("ACCEPT")
        if (hasImprove) {
            val idx = trimmed.indexOf("IMPROVE")
            val after = trimmed.substring(idx).removePrefix("IMPROVE:").removePrefix("IMPROVE").trim()
            return ReviewResult(ReviewVerdict.IMPROVE, after.take(MAX_FEEDBACK_LENGTH).ifEmpty { trimmed.take(MAX_FEEDBACK_LENGTH) })
        }
        if (hasAccept && !hasImprove) {
            return ReviewResult(ReviewVerdict.ACCEPT, null)
        }
        // Ambiguous — default to IMPROVE
        return ReviewResult(ReviewVerdict.IMPROVE, trimmed.take(MAX_FEEDBACK_LENGTH))
    }

    /** Tool names the reviewer is allowed to use (read-only evaluation tools). */
    val REVIEWER_TOOLS = setOf(
        "read_file", "search_code", "diagnostics", "problem_view",
        "find_definition", "find_references", "run_inspections",
        "file_structure", "glob_files", "get_annotations", "get_method_body"
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*RalphReviewerTest*" -v`
Expected: PASS (all 10 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphReviewer.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphReviewerTest.kt
git commit -m "feat(agent): add RalphReviewer prompt builder and response parser"
```

---

### Task 3: RalphLoopOrchestrator — Core Loop Logic

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopOrchestrator.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopOrchestratorTest.kt`

- [ ] **Step 1: Write the failing tests for orchestrator logic**

```kotlin
package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopOrchestratorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var orchestrator: RalphLoopOrchestrator

    @BeforeEach
    fun setUp() {
        orchestrator = RalphLoopOrchestrator(ralphDir = tempDir)
    }

    @Test
    fun `startLoop creates state with correct defaults`() {
        val state = orchestrator.startLoop(
            prompt = "Build a REST API",
            config = RalphLoopConfig()
        )

        assertEquals("Build a REST API", state.originalPrompt)
        assertEquals(RalphPhase.EXECUTING, state.phase)
        assertEquals(1, state.iteration)
        assertEquals(10, state.maxIterations)
        assertEquals(10.0, state.maxCostUsd, 0.001)
        assertTrue(state.reviewerEnabled)
        assertEquals(0.0, state.totalCostUsd, 0.001)
        assertTrue(state.iterationHistory.isEmpty())
    }

    @Test
    fun `startLoop persists state to disk`() {
        val state = orchestrator.startLoop("test", RalphLoopConfig())
        val loaded = RalphLoopState.load(File(tempDir, state.loopId))
        assertNotNull(loaded)
        assertEquals(state.loopId, loaded!!.loopId)
    }

    @Test
    fun `getCurrentState returns null when no loop active`() {
        assertNull(orchestrator.getCurrentState())
    }

    @Test
    fun `getCurrentState returns active state`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        assertNotNull(orchestrator.getCurrentState())
    }

    @Test
    fun `cancel sets phase to CANCELLED and returns final state`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        val finalState = orchestrator.cancel()
        assertNotNull(finalState)
        assertEquals(RalphPhase.CANCELLED, finalState!!.phase)
        assertNotNull(finalState.completedAt)
    }

    @Test
    fun `cancel returns null when no active loop`() {
        assertNull(orchestrator.cancel())
    }

    @Test
    fun `onIterationCompleted returns ForcedCompletion when budget exhausted`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxCostUsd = 5.0))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 5.0,
            tokensUsed = 100_000,
            durationMs = 30_000,
            filesChanged = listOf("file.kt"),
            completionSummary = "Done",
            sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
        assertTrue((decision as RalphLoopDecision.ForcedCompletion).reason.contains("Budget"))
    }

    @Test
    fun `onIterationCompleted returns ForcedCompletion when max iterations reached without auto-expand`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = emptyList(), // no progress → no auto-expand
            completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
    }

    @Test
    fun `onIterationCompleted continues when reviewer disabled and under limits`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 5, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        assertTrue(decision is RalphLoopDecision.Continue)
    }

    @Test
    fun `auto-expand extends maxIterations when files changed at limit`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        val decision = orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), // active progress → auto-expand
            completionSummary = "Done", sessionId = "sess-1"
        )
        // Should auto-expand and continue, not force-complete
        assertTrue(decision is RalphLoopDecision.Continue)
        assertEquals(6, orchestrator.getCurrentState()!!.maxIterations) // 1 + 5
        assertEquals(1, orchestrator.getCurrentState()!!.autoExpandCount)
    }

    @Test
    fun `auto-expand capped at 3 expansions`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 1, reviewerEnabled = false))
        // Expand 3 times
        repeat(3) {
            orchestrator.onIterationCompleted(
                costUsd = 0.5, tokensUsed = 10_000, durationMs = 5_000,
                filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-$it"
            )
            // Simulate next iterations reaching the new limit each time
            val state = orchestrator.getCurrentState()!!
            orchestrator.forceIteration(state.maxIterations)
        }
        // 4th attempt — should NOT auto-expand
        val decision = orchestrator.onIterationCompleted(
            costUsd = 0.5, tokensUsed = 10_000, durationMs = 5_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-final"
        )
        assertTrue(decision is RalphLoopDecision.ForcedCompletion)
    }

    @Test
    fun `buildIterationContext includes original prompt and feedback`() {
        orchestrator.startLoop("Build a REST API", RalphLoopConfig())
        orchestrator.setReviewerFeedback("Add validation", "Created endpoints")
        val context = orchestrator.buildIterationContext()

        assertTrue(context.contains("<ralph_iteration>"))
        assertTrue(context.contains("Build a REST API"))
        assertTrue(context.contains("Add validation"))
        assertTrue(context.contains("Created endpoints"))
        assertTrue(context.contains("</ralph_iteration>"))
    }

    @Test
    fun `buildIterationContext omits feedback on first iteration`() {
        orchestrator.startLoop("Fix bug", RalphLoopConfig())
        val context = orchestrator.buildIterationContext()

        assertTrue(context.contains("Fix bug"))
        assertFalse(context.contains("Reviewer Feedback"))
    }

    @Test
    fun `onReviewerResult ACCEPT completes the loop`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        val decision = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.ACCEPT, null),
            reviewerCostUsd = 0.30
        )
        assertTrue(decision is RalphLoopDecision.Completed)
        assertEquals(RalphPhase.COMPLETED, orchestrator.getCurrentState()!!.phase)
    }

    @Test
    fun `onReviewerResult IMPROVE continues with feedback`() {
        orchestrator.startLoop("test", RalphLoopConfig())
        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("file.kt"), completionSummary = "Done", sessionId = "sess-1"
        )
        val decision = orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.IMPROVE, "Add tests"),
            reviewerCostUsd = 0.30
        )
        assertTrue(decision is RalphLoopDecision.Continue)
        assertEquals("Add tests", orchestrator.getCurrentState()!!.reviewerFeedback)
        assertEquals(2, orchestrator.getCurrentState()!!.iteration)
    }

    @Test
    fun `3 consecutive IMPROVEs without file changes forces completion`() {
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 10))
        repeat(3) {
            orchestrator.onIterationCompleted(
                costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
                filesChanged = emptyList(), // no progress
                completionSummary = "Done", sessionId = "sess-$it"
            )
            orchestrator.onReviewerResult(
                ReviewResult(ReviewVerdict.IMPROVE, "Try harder"),
                reviewerCostUsd = 0.30
            )
        }
        // After 3rd IMPROVE with no progress, the state should be FORCE_COMPLETED
        assertEquals(RalphPhase.FORCE_COMPLETED, orchestrator.getCurrentState()!!.phase)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*RalphLoopOrchestratorTest*" -v`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement RalphLoopOrchestrator**

```kotlin
package com.workflow.orchestrator.agent.ralph

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Core Ralph Loop manager. Tracks state, makes decisions about continuing or stopping,
 * builds iteration context for system prompt injection.
 *
 * This class is deliberately decoupled from IntelliJ services — it takes a directory
 * for persistence and pure data for decisions. AgentController wires it to the IDE.
 */
class RalphLoopOrchestrator(
    private val ralphDir: File,
) {
    companion object {
        private val LOG = Logger.getInstance(RalphLoopOrchestrator::class.java)
        private const val MAX_AUTO_EXPANDS = 3
        private const val AUTO_EXPAND_INCREMENT = 5
        private const val MAX_CONSECUTIVE_IMPROVES_WITHOUT_PROGRESS = 3
        private const val BUDGET_SAFETY_MARGIN = 0.95
    }

    private var state: RalphLoopState? = null

    fun startLoop(prompt: String, config: RalphLoopConfig): RalphLoopState {
        val loopId = UUID.randomUUID().toString().take(12)
        val newState = RalphLoopState(
            loopId = loopId,
            projectPath = ralphDir.absolutePath,
            originalPrompt = prompt,
            maxIterations = config.maxIterations,
            maxCostUsd = config.maxCostUsd,
            reviewerEnabled = config.reviewerEnabled,
            phase = RalphPhase.EXECUTING,
            iteration = 1,
            totalCostUsd = 0.0,
            totalTokensUsed = 0,
            reviewerFeedback = null,
            priorAccomplishments = null,
            iterationHistory = emptyList(),
            autoExpandCount = 0,
            consecutiveImprovesWithoutProgress = 0,
            startedAt = Instant.now().toString(),
            lastIterationAt = Instant.now().toString(),
            completedAt = null,
            currentSessionId = null,
            allSessionIds = emptyList()
        )
        state = newState
        persistState(newState)
        LOG.info("RalphLoop: started loop $loopId — maxIter=${config.maxIterations}, budget=$${config.maxCostUsd}, reviewer=${config.reviewerEnabled}")
        return newState
    }

    fun getCurrentState(): RalphLoopState? = state

    fun cancel(): RalphLoopState? {
        val current = state ?: return null
        val cancelled = current.copy(
            phase = RalphPhase.CANCELLED,
            completedAt = Instant.now().toString()
        )
        state = cancelled
        persistState(cancelled)
        LOG.info("RalphLoop: cancelled ${current.loopId} at iteration ${current.iteration}")
        return cancelled
    }

    fun resumeInterrupted(interrupted: RalphLoopState) {
        val resumed = interrupted.copy(phase = RalphPhase.EXECUTING)
        state = resumed
        persistState(resumed)
        LOG.info("RalphLoop: resumed ${interrupted.loopId} at iteration ${interrupted.iteration}")
    }

    /**
     * Called after the agent session completes. Records the iteration, checks budget/limits.
     * Does NOT run the reviewer — caller must call [onReviewerResult] separately if reviewer is enabled.
     * Returns a decision if the loop should stop (budget/iterations), or null if reviewer should run.
     */
    fun onIterationCompleted(
        costUsd: Double,
        tokensUsed: Long,
        durationMs: Long,
        filesChanged: List<String>,
        completionSummary: String,
        sessionId: String,
    ): RalphLoopDecision {
        val current = state ?: return RalphLoopDecision.ForcedCompletion("No active loop", 0.0, 0)
        val newTotalCost = current.totalCostUsd + costUsd

        // Record iteration
        val record = RalphIterationRecord(
            iteration = current.iteration,
            sessionId = sessionId,
            costUsd = costUsd,
            tokensUsed = tokensUsed,
            durationMs = durationMs,
            reviewerVerdict = null,
            reviewerFeedback = null,
            filesChanged = filesChanged
        )

        var updated = current.copy(
            totalCostUsd = newTotalCost,
            totalTokensUsed = current.totalTokensUsed + tokensUsed,
            iterationHistory = current.iterationHistory + record,
            currentSessionId = sessionId,
            allSessionIds = current.allSessionIds + sessionId,
            phase = if (current.reviewerEnabled) RalphPhase.AWAITING_REVIEW else current.phase,
            priorAccomplishments = buildAccomplishments(current, completionSummary)
        )

        // Budget check
        if (updated.maxCostUsd > 0 && newTotalCost >= updated.maxCostUsd * BUDGET_SAFETY_MARGIN) {
            updated = updated.copy(phase = RalphPhase.FORCE_COMPLETED, completedAt = Instant.now().toString())
            state = updated
            persistState(updated)
            LOG.info("RalphLoop: budget exhausted ($${newTotalCost}/$${updated.maxCostUsd})")
            return RalphLoopDecision.ForcedCompletion(
                "Budget exhausted ($${String.format("%.2f", newTotalCost)}/$${String.format("%.2f", updated.maxCostUsd)})",
                newTotalCost, updated.iteration
            )
        }

        // Max iterations check (with auto-expand)
        if (updated.maxIterations > 0 && updated.iteration >= updated.maxIterations) {
            if (filesChanged.isNotEmpty() && updated.autoExpandCount < MAX_AUTO_EXPANDS) {
                updated = updated.copy(
                    maxIterations = updated.maxIterations + AUTO_EXPAND_INCREMENT,
                    autoExpandCount = updated.autoExpandCount + 1
                )
                LOG.info("RalphLoop: auto-expanded to ${updated.maxIterations} (expansion ${updated.autoExpandCount})")
            } else {
                updated = updated.copy(phase = RalphPhase.FORCE_COMPLETED, completedAt = Instant.now().toString())
                state = updated
                persistState(updated)
                LOG.info("RalphLoop: max iterations reached (${updated.iteration}/${updated.maxIterations})")
                return RalphLoopDecision.ForcedCompletion(
                    "Max iterations reached (${updated.iteration}/${updated.maxIterations})",
                    newTotalCost, updated.iteration
                )
            }
        }

        state = updated
        persistState(updated)

        // If reviewer disabled, continue to next iteration
        if (!updated.reviewerEnabled) {
            val next = updated.copy(
                iteration = updated.iteration + 1,
                lastIterationAt = Instant.now().toString()
            )
            state = next
            persistState(next)
            return RalphLoopDecision.Continue(buildIterationContext())
        }

        // Reviewer enabled — caller should now spawn reviewer and call onReviewerResult
        return RalphLoopDecision.Continue("") // Placeholder — caller will get real context after review
    }

    /**
     * Called after the reviewer returns a verdict.
     * Updates state and returns the final decision for this iteration.
     */
    fun onReviewerResult(result: ReviewResult, reviewerCostUsd: Double): RalphLoopDecision {
        val current = state ?: return RalphLoopDecision.ForcedCompletion("No active loop", 0.0, 0)
        val newTotalCost = current.totalCostUsd + reviewerCostUsd

        // Update the last iteration record with reviewer verdict
        val updatedHistory = current.iterationHistory.toMutableList()
        if (updatedHistory.isNotEmpty()) {
            val lastRecord = updatedHistory.last()
            updatedHistory[updatedHistory.lastIndex] = lastRecord.copy(
                reviewerVerdict = result.verdict.name,
                reviewerFeedback = result.feedback
            )
        }

        when (result.verdict) {
            ReviewVerdict.ACCEPT -> {
                val completed = current.copy(
                    phase = RalphPhase.COMPLETED,
                    totalCostUsd = newTotalCost,
                    completedAt = Instant.now().toString(),
                    iterationHistory = updatedHistory
                )
                state = completed
                persistState(completed)
                LOG.info("RalphLoop: reviewer ACCEPTED at iteration ${current.iteration}")
                return RalphLoopDecision.Completed(
                    "Reviewer accepted after ${current.iteration} iterations",
                    newTotalCost, current.iteration
                )
            }
            ReviewVerdict.IMPROVE -> {
                val lastFilesChanged = current.iterationHistory.lastOrNull()?.filesChanged ?: emptyList()
                val newConsecutive = if (lastFilesChanged.isEmpty()) {
                    current.consecutiveImprovesWithoutProgress + 1
                } else 0

                // Stuck detection
                if (newConsecutive >= MAX_CONSECUTIVE_IMPROVES_WITHOUT_PROGRESS) {
                    val forced = current.copy(
                        phase = RalphPhase.FORCE_COMPLETED,
                        totalCostUsd = newTotalCost,
                        completedAt = Instant.now().toString(),
                        iterationHistory = updatedHistory,
                        consecutiveImprovesWithoutProgress = newConsecutive
                    )
                    state = forced
                    persistState(forced)
                    LOG.info("RalphLoop: force-completed — $newConsecutive consecutive IMPROVEs without file changes")
                    return RalphLoopDecision.ForcedCompletion(
                        "Reviewer requested improvements $newConsecutive consecutive times without progress. Force-completing.",
                        newTotalCost, current.iteration
                    )
                }

                val next = current.copy(
                    phase = RalphPhase.EXECUTING,
                    iteration = current.iteration + 1,
                    totalCostUsd = newTotalCost,
                    reviewerFeedback = result.feedback,
                    lastIterationAt = Instant.now().toString(),
                    iterationHistory = updatedHistory,
                    consecutiveImprovesWithoutProgress = newConsecutive
                )
                state = next
                persistState(next)
                LOG.info("RalphLoop: reviewer IMPROVE at iteration ${current.iteration} — continuing to ${next.iteration}")
                return RalphLoopDecision.Continue(buildIterationContext())
            }
        }
    }

    fun buildIterationContext(): String {
        val current = state ?: return ""
        return buildString {
            appendLine("<ralph_iteration>")
            appendLine("You are on iteration ${current.iteration} of a self-improvement loop.")
            appendLine("Your task is to review and improve upon work done in previous iterations.")
            appendLine()
            appendLine("## Original Task")
            appendLine(current.originalPrompt)
            if (!current.priorAccomplishments.isNullOrBlank()) {
                appendLine()
                appendLine("## What Was Done in Previous Iterations")
                appendLine(current.priorAccomplishments)
            }
            if (!current.reviewerFeedback.isNullOrBlank()) {
                appendLine()
                appendLine("## Reviewer Feedback (from iteration ${current.iteration - 1})")
                appendLine("The reviewer evaluated the previous iteration's work and requested improvements:")
                appendLine(current.reviewerFeedback)
            }
            appendLine()
            appendLine("## Instructions")
            appendLine("1. Read the files that were modified in previous iterations")
            appendLine("2. Review the current state of the code against the original task")
            if (!current.reviewerFeedback.isNullOrBlank()) {
                appendLine("3. Address the reviewer's feedback specifically")
                appendLine("4. Make improvements and call attempt_completion when done")
            } else {
                appendLine("3. Make improvements and call attempt_completion when done")
            }
            appendLine("</ralph_iteration>")
        }
    }

    /** Expose for testing — force iteration to a specific value. */
    internal fun forceIteration(iteration: Int) {
        val current = state ?: return
        state = current.copy(iteration = iteration)
        persistState(state!!)
    }

    /** Expose for testing — set reviewer feedback and accomplishments. */
    internal fun setReviewerFeedback(feedback: String, accomplishments: String) {
        val current = state ?: return
        state = current.copy(reviewerFeedback = feedback, priorAccomplishments = accomplishments)
    }

    private fun buildAccomplishments(current: RalphLoopState, latestSummary: String): String {
        val prior = current.priorAccomplishments
        return if (prior.isNullOrBlank()) {
            "- Iteration ${current.iteration}: $latestSummary"
        } else {
            "$prior\n- Iteration ${current.iteration}: $latestSummary"
        }
    }

    private fun persistState(s: RalphLoopState) {
        val dir = File(ralphDir, s.loopId)
        RalphLoopState.save(s, dir)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*RalphLoopOrchestratorTest*" -v`
Expected: PASS (all 13 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopOrchestrator.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopOrchestratorTest.kt
git commit -m "feat(agent): add RalphLoopOrchestrator — core loop logic with auto-expand and stuck detection"
```

---

### Task 4: PromptAssembler — Add Ralph Iteration Context Slot

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt:54-120`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssemblerTest.kt` (if exists, else create)

- [ ] **Step 1: Write the failing test**

```kotlin
// Add to existing PromptAssembler tests, or create new test file
@Test
fun `ralph iteration context is injected between previous_results and recency zone`() {
    val assembler = PromptAssembler(mockk(relaxed = true))
    val prompt = assembler.buildSingleAgentPrompt(
        projectName = "test",
        ralphIterationContext = "<ralph_iteration>\nIteration 2\n</ralph_iteration>"
    )
    assertTrue(prompt.contains("<ralph_iteration>"))
    assertTrue(prompt.contains("Iteration 2"))
    // Verify it appears before the recency zone (PLANNING_RULES)
    val ralphIdx = prompt.indexOf("<ralph_iteration>")
    val planningIdx = prompt.indexOf("PLANNING")
    assertTrue(ralphIdx < planningIdx, "Ralph context should appear before planning rules")
}

@Test
fun `ralph iteration context omitted when null`() {
    val assembler = PromptAssembler(mockk(relaxed = true))
    val prompt = assembler.buildSingleAgentPrompt(projectName = "test")
    assertFalse(prompt.contains("<ralph_iteration>"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*PromptAssembler*ralph*" -v`
Expected: FAIL — parameter doesn't exist

- [ ] **Step 3: Add `ralphIterationContext` parameter to `buildSingleAgentPrompt()`**

In `PromptAssembler.kt`, add parameter to the method signature:

```kotlin
fun buildSingleAgentPrompt(
    // ... existing params ...
    ralphIterationContext: String? = null,  // ADD THIS
): String {
```

Then after the `previousStepResults` block (around line 97), add:

```kotlin
if (!ralphIterationContext.isNullOrBlank()) {
    sections.add(ralphIterationContext)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*PromptAssembler*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt
git commit -m "feat(agent): add ralphIterationContext slot to PromptAssembler"
```

---

### Task 5: AgentSettings — Ralph Loop Defaults

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt:15-33`

- [ ] **Step 1: Add Ralph Loop settings fields**

After `var powershellEnabled by property(true)` (line 32), add:

```kotlin
// Ralph Loop defaults
var ralphMaxIterations by property(10)
var ralphMaxCostUsd by string("10.0")
var ralphReviewerEnabled by property(true)
```

- [ ] **Step 2: Run existing agent tests to verify no regression**

Run: `./gradlew :agent:test -v`
Expected: PASS (no regressions — new fields have defaults)

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt
git commit -m "feat(agent): add Ralph Loop default settings (maxIterations, maxCost, reviewerEnabled)"
```

---

### Task 6: AgentService — Hold RalphLoopOrchestrator Reference

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Add `ralphOrchestrator` field to AgentService**

After `@Volatile var currentRollbackManager` (line 69), add:

```kotlin
/** Ralph Loop orchestrator — manages iterative self-improvement loops. */
@Volatile var ralphOrchestrator: RalphLoopOrchestrator? = null
```

Add import:
```kotlin
import com.workflow.orchestrator.agent.ralph.RalphLoopOrchestrator
```

- [ ] **Step 2: Run existing tests to verify no regression**

Run: `./gradlew :agent:test -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): add ralphOrchestrator reference to AgentService"
```

---

### Task 7: AgentController — Wire Ralph Loop into Session Lifecycle

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

This is the largest integration task. Three changes: (A) initialize orchestrator, (B) intercept `handleResult()`, (C) add `startRalphLoop()`, (D) modify `cancelTask()`.

- [ ] **Step 1: Initialize RalphLoopOrchestrator in AgentController constructor or init block**

In the AgentController class, where other services are initialized, add:

```kotlin
private val ralphOrchestrator: RalphLoopOrchestrator by lazy {
    val ralphDir = File(ProjectIdentifier.projectDir(project.basePath ?: ""), "agent/ralph")
    RalphLoopOrchestrator(ralphDir = ralphDir).also {
        try { AgentService.getInstance(project).ralphOrchestrator = it } catch (_: Exception) {}
    }
}
```

Add imports:
```kotlin
import com.workflow.orchestrator.agent.ralph.*
import com.workflow.orchestrator.agent.service.ProjectIdentifier
```

- [ ] **Step 2: Add `startRalphLoop()` method**

```kotlin
fun startRalphLoop(prompt: String, config: RalphLoopConfig = RalphLoopConfig()) {
    val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
    val effectiveConfig = RalphLoopConfig(
        maxIterations = settings?.state?.ralphMaxIterations ?: config.maxIterations,
        maxCostUsd = settings?.state?.ralphMaxCostUsd?.toDoubleOrNull() ?: config.maxCostUsd,
        reviewerEnabled = settings?.state?.ralphReviewerEnabled ?: config.reviewerEnabled
    )

    val state = ralphOrchestrator.startLoop(prompt, effectiveConfig)
    dashboard.appendStatus(
        "Ralph Loop started — iteration 1/${state.maxIterations} | Budget: $${String.format("%.2f", state.maxCostUsd)}",
        RichStreamingPanel.StatusType.INFO
    )
    executeTask(prompt)
}
```

- [ ] **Step 3: Intercept `handleResult()` for Ralph Loop**

In `handleResult()`, replace the `is AgentResult.Completed` branch. The new logic:

```kotlin
is AgentResult.Completed -> {
    dashboard.flushStreamBuffer()

    val ralph = ralphOrchestrator.getCurrentState()
    if (ralph != null && ralph.phase == RalphPhase.EXECUTING) {
        // Ralph Loop active — run iteration completion + reviewer
        val scorecard = lastScorecard  // capture from orchestrator callback
        scope.launch {
            try {
                // Step 1: Record iteration, check budget/limits
                val iterDecision = ralphOrchestrator.onIterationCompleted(
                    costUsd = scorecard?.metrics?.estimatedCostUsd ?: 0.0,
                    tokensUsed = scorecard?.metrics?.totalInputTokens ?: 0,
                    durationMs = durationMs,
                    filesChanged = result.artifacts,
                    completionSummary = result.summary,
                    sessionId = session?.sessionId ?: ""
                )

                // Check if budget/iterations already stopped it
                if (iterDecision is RalphLoopDecision.ForcedCompletion) {
                    withContext(kotlinx.coroutines.Dispatchers.EDT) {
                        dashboard.appendStatus("Ralph Loop stopped: ${iterDecision.reason}", RichStreamingPanel.StatusType.WARNING)
                        dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                    }
                    return@launch
                }

                // Step 2: Run reviewer (if enabled)
                val currentState = ralphOrchestrator.getCurrentState()!!
                val decision: RalphLoopDecision
                if (currentState.reviewerEnabled && currentState.phase == RalphPhase.AWAITING_REVIEW) {
                    withContext(kotlinx.coroutines.Dispatchers.EDT) {
                        dashboard.appendStatus("Reviewing iteration ${currentState.iteration}...", RichStreamingPanel.StatusType.INFO)
                    }

                    // Spawn reviewer WorkerSession
                    val reviewerResult = runReviewerWorker(currentState, result)
                    decision = ralphOrchestrator.onReviewerResult(reviewerResult.first, reviewerResult.second)
                } else {
                    decision = iterDecision
                }

                // Step 3: Act on decision
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    when (decision) {
                        is RalphLoopDecision.Continue -> {
                            session = null
                            dashboard.appendStatus(
                                "Ralph iteration ${ralphOrchestrator.getCurrentState()!!.iteration} — reviewer requested improvements",
                                RichStreamingPanel.StatusType.INFO
                            )
                            executeTask(currentState.originalPrompt)
                        }
                        is RalphLoopDecision.Completed -> {
                            dashboard.appendStatus(
                                "Ralph Loop completed after ${decision.iterations} iterations | Cost: $${String.format("%.2f", decision.totalCost)}",
                                RichStreamingPanel.StatusType.SUCCESS
                            )
                            dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                        }
                        is RalphLoopDecision.ForcedCompletion -> {
                            dashboard.appendStatus(
                                "Ralph Loop stopped: ${decision.reason}",
                                RichStreamingPanel.StatusType.WARNING
                            )
                            dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.error("RalphLoop: error in loop", e)
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    dashboard.appendError("Ralph Loop error: ${e.message}")
                    dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.FAILED)
                }
            }
        }
        return
    }

    // Normal (non-Ralph) completion — existing code
    dashboard.completeSession(result.totalTokens, 0, result.artifacts, durationMs, RichStreamingPanel.SessionStatus.SUCCESS)
    if (result.artifacts.isNotEmpty()) {
        dashboard.appendStatus(
            "Agent modified ${result.artifacts.size} file(s). You can undo all changes via Edit > Undo or LocalHistory.",
            RichStreamingPanel.StatusType.INFO
        )
    }
}
```

- [ ] **Step 4: Add `runReviewerWorker()` private method**

```kotlin
private suspend fun runReviewerWorker(
    ralphState: RalphLoopState,
    completionResult: AgentResult.Completed
): Pair<ReviewResult, Double> {
    val changedFiles = ralphState.iterationHistory.flatMap { it.filesChanged }.distinct()
    val planStatus = try {
        session?.planManager?.currentPlan?.let { plan ->
            plan.steps.joinToString("\n") { "- ${it.title}: ${it.status}" }
        }
    } catch (_: Exception) { null }

    val prompt = RalphReviewer.buildReviewerPrompt(
        originalTask = ralphState.originalPrompt,
        iteration = ralphState.iteration,
        maxIterations = ralphState.maxIterations,
        completionSummary = completionResult.summary,
        changedFiles = changedFiles,
        planStatus = planStatus,
        priorFeedback = ralphState.reviewerFeedback
    )

    return try {
        val agentService = AgentService.getInstance(project)
        val brain = agentService.brain
        val allTools = agentService.toolRegistry.allTools().associateBy { it.name }
        val reviewerTools = allTools.filterKeys { it in RalphReviewer.REVIEWER_TOOLS }
        val reviewerToolDefs = reviewerTools.values.map { it.toToolDefinition() }

        val bridge = com.workflow.orchestrator.agent.context.EventSourcedContextBridge.create(
            sessionDir = null,
            config = com.workflow.orchestrator.agent.context.ContextManagementConfig.DEFAULT,
            maxInputTokens = 100_000
        )

        val worker = WorkerSession(maxIterations = 10)
        val result = worker.execute(
            workerType = WorkerType.REVIEWER,
            systemPrompt = RalphReviewer.SYSTEM_PROMPT,
            task = prompt,
            tools = reviewerTools,
            toolDefinitions = reviewerToolDefs,
            brain = brain,
            bridge = bridge,
            project = project
        )

        val costUsd = com.workflow.orchestrator.agent.runtime.SessionScorecard.computeEstimatedCost(
            result.tokensUsed.toLong(), 0
        )
        Pair(RalphReviewer.parseResponse(result.content), costUsd)
    } catch (e: Exception) {
        LOG.warn("RalphLoop: reviewer failed — ${e.message}, skipping review")
        Pair(ReviewResult(ReviewVerdict.IMPROVE, "Reviewer error: ${e.message}"), 0.0)
    }
}
```

- [ ] **Step 5: Modify `cancelTask()` to cancel Ralph Loop**

In `cancelTask()`, after the existing cancellation code, add:

```kotlin
ralphOrchestrator.cancel()?.let { finalState ->
    dashboard.appendStatus(
        "Ralph Loop cancelled at iteration ${finalState.iteration}",
        RichStreamingPanel.StatusType.WARNING
    )
}
```

- [ ] **Step 6: Modify `newChat()` to cancel active Ralph Loop**

In `newChat()`, before `session = null`, add:

```kotlin
ralphOrchestrator.cancel()
```

- [ ] **Step 7: Run full agent test suite**

Run: `./gradlew :agent:test -v`
Expected: PASS (no regressions — changes are additive, existing paths unchanged)

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire Ralph Loop into AgentController — intercept completion, spawn reviewer, loop"
```

---

### Task 8: AgentStartupActivity — Crash Recovery for Ralph Loops

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt`

- [ ] **Step 1: Add interrupted Ralph Loop detection after existing session detection**

After the existing `interrupted.isNotEmpty()` block (around line 82), before `index.cleanup()`, add:

```kotlin
// Check for interrupted Ralph loops
try {
    val ralphDir = File(ProjectIdentifier.projectDir(projectPath), "agent/ralph")
    if (ralphDir.exists()) {
        val terminalPhases = setOf(RalphPhase.COMPLETED, RalphPhase.FORCE_COMPLETED, RalphPhase.CANCELLED)
        val activeLoops = ralphDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> RalphLoopState.load(dir)?.takeIf { it.phase !in terminalPhases } }
            ?: emptyList()

        for (loop in activeLoops) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("workflow.agent")
                .createNotification(
                    "Ralph Loop Interrupted",
                    "Ralph Loop was interrupted at iteration ${loop.iteration}/${loop.maxIterations}. Cost so far: $${String.format("%.2f", loop.totalCostUsd)}",
                    NotificationType.INFORMATION
                )
                .addAction(NotificationAction.createSimple("Resume") {
                    try {
                        val agentService = com.workflow.orchestrator.agent.AgentService.getInstance(project)
                        agentService.ralphOrchestrator?.resumeInterrupted(loop)
                    } catch (_: Exception) {}
                })
                .addAction(NotificationAction.createSimple("Cancel") {
                    val dir = File(ralphDir, loop.loopId)
                    RalphLoopState.delete(dir)
                })
                .notify(project)
        }
    }
} catch (_: Exception) {}
```

Add imports:
```kotlin
import com.workflow.orchestrator.agent.ralph.RalphLoopState
import com.workflow.orchestrator.agent.ralph.RalphPhase
import com.workflow.orchestrator.agent.service.ProjectIdentifier
```

- [ ] **Step 2: Run existing tests**

Run: `./gradlew :agent:test -v`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt
git commit -m "feat(agent): detect interrupted Ralph Loops on IDE startup and offer resume"
```

---

### Task 9: RalphLoopScorecard — Cross-Session Metrics

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopScorecard.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopScorecardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RalphLoopScorecardTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fromState computes correct aggregates`() {
        val state = RalphLoopState(
            loopId = "test-123", projectPath = "/tmp", originalPrompt = "test",
            maxIterations = 10, maxCostUsd = 10.0, reviewerEnabled = true,
            phase = RalphPhase.COMPLETED, iteration = 3,
            totalCostUsd = 4.50, totalTokensUsed = 150_000,
            reviewerFeedback = null, priorAccomplishments = null,
            iterationHistory = listOf(
                RalphIterationRecord(1, "s1", 1.5, 50_000, 10_000, "IMPROVE", "fix", listOf("a.kt")),
                RalphIterationRecord(2, "s2", 1.8, 60_000, 15_000, "IMPROVE", "more", listOf("a.kt", "b.kt")),
                RalphIterationRecord(3, "s3", 1.2, 40_000, 8_000, "ACCEPT", null, listOf("a.kt"))
            ),
            autoExpandCount = 0, consecutiveImprovesWithoutProgress = 0,
            startedAt = "2026-04-03T10:00:00Z", lastIterationAt = null,
            completedAt = "2026-04-03T10:01:00Z",
            currentSessionId = "s3", allSessionIds = listOf("s1", "s2", "s3")
        )

        val scorecard = RalphLoopScorecard.fromState(state)

        assertEquals("test-123", scorecard.loopId)
        assertEquals(3, scorecard.totalIterations)
        assertEquals(4.50, scorecard.totalCostUsd, 0.001)
        assertEquals(150_000, scorecard.totalTokensUsed)
        assertEquals(33_000, scorecard.totalDurationMs) // 10k + 15k + 8k
        assertEquals(2, scorecard.totalFilesModified) // a.kt, b.kt
        assertEquals("COMPLETED", scorecard.outcome)
    }

    @Test
    fun `save and load round-trips correctly`() {
        val scorecard = RalphLoopScorecard(
            loopId = "sc-1", totalIterations = 2, totalCostUsd = 3.0,
            totalTokensUsed = 100_000, totalDurationMs = 20_000,
            totalFilesModified = 3, outcome = "COMPLETED"
        )
        RalphLoopScorecard.save(scorecard, tempDir)
        val loaded = RalphLoopScorecard.load("sc-1", tempDir)
        assertNotNull(loaded)
        assertEquals(scorecard.totalIterations, loaded!!.totalIterations)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*RalphLoopScorecardTest*" -v`
Expected: FAIL

- [ ] **Step 3: Implement RalphLoopScorecard**

```kotlin
package com.workflow.orchestrator.agent.ralph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

@Serializable
data class RalphLoopScorecard(
    val loopId: String,
    val totalIterations: Int,
    val totalCostUsd: Double,
    val totalTokensUsed: Long,
    val totalDurationMs: Long,
    val totalFilesModified: Int,
    val outcome: String,
) {
    companion object {
        fun fromState(state: RalphLoopState): RalphLoopScorecard {
            val allFiles = state.iterationHistory.flatMap { it.filesChanged }.distinct()
            val totalDuration = state.iterationHistory.sumOf { it.durationMs }
            return RalphLoopScorecard(
                loopId = state.loopId,
                totalIterations = state.iterationHistory.size,
                totalCostUsd = state.totalCostUsd,
                totalTokensUsed = state.totalTokensUsed,
                totalDurationMs = totalDuration,
                totalFilesModified = allFiles.size,
                outcome = state.phase.name
            )
        }

        fun save(scorecard: RalphLoopScorecard, metricsDir: File) {
            metricsDir.mkdirs()
            File(metricsDir, "ralph-${scorecard.loopId}.json")
                .writeText(json.encodeToString(serializer(), scorecard))
        }

        fun load(loopId: String, metricsDir: File): RalphLoopScorecard? {
            val file = File(metricsDir, "ralph-$loopId.json")
            if (!file.exists()) return null
            return try { json.decodeFromString(serializer(), file.readText()) } catch (_: Exception) { null }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*RalphLoopScorecardTest*" -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopScorecard.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopScorecardTest.kt
git commit -m "feat(agent): add RalphLoopScorecard for cross-session metrics aggregation"
```

---

### Task 10: Integration Test — Full Ralph Loop E2E

**Files:**
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopIntegrationTest.kt`

- [ ] **Step 1: Write integration test covering the full loop lifecycle**

```kotlin
package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Integration test: exercises the full loop lifecycle without IntelliJ services.
 * Tests the orchestrator + reviewer parsing + state persistence working together.
 */
class RalphLoopIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `full loop lifecycle — 3 iterations with reviewer`() {
        val orchestrator = RalphLoopOrchestrator(ralphDir = tempDir)

        // Start
        val state = orchestrator.startLoop("Build a REST API", RalphLoopConfig(maxIterations = 10))
        assertEquals(RalphPhase.EXECUTING, state.phase)
        assertEquals(1, state.iteration)

        // Iteration 1: complete → reviewer IMPROVE
        orchestrator.onIterationCompleted(
            costUsd = 1.5, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("UserController.kt"), completionSummary = "Created /users endpoint",
            sessionId = "sess-1"
        )
        val decision1 = orchestrator.onReviewerResult(
            RalphReviewer.parseResponse("IMPROVE: Missing input validation and error handling"),
            reviewerCostUsd = 0.30
        )
        assertTrue(decision1 is RalphLoopDecision.Continue)
        assertEquals(2, orchestrator.getCurrentState()!!.iteration)
        assertTrue(orchestrator.getCurrentState()!!.reviewerFeedback!!.contains("validation"))

        // Iteration 2: complete → reviewer IMPROVE
        orchestrator.onIterationCompleted(
            costUsd = 1.2, tokensUsed = 40_000, durationMs = 8_000,
            filesChanged = listOf("UserController.kt", "UserService.kt"),
            completionSummary = "Added validation and error handling",
            sessionId = "sess-2"
        )
        val decision2 = orchestrator.onReviewerResult(
            RalphReviewer.parseResponse("IMPROVE: Add unit tests"),
            reviewerCostUsd = 0.25
        )
        assertTrue(decision2 is RalphLoopDecision.Continue)

        // Iteration 3: complete → reviewer ACCEPT
        orchestrator.onIterationCompleted(
            costUsd = 0.9, tokensUsed = 30_000, durationMs = 6_000,
            filesChanged = listOf("UserControllerTest.kt"),
            completionSummary = "Added unit tests",
            sessionId = "sess-3"
        )
        val decision3 = orchestrator.onReviewerResult(
            RalphReviewer.parseResponse("ACCEPT — all requirements met"),
            reviewerCostUsd = 0.20
        )
        assertTrue(decision3 is RalphLoopDecision.Completed)
        assertEquals(RalphPhase.COMPLETED, orchestrator.getCurrentState()!!.phase)

        // Verify metrics
        val finalState = orchestrator.getCurrentState()!!
        assertEquals(3, finalState.iterationHistory.size)
        assertTrue(finalState.totalCostUsd > 4.0)
        assertEquals("sess-1", finalState.allSessionIds[0])
        assertEquals("sess-3", finalState.allSessionIds[2])

        // Verify persistence survived
        val loaded = RalphLoopState.load(File(tempDir, finalState.loopId))
        assertNotNull(loaded)
        assertEquals(RalphPhase.COMPLETED, loaded!!.phase)
    }

    @Test
    fun `iteration context includes feedback from prior iterations`() {
        val orchestrator = RalphLoopOrchestrator(ralphDir = tempDir)
        orchestrator.startLoop("Fix the bug", RalphLoopConfig(maxIterations = 5))

        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("Bug.kt"), completionSummary = "Fixed NPE",
            sessionId = "sess-1"
        )
        orchestrator.onReviewerResult(
            ReviewResult(ReviewVerdict.IMPROVE, "The fix doesn't handle the null case in line 42"),
            reviewerCostUsd = 0.20
        )

        val context = orchestrator.buildIterationContext()
        assertTrue(context.contains("Fix the bug"))
        assertTrue(context.contains("null case in line 42"))
        assertTrue(context.contains("iteration 2"))
        assertTrue(context.contains("<ralph_iteration>"))
    }

    @Test
    fun `scorecard computed correctly from completed loop`() {
        val orchestrator = RalphLoopOrchestrator(ralphDir = tempDir)
        orchestrator.startLoop("test", RalphLoopConfig(maxIterations = 5, reviewerEnabled = false))

        orchestrator.onIterationCompleted(
            costUsd = 1.0, tokensUsed = 50_000, durationMs = 10_000,
            filesChanged = listOf("a.kt"), completionSummary = "Done", sessionId = "s1"
        )
        // No reviewer → auto-continues
        orchestrator.onIterationCompleted(
            costUsd = 0.8, tokensUsed = 40_000, durationMs = 8_000,
            filesChanged = listOf("a.kt", "b.kt"), completionSummary = "Improved", sessionId = "s2"
        )

        val state = orchestrator.getCurrentState()!!
        val scorecard = RalphLoopScorecard.fromState(state)
        assertEquals(2, scorecard.totalIterations)
        assertEquals(2, scorecard.totalFilesModified)
        assertEquals(18_000, scorecard.totalDurationMs)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :agent:test --tests "*RalphLoop*" -v`
Expected: PASS (all ralph tests — state, reviewer, orchestrator, scorecard, integration)

- [ ] **Step 3: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/ralph/RalphLoopIntegrationTest.kt
git commit -m "test(agent): add Ralph Loop integration test — full lifecycle coverage"
```

---

### Task 11: Documentation Update

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `CLAUDE.md` (root)

- [ ] **Step 1: Add Ralph Loop section to agent/CLAUDE.md**

After the "## Evaluation & Observability" section, add:

```markdown
## Ralph Loop (Iterative Self-Improvement)

The agent supports an iterative self-improvement loop inspired by the Ralph Wiggum technique.
After the agent completes a task, it can automatically start a **new session** with the **same prompt**
and a **reviewer subagent** evaluates the work between iterations.

**Components:**
- `RalphLoopOrchestrator` — Core loop logic, state machine, auto-expand, stuck detection
- `RalphReviewer` — Reviewer prompt builder and response parser (ACCEPT/IMPROVE)
- `RalphLoopState` — Persistent state (JSON) at `~/.workflow-orchestrator/{proj}/agent/ralph/{loopId}/`
- `RalphLoopScorecard` — Cross-session metrics aggregation

**State machine:** EXECUTING → AWAITING_REVIEW → REVIEWING → COMPLETED | EXECUTING (loop)

**Safety:**
- `maxIterations` (default 10) + auto-expand (3 expansions, +5 each)
- `maxCostUsd` (default $10) checked before each iteration and reviewer
- 3 consecutive IMPROVEs without file changes → force-complete
- Crash recovery via persisted state + AgentStartupActivity detection
- LocalHistory checkpoints per iteration for rollback

**Activation:** `startRalphLoop()` in AgentController, settings defaults in AgentSettings.
```

- [ ] **Step 2: Add build command to root CLAUDE.md**

In the "Build & Verify" section, add:

```bash
./gradlew :agent:test --tests "*RalphLoop*"  # Ralph Loop tests
```

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md CLAUDE.md
git commit -m "docs: add Ralph Loop documentation to CLAUDE.md"
```
