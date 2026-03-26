# Ralph Loop Patterns Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate 4 structural patterns from the Ralph Loop technique into the agent's ReAct loop: learned guardrails, pre-edit search enforcement, backpressure gates, and context rotation.

**Architecture:** Each pattern is an independent component that plugs into existing infrastructure (`LoopGuard`, `ContextManager`, `SingleAgentSession`, `PromptAssembler`). GuardrailStore is the foundation — backpressure records to it. Pre-edit enforcement extends LoopGuard. Backpressure is a new interceptor in the ReAct loop. Context rotation enhances the TERMINATE path.

**Tech Stack:** Kotlin, JUnit 5, MockK, kotlinx.serialization

**Spec:** `docs/superpowers/specs/2026-03-26-ralph-loop-patterns-integration-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../context/GuardrailStore.kt` | Persistent guardrails: load/save/record/evict from `guardrails.md` |
| `agent/src/main/kotlin/.../runtime/BackpressureGate.kt` | Edit accumulator + verification nudge injector |
| `agent/src/main/kotlin/.../runtime/RotationState.kt` | Serializable context rotation state for session handoff |
| `agent/src/test/kotlin/.../context/GuardrailStoreTest.kt` | Tests for guardrail persistence, FIFO eviction, token cap |
| `agent/src/test/kotlin/.../runtime/BackpressureGateTest.kt` | Tests for edit counting, nudge injection, threshold config |
| `agent/src/test/kotlin/.../runtime/LoopGuardPreEditTest.kt` | Tests for pre-edit read enforcement |
| `agent/src/test/kotlin/.../runtime/ContextRotationTest.kt` | Tests for rotation state serialization and loading |

### Modified Files
| File | Changes |
|------|---------|
| `agent/.../context/ContextManager.kt` | Add `guardrailsAnchor` slot + `setGuardrailsAnchor()` method |
| `agent/.../runtime/LoopGuard.kt` | Add `checkPreEditRead()`, `guardrailStore` reference, auto-record on doom loop/circuit breaker |
| `agent/.../runtime/SingleAgentSession.kt` | Pre-edit gate before `edit_file`, backpressure integration after edits, `ContextRotated` result type, rotation flow at TERMINATE |
| `agent/.../orchestrator/PromptAssembler.kt` | Add `guardrailsContext` parameter, inject `<guardrails>` section |
| `agent/.../runtime/ConversationSession.kt` | Load guardrails in `create()`, pass to PromptAssembler |
| `agent/.../tools/builtin/SaveMemoryTool.kt` | Add optional `type` parameter (`memory` or `guardrail`) |
| `agent/.../ui/AgentController.kt` | Handle `ContextRotated` result, auto-start new session with rotated context |

---

### Task 1: GuardrailStore — Persistence and Loading

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/GuardrailStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.agent.context

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GuardrailStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: GuardrailStore

    @BeforeEach
    fun setup() {
        store = GuardrailStore(tempDir)
    }

    @Test
    fun `record adds constraint to store`() {
        store.record("Always re-read build.gradle.kts before editing — whitespace-sensitive syntax")
        assertEquals(1, store.size)
    }

    @Test
    fun `save persists to guardrails md`() {
        store.record("Avoid calling run_command with ./gradlew test — use module-specific test instead")
        store.save()

        val file = File(tempDir, ".workflow/agent/guardrails.md")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Avoid calling run_command"))
    }

    @Test
    fun `load reads from guardrails md`() {
        val dir = File(tempDir, ".workflow/agent")
        dir.mkdirs()
        File(dir, "guardrails.md").writeText(
            "# Agent Guardrails\n\n- Constraint one\n- Constraint two\n"
        )

        val loaded = GuardrailStore(tempDir)
        loaded.load()
        assertEquals(2, loaded.size)
    }

    @Test
    fun `toContextString renders guardrails tag`() {
        store.record("Rule one")
        store.record("Rule two")
        val ctx = store.toContextString()
        assertTrue(ctx.contains("<guardrails>"))
        assertTrue(ctx.contains("Rule one"))
        assertTrue(ctx.contains("Rule two"))
        assertTrue(ctx.contains("</guardrails>"))
    }

    @Test
    fun `FIFO eviction when max exceeded`() {
        val small = GuardrailStore(tempDir, maxConstraints = 3)
        small.record("First")
        small.record("Second")
        small.record("Third")
        small.record("Fourth")
        assertEquals(3, small.size)
        assertFalse(small.toContextString().contains("First"))
        assertTrue(small.toContextString().contains("Fourth"))
    }

    @Test
    fun `estimateTokens returns non-zero when constraints exist`() {
        store.record("A constraint")
        assertTrue(store.estimateTokens() > 0)
    }

    @Test
    fun `estimateTokens returns zero when empty`() {
        assertEquals(0, store.estimateTokens())
    }

    @Test
    fun `duplicate constraints are not added`() {
        store.record("Same constraint")
        store.record("Same constraint")
        assertEquals(1, store.size)
    }

    @Test
    fun `load from non-existent file returns empty`() {
        store.load()
        assertEquals(0, store.size)
    }

    @Test
    fun `save creates directory if needed`() {
        val nested = GuardrailStore(File(tempDir, "deep/nested"))
        nested.record("A rule")
        nested.save()
        assertTrue(File(tempDir, "deep/nested/.workflow/agent/guardrails.md").exists())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.GuardrailStoreTest" -v`
Expected: FAIL — `GuardrailStore` class does not exist

- [ ] **Step 3: Implement GuardrailStore**

```kotlin
package com.workflow.orchestrator.agent.context

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Persistent store for learned agent constraints.
 *
 * Constraints are discovered during agent sessions (doom loops, repeated failures)
 * or saved manually by the LLM. They persist to {projectBasePath}/.workflow/agent/guardrails.md
 * and are loaded into the system prompt at session start.
 *
 * The guardrails anchor in ContextManager ensures constraints survive context compression.
 *
 * THREAD SAFETY: Not thread-safe. Access from the ReAct loop's single coroutine context only.
 */
class GuardrailStore(
    private val projectBasePath: File,
    private val maxConstraints: Int = 50
) {
    companion object {
        private val LOG = Logger.getInstance(GuardrailStore::class.java)
        private const val GUARDRAILS_DIR = ".workflow/agent"
        private const val GUARDRAILS_FILE = "guardrails.md"
    }

    private val constraints = mutableListOf<String>()

    val size: Int get() = constraints.size

    /**
     * Record a new constraint. Duplicates are ignored. FIFO eviction when max exceeded.
     */
    fun record(constraint: String) {
        val trimmed = constraint.trim()
        if (trimmed.isBlank()) return
        if (trimmed in constraints) return

        constraints.add(trimmed)
        while (constraints.size > maxConstraints) constraints.removeAt(0)
        LOG.info("GuardrailStore: recorded constraint (${constraints.size}/$maxConstraints): ${trimmed.take(80)}")
    }

    /**
     * Load constraints from guardrails.md. Parses markdown list items (lines starting with "- ").
     */
    fun load() {
        try {
            val file = File(projectBasePath, "$GUARDRAILS_DIR/$GUARDRAILS_FILE")
            if (!file.exists()) return

            val lines = file.readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- ")) {
                    val constraint = trimmed.removePrefix("- ").trim()
                    if (constraint.isNotBlank() && constraint !in constraints) {
                        constraints.add(constraint)
                    }
                }
            }
            while (constraints.size > maxConstraints) constraints.removeAt(0)
            LOG.info("GuardrailStore: loaded ${constraints.size} constraints from ${file.path}")
        } catch (e: Exception) {
            LOG.warn("GuardrailStore: failed to load guardrails", e)
        }
    }

    /**
     * Save constraints to guardrails.md. Creates directory if needed.
     */
    fun save() {
        try {
            val dir = File(projectBasePath, GUARDRAILS_DIR)
            dir.mkdirs()
            val file = File(dir, GUARDRAILS_FILE)
            val content = buildString {
                appendLine("# Agent Guardrails")
                appendLine()
                for (c in constraints) {
                    appendLine("- $c")
                }
            }
            file.writeText(content)
            LOG.info("GuardrailStore: saved ${constraints.size} constraints to ${file.path}")
        } catch (e: Exception) {
            LOG.warn("GuardrailStore: failed to save guardrails", e)
        }
    }

    /**
     * Render constraints as a context string for injection into the LLM prompt.
     * Returns empty string if no constraints recorded.
     */
    fun toContextString(): String {
        if (constraints.isEmpty()) return ""
        return buildString {
            appendLine("<guardrails>")
            appendLine("Learned constraints from previous sessions (follow these strictly):")
            for (c in constraints) {
                appendLine("- $c")
            }
            appendLine("</guardrails>")
        }.trimEnd()
    }

    /**
     * Estimate token consumption of the current constraints context string.
     */
    fun estimateTokens(): Int {
        val s = toContextString()
        return if (s.isEmpty()) 0 else TokenEstimator.estimate(s)
    }

    /** Clear all constraints (for testing). */
    fun clear() {
        constraints.clear()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.GuardrailStoreTest" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/GuardrailStoreTest.kt
git commit -m "feat(agent): add GuardrailStore for persistent learned constraints"
```

---

### Task 2: Wire GuardrailStore into ContextManager and PromptAssembler

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt` (lines 100-107, 141-178, 192-195, 697-700)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt` (lines 31-73)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt` (lines 208-214, 256-260)

- [ ] **Step 1: Add guardrailsAnchor to ContextManager**

In `ContextManager.kt`, add a new anchor slot alongside the existing four:

After line 107 (`private var factsAnchor: ChatMessage? = null`), add:
```kotlin
    /** Dedicated guardrails anchor — compression-proof learned constraints. */
    private var guardrailsAnchor: ChatMessage? = null
```

Add a setter method after `updateFactsAnchor()` (after line 178):
```kotlin
    /**
     * Set or update the anchored guardrails context. Dedicated compression-proof slot
     * containing learned constraints from previous sessions.
     */
    fun setGuardrailsAnchor(message: ChatMessage?) {
        guardrailsAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }
```

In `getMessages()` (line 192-195), add `guardrailsAnchor` after `factsAnchor`:
```kotlin
        planAnchor?.let { result.add(it) }
        skillAnchor?.let { result.add(it) }
        mentionAnchor?.let { result.add(it) }
        factsAnchor?.let { result.add(it) }
        guardrailsAnchor?.let { result.add(it) }
```

In `reset()` (around line 697-700), add:
```kotlin
        guardrailsAnchor = null
```

- [ ] **Step 2: Add guardrailsContext parameter to PromptAssembler**

In `PromptAssembler.buildSingleAgentPrompt()` (line 31), add parameter:
```kotlin
    fun buildSingleAgentPrompt(
        projectName: String? = null,
        projectPath: String? = null,
        frameworkInfo: String? = null,
        previousStepResults: List<String>? = null,
        repoMapContext: String? = null,
        memoryContext: String? = null,
        guardrailsContext: String? = null,  // NEW
        skillDescriptions: String? = null,
        ...
    )
```

After the memory section (after line 73), add:
```kotlin
        // 5b. Learned Guardrails (only if available)
        if (!guardrailsContext.isNullOrBlank()) {
            sections.add(guardrailsContext)
        }
```

- [ ] **Step 3: Load guardrails in ConversationSession.create()**

In `ConversationSession.create()`, after the memory loading block (lines 208-214), add:
```kotlin
            // Load learned guardrails
            val guardrailsContext = try {
                val basePath = project.basePath
                if (basePath != null) {
                    val store = GuardrailStore(java.io.File(basePath))
                    store.load()
                    store.toContextString().ifBlank { null }
                } else null
            } catch (_: Exception) { null }
```

And pass it to `buildSingleAgentPrompt()` (around line 260):
```kotlin
                memoryContext = memoryContext,
                guardrailsContext = guardrailsContext,
                skillDescriptions = ...
```

Also, in `ConversationSession.create()`, after setting up `contextManager`, wire the guardrails anchor:
```kotlin
            if (!guardrailsContext.isNullOrBlank()) {
                contextManager.setGuardrailsAnchor(ChatMessage(role = "system", content = guardrailsContext))
            }
```

- [ ] **Step 4: Also wire guardrails in AgentOrchestrator.executeTask()**

In `AgentOrchestrator.executeTask()`, at the same point where `memoryContext` would be loaded (currently it's not loaded there — it goes through ConversationSession), check if `AgentOrchestrator` also calls `buildSingleAgentPrompt` directly. If so (line 198), load guardrails there too:

```kotlin
            val guardrailsContext = try {
                val basePath = project.basePath
                if (basePath != null) {
                    val store = GuardrailStore(java.io.File(basePath))
                    store.load()
                    store.toContextString().ifBlank { null }
                } else null
            } catch (_: Exception) { null }
```

Pass to `buildSingleAgentPrompt()`:
```kotlin
            val systemPrompt = promptAssembler.buildSingleAgentPrompt(
                projectName = project.name,
                projectPath = project.basePath,
                repoMapContext = repoMap.ifBlank { null },
                repoContext = repoContext,
                guardrailsContext = guardrailsContext
            )
```

- [ ] **Step 5: Compile and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(agent): wire GuardrailStore into ContextManager and PromptAssembler"
```

---

### Task 3: Auto-Record Guardrails from LoopGuard

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt` (where LoopGuard is constructed)

- [ ] **Step 1: Add guardrailStore to LoopGuard**

In `LoopGuard.kt`, add a constructor parameter and auto-record logic:

```kotlin
class LoopGuard(
    private val reminderIntervalIterations: Int = 4,
    /** Optional guardrail store for auto-recording failure patterns across sessions. */
    var guardrailStore: GuardrailStore? = null
) {
```

Add import:
```kotlin
import com.workflow.orchestrator.agent.context.GuardrailStore
```

- [ ] **Step 2: Auto-record on doom loop detection**

In `checkDoomLoop()`, after the doom loop detection block (line 116-121), before clearing and returning, add auto-recording:

```kotlin
        // Doom loop: last N calls identical
        if (recentDoomCalls.size >= DOOM_LOOP_THRESHOLD) {
            val lastN = recentDoomCalls.takeLast(DOOM_LOOP_THRESHOLD)
            if (lastN.distinct().size == 1) {
                recentDoomCalls.clear()
                // Auto-record guardrail from doom loop
                guardrailStore?.record(
                    "Avoid calling $toolName with repeated identical arguments — causes doom loops. Try a different approach or tool."
                )
                return "You have called $toolName with the same arguments $DOOM_LOOP_THRESHOLD times in a row. Try a different approach or summarize your findings."
            }
        }
```

- [ ] **Step 3: Auto-record on edit-file re-read warning**

In `checkDoomLoop()`, in the `read_file` tracking block (line 104-112), where it detects a re-read, this is informational, not an error — no guardrail recording needed here. Skip this.

- [ ] **Step 4: Wire guardrailStore into SingleAgentSession**

In `SingleAgentSession.kt`, after the circuit breaker check (around line 738-742 and 819-822), add auto-recording:

For both parallel and sequential tool result processing, after `metrics.isCircuitBroken(toolName)`:
```kotlin
                if (metrics.isCircuitBroken(toolName)) {
                    contextManager.addSystemMessage(
                        "Circuit breaker: '$toolName' has failed ${AgentMetrics.CIRCUIT_BREAKER_THRESHOLD} consecutive times. Try a different approach or tool."
                    )
                    // Auto-record to guardrails
                    loopGuard.guardrailStore?.record(
                        "Tool '$toolName' frequently fails in this project — consider alternative approaches"
                    )
                }
```

- [ ] **Step 5: Save guardrails at session end**

In `SingleAgentSession.execute()`, before returning `SingleAgentResult.Completed` (around line 661) and before returning `SingleAgentResult.Failed` (around line 268), save guardrails:

```kotlin
            // Persist any learned guardrails
            loopGuard.guardrailStore?.save()
```

- [ ] **Step 6: Run existing LoopGuard tests to ensure no regression**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.LoopGuardTest" -v`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): auto-record guardrails from doom loops and circuit breakers"
```

---

### Task 4: Extend SaveMemoryTool with Guardrail Type

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryTool.kt`

- [ ] **Step 1: Add type parameter to SaveMemoryTool**

```kotlin
class SaveMemoryTool : AgentTool {
    override val name = "save_memory"
    override val description = "Save a project-specific learning for future sessions. Use when you discover something worth remembering: build quirks, API behaviors, project conventions, debugging insights, user preferences. Set type='guardrail' to save as a constraint that the agent must follow in all future sessions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "topic" to ParameterProperty(type = "string", description = "Short topic name (e.g., 'build-config', 'api-quirks', 'testing-patterns')"),
            "content" to ParameterProperty(type = "string", description = "The learning to remember. Be concise but specific."),
            "type" to ParameterProperty(type = "string", description = "Type of memory: 'memory' (default) for general learnings, 'guardrail' for constraints the agent must follow. Guardrails are loaded with higher priority and survive context compression.", enum = listOf("memory", "guardrail"))
        ),
        required = listOf("topic", "content")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val topic = params["topic"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'topic' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val type = params["type"]?.jsonPrimitive?.content ?: "memory"
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (type == "guardrail") {
            val store = GuardrailStore(File(basePath))
            store.load()
            store.record(content)
            store.save()
            return ToolResult(
                content = "Guardrail saved: '$topic'. This constraint will be enforced in all future sessions.",
                summary = "Saved guardrail: $topic",
                tokenEstimate = 5
            )
        }

        AgentMemoryStore(File(basePath)).saveMemory(topic, content)
        return ToolResult(
            content = "Memory saved: '$topic'. This will be available in future sessions.",
            summary = "Saved memory: $topic",
            tokenEstimate = 5
        )
    }
}
```

Add import:
```kotlin
import com.workflow.orchestrator.agent.context.GuardrailStore
```

- [ ] **Step 2: Compile and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SaveMemoryTool.kt
git commit -m "feat(agent): extend save_memory tool with guardrail type"
```

---

### Task 5: Pre-Edit Search Enforcement

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuardPreEditTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoopGuardPreEditTest {

    private lateinit var guard: LoopGuard

    @BeforeEach
    fun setup() {
        guard = LoopGuard()
    }

    @Test
    fun `edit blocked when file not read`() {
        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
        assertTrue(warning!!.contains("Edit blocked"))
        assertTrue(warning.contains("Main.kt"))
    }

    @Test
    fun `edit allowed after file read`() {
        // Simulate reading the file via checkDoomLoop (which tracks reads)
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")

        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNull(warning)
    }

    @Test
    fun `edit blocked after clearFileRead`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.clearFileRead("/src/Main.kt")

        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
    }

    @Test
    fun `edit blocked after clearAllFileReads`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.clearAllFileReads()

        val warning = guard.checkPreEditRead("/src/Main.kt")
        assertNotNull(warning)
    }

    @Test
    fun `different file not affected by reading another`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")

        val warning = guard.checkPreEditRead("/src/Other.kt")
        assertNotNull(warning)
    }

    @Test
    fun `edit allowed after reset and re-read`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.reset()
        // Not read after reset
        assertNotNull(guard.checkPreEditRead("/src/Main.kt"))
        // Read again
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        assertNull(guard.checkPreEditRead("/src/Main.kt"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.LoopGuardPreEditTest" -v`
Expected: FAIL — `checkPreEditRead` does not exist

- [ ] **Step 3: Add checkPreEditRead to LoopGuard**

In `LoopGuard.kt`, add after `clearAllFileReads()` (after line 140):

```kotlin
    /**
     * Check if a file was read in this session before allowing an edit.
     * Returns an error message if the file hasn't been read, or null if safe to proceed.
     *
     * This is a hard gate — the edit tool should return this as an error to the LLM.
     */
    fun checkPreEditRead(filePath: String): String? {
        if (filePath in readFiles) return null
        return "Edit blocked: you haven't read '$filePath' in this session. Read the file first to see its current content, then retry the edit. This prevents blind edits with incorrect old_string values."
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.LoopGuardPreEditTest" -v`
Expected: ALL PASS

- [ ] **Step 5: Wire pre-edit gate into SingleAgentSession**

In `SingleAgentSession.kt`, in the write tools sequential execution loop (around line 774-775), before `executeSingleToolRaw`, add the pre-edit gate:

```kotlin
        // Execute write tools sequentially
        for (toolCall in activeWriteCalls) {
            if (cancelled.get()) {
                contextManager.addToolResult(toolCall.id, "Cancelled by user", "Cancelled")
                break
            }

            val toolName = toolCall.function.name

            // Pre-edit search enforcement: block edit_file if file not read in this session
            if (toolName == "edit_file") {
                val editPathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(toolCall.function.arguments)
                val editPath = editPathMatch?.groupValues?.get(1)
                if (editPath != null) {
                    val preEditWarning = loopGuard.checkPreEditRead(editPath)
                    if (preEditWarning != null) {
                        contextManager.addToolResult(toolCall.id, preEditWarning, "Edit blocked: file not read")
                        toolResults.add(toolCall.id to true)
                        onProgress(AgentProgress(
                            step = "Edit blocked: $editPath not read yet",
                            tokensUsed = contextManager.currentTokens
                        ))
                        continue  // Skip to next tool call
                    }
                }
            }

            val (_, toolResult, toolDurationMs) = executeSingleToolRaw(toolCall, tools, project, approvalGate, eventLog, sessionTrace, onProgress)
```

- [ ] **Step 6: Run full LoopGuard test suite**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.LoopGuard*" -v`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuard.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/LoopGuardPreEditTest.kt
git commit -m "feat(agent): enforce pre-edit file read gate in LoopGuard"
```

---

### Task 6: BackpressureGate

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGate.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGateTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BackpressureGateTest {

    private lateinit var gate: BackpressureGate

    @BeforeEach
    fun setup() {
        gate = BackpressureGate(editThreshold = 3)
    }

    @Test
    fun `no nudge below threshold`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        val nudge = gate.checkAndGetNudge()
        assertNull(nudge)
    }

    @Test
    fun `nudge at threshold`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        val nudge = gate.checkAndGetNudge()
        assertNotNull(nudge)
        assertTrue(nudge!!.content.contains("diagnostics"))
        assertTrue(nudge.content.contains("A.kt"))
    }

    @Test
    fun `counter resets after nudge acknowledged`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge() // consume the nudge
        gate.acknowledgeVerification()
        // Counter should be reset
        assertNull(gate.checkAndGetNudge())
    }

    @Test
    fun `strong nudge when verification not performed`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge() // first nudge
        // Agent didn't run verification, made another edit
        gate.recordEdit("/src/D.kt")
        val strong = gate.checkAndGetNudge()
        assertNotNull(strong)
        assertTrue(strong!!.content.contains("REQUIRED"))
    }

    @Test
    fun `test failure generates backpressure error`() {
        val error = gate.createBackpressureError(
            toolName = "run_tests",
            errorOutput = "FAILED: testAuth — Expected 200 but got 401"
        )
        assertTrue(error.content.contains("<backpressure_error>"))
        assertTrue(error.content.contains("testAuth"))
    }

    @Test
    fun `disabled when threshold is zero`() {
        val disabled = BackpressureGate(editThreshold = 0)
        disabled.recordEdit("/src/A.kt")
        disabled.recordEdit("/src/B.kt")
        disabled.recordEdit("/src/C.kt")
        assertNull(disabled.checkAndGetNudge())
    }

    @Test
    fun `verification tools acknowledged`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        assertTrue(gate.isVerificationPending())
        gate.acknowledgeVerification()
        assertFalse(gate.isVerificationPending())
    }

    @Test
    fun `reset clears all state`() {
        gate.recordEdit("/src/A.kt")
        gate.recordEdit("/src/B.kt")
        gate.recordEdit("/src/C.kt")
        gate.checkAndGetNudge()
        gate.reset()
        assertFalse(gate.isVerificationPending())
        assertNull(gate.checkAndGetNudge())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.BackpressureGateTest" -v`
Expected: FAIL — `BackpressureGate` class does not exist

- [ ] **Step 3: Implement BackpressureGate**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.ChatMessage

/**
 * Tracks file edits and injects verification nudges when the agent modifies code
 * without checking for errors.
 *
 * After [editThreshold] edits without running diagnostics/tests, injects a nudge
 * asking the agent to verify. If the agent ignores the nudge and makes more edits,
 * escalates to a stronger message.
 *
 * Set [editThreshold] to 0 to disable backpressure.
 */
class BackpressureGate(
    private val editThreshold: Int = 3
) {
    companion object {
        /** Tools that count as verification (reset the edit counter). */
        val VERIFICATION_TOOLS = setOf("diagnostics", "run_tests", "run_inspections", "compile_module")
    }

    private val pendingEdits = mutableListOf<String>()
    private var nudgeEmitted = false
    private var verificationPending = false

    /**
     * Record a successful file edit.
     */
    fun recordEdit(filePath: String) {
        pendingEdits.add(filePath)
    }

    /**
     * Check if a verification nudge should be injected.
     * Returns a system message to inject, or null if no nudge needed.
     */
    fun checkAndGetNudge(): ChatMessage? {
        if (editThreshold <= 0) return null
        if (pendingEdits.size < editThreshold) return null

        val fileList = pendingEdits.distinct().joinToString(", ")

        if (nudgeEmitted && verificationPending) {
            // Agent ignored the first nudge — escalate
            return ChatMessage(
                role = "system",
                content = "REQUIRED: You have edited files without running verification. Run diagnostics or run_tests on modified files before making more changes: $fileList"
            )
        }

        nudgeEmitted = true
        verificationPending = true
        return ChatMessage(
            role = "system",
            content = "Backpressure gate: you've edited ${pendingEdits.size} files ($fileList). Run diagnostics on these files before continuing to catch errors early."
        )
    }

    /**
     * Called when the agent runs a verification tool. Resets the edit counter.
     */
    fun acknowledgeVerification() {
        pendingEdits.clear()
        nudgeEmitted = false
        verificationPending = false
    }

    /**
     * Check if verification is currently pending (nudge was emitted but not acknowledged).
     */
    fun isVerificationPending(): Boolean = verificationPending

    /**
     * Create a structured backpressure error message from a test/build failure.
     */
    fun createBackpressureError(toolName: String, errorOutput: String): ChatMessage {
        val trimmedError = errorOutput.take(2000)
        return ChatMessage(
            role = "system",
            content = "<backpressure_error>\n$toolName failed. Errors:\n$trimmedError\nFix these errors before proceeding to the next plan step.\n</backpressure_error>"
        )
    }

    /** Reset all state. */
    fun reset() {
        pendingEdits.clear()
        nudgeEmitted = false
        verificationPending = false
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.BackpressureGateTest" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGate.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/BackpressureGateTest.kt
git commit -m "feat(agent): add BackpressureGate for verify-after-edit enforcement"
```

---

### Task 7: Wire BackpressureGate into SingleAgentSession

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 1: Add BackpressureGate as a field**

In `SingleAgentSession`, add a field alongside `loopGuard`:

```kotlin
class SingleAgentSession(
    private val maxIterations: Int = 50,
    val cancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
    val metrics: AgentMetrics = AgentMetrics(),
    private val agentFileLogger: AgentFileLogger? = null
) {
    private var consecutiveMalformedRetries = 0
    private var forceTextOnly = false
```

Inside `execute()`, where `loopGuard` is used (it's likely created locally), also create BackpressureGate. Find where `val loopGuard = LoopGuard()` or similar is, and add:

```kotlin
        val backpressureGate = BackpressureGate(
            editThreshold = 3  // TODO: read from AgentSettings when settings UI is added
        )
```

- [ ] **Step 2: Record edits in BackpressureGate**

In the sequential write tool execution loop, after the existing edit tracking block (around line 796-806):

```kotlin
            // Track edited files for LoopGuard auto-verification
            if (toolName == "edit_file" && !toolResult.isError) {
                editedFiles.addAll(toolResult.artifacts)
                // ... existing clearFileRead logic ...
                // Record in backpressure gate
                toolResult.artifacts.firstOrNull()?.let { backpressureGate.recordEdit(it) }
            }
```

- [ ] **Step 3: Acknowledge verification tools**

In both the parallel and sequential tool execution paths, after tool result processing, check if the tool is a verification tool:

```kotlin
            // Acknowledge verification tools in backpressure gate
            if (toolName in BackpressureGate.VERIFICATION_TOOLS && !toolResult.isError) {
                backpressureGate.acknowledgeVerification()
            }
```

- [ ] **Step 4: Inject backpressure nudges in afterIteration**

After the `loopGuard.afterIteration(...)` call (which returns injections), add backpressure nudge:

```kotlin
        val loopInjections = loopGuard.afterIteration(toolCalls, toolResults, editedFiles)
        for (msg in loopInjections) {
            contextManager.addMessage(msg)
        }

        // Backpressure gate: nudge after N edits without verification
        val backpressureNudge = backpressureGate.checkAndGetNudge()
        if (backpressureNudge != null) {
            contextManager.addMessage(backpressureNudge)
        }
```

- [ ] **Step 5: Inject backpressure error on test/build failures**

In the sequential tool execution, after processing `run_command` or `run_tests` results that have errors:

```kotlin
            // Backpressure error on test/build failures
            if (toolResult.isError && toolName in setOf("run_command", "run_tests", "compile_module")) {
                val bpError = backpressureGate.createBackpressureError(toolName, toolResult.content)
                contextManager.addMessage(bpError)
            }
```

- [ ] **Step 6: Compile and run all agent tests**

Run: `./gradlew :agent:compileKotlin && ./gradlew :agent:test -v`
Expected: BUILD SUCCESSFUL, ALL TESTS PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): wire BackpressureGate into ReAct loop"
```

---

### Task 8: Context Rotation — RotationState and Serialization

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/RotationState.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ContextRotationTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextRotationTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `RotationState serializes to JSON`() {
        val state = RotationState(
            goal = "Refactor auth module",
            accomplishments = "Completed steps 1-3: extracted interface, moved to core, updated tests",
            remainingWork = "Steps 4-5: wire new service in agent tools, update system prompt",
            modifiedFiles = listOf("core/AuthService.kt", "agent/tools/AuthTool.kt"),
            guardrails = listOf("Always re-read AuthService.kt before editing"),
            factsSnapshot = listOf("AuthService moved to core/services/", "3 tests updated in agent module")
        )

        val jsonStr = json.encodeToString(RotationState.serializer(), state)
        assertTrue(jsonStr.contains("Refactor auth module"))
        assertTrue(jsonStr.contains("AuthService.kt"))
    }

    @Test
    fun `RotationState round-trips through JSON`() {
        val state = RotationState(
            goal = "Fix build",
            accomplishments = "Found the issue",
            remainingWork = "Apply fix",
            modifiedFiles = listOf("build.gradle.kts"),
            guardrails = emptyList(),
            factsSnapshot = listOf("Build fails on missing dependency")
        )

        val jsonStr = json.encodeToString(RotationState.serializer(), state)
        val loaded = json.decodeFromString(RotationState.serializer(), jsonStr)
        assertEquals(state.goal, loaded.goal)
        assertEquals(state.modifiedFiles, loaded.modifiedFiles)
    }

    @Test
    fun `save and load rotation state from disk`() {
        val state = RotationState(
            goal = "Add caching layer",
            accomplishments = "Designed cache interface",
            remainingWork = "Implement Redis adapter",
            modifiedFiles = listOf("core/Cache.kt"),
            guardrails = listOf("Use suspend functions for cache operations"),
            factsSnapshot = emptyList()
        )

        RotationState.save(state, tempDir)
        val loaded = RotationState.load(tempDir)
        assertNotNull(loaded)
        assertEquals("Add caching layer", loaded!!.goal)
        assertEquals(listOf("core/Cache.kt"), loaded.modifiedFiles)
    }

    @Test
    fun `load returns null when file does not exist`() {
        val loaded = RotationState.load(tempDir)
        assertNull(loaded)
    }

    @Test
    fun `toContextString renders rotated context tag`() {
        val state = RotationState(
            goal = "Fix auth",
            accomplishments = "Extracted service",
            remainingWork = "Wire into agent",
            modifiedFiles = listOf("AuthService.kt"),
            guardrails = listOf("Re-read before edit"),
            factsSnapshot = listOf("Auth uses Bearer tokens")
        )

        val ctx = state.toContextString()
        assertTrue(ctx.contains("<rotated_context>"))
        assertTrue(ctx.contains("Fix auth"))
        assertTrue(ctx.contains("Extracted service"))
        assertTrue(ctx.contains("Wire into agent"))
        assertTrue(ctx.contains("AuthService.kt"))
        assertTrue(ctx.contains("Re-read before edit"))
        assertTrue(ctx.contains("</rotated_context>"))
    }

    @Test
    fun `toContextString truncated to token limit`() {
        val longFacts = (1..500).map { "Fact number $it with extra padding to consume tokens" }
        val state = RotationState(
            goal = "Big task",
            accomplishments = "Done a lot",
            remainingWork = "Still more",
            modifiedFiles = emptyList(),
            guardrails = emptyList(),
            factsSnapshot = longFacts
        )
        val ctx = state.toContextString(maxTokens = 500)
        // Should be capped — check it doesn't include all 500 facts
        val factCount = ctx.lines().count { it.startsWith("- Fact number") }
        assertTrue(factCount < 500)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ContextRotationTest" -v`
Expected: FAIL — `RotationState` class does not exist

- [ ] **Step 3: Implement RotationState**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.TokenEstimator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Externalized agent state for context rotation.
 *
 * When the context budget is exhausted, the agent serializes its working state
 * to this structure, which is loaded into a new session's system prompt.
 * This enables graceful handoff instead of abrupt termination.
 */
@Serializable
data class RotationState(
    val goal: String,
    val accomplishments: String,
    val remainingWork: String,
    val modifiedFiles: List<String>,
    val guardrails: List<String>,
    val factsSnapshot: List<String>
) {
    companion object {
        private const val ROTATION_FILE = "rotation-state.json"
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        fun save(state: RotationState, sessionDir: File) {
            sessionDir.mkdirs()
            File(sessionDir, ROTATION_FILE).writeText(json.encodeToString(serializer(), state))
        }

        fun load(sessionDir: File): RotationState? {
            val file = File(sessionDir, ROTATION_FILE)
            if (!file.exists()) return null
            return json.decodeFromString(serializer(), file.readText())
        }
    }

    /**
     * Render as a context string for injection into a new session's system prompt.
     * Truncates if the result exceeds [maxTokens].
     */
    fun toContextString(maxTokens: Int = 10000): String {
        val full = buildString {
            appendLine("<rotated_context>")
            appendLine("This session continues work from a previous session whose context was full.")
            appendLine()
            appendLine("## Goal")
            appendLine(goal)
            appendLine()
            appendLine("## Accomplished")
            appendLine(accomplishments)
            appendLine()
            appendLine("## Remaining Work")
            appendLine(remainingWork)
            if (modifiedFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Modified Files")
                for (f in modifiedFiles) appendLine("- $f")
            }
            if (guardrails.isNotEmpty()) {
                appendLine()
                appendLine("## Guardrails")
                for (g in guardrails) appendLine("- $g")
            }
            if (factsSnapshot.isNotEmpty()) {
                appendLine()
                appendLine("## Key Facts")
                for (f in factsSnapshot) appendLine("- $f")
            }
            appendLine("</rotated_context>")
        }.trimEnd()

        // Truncate if exceeds token budget
        if (TokenEstimator.estimate(full) <= maxTokens) return full

        // Progressive truncation: drop facts first, then guardrails
        val truncated = buildString {
            appendLine("<rotated_context>")
            appendLine("This session continues work from a previous session whose context was full.")
            appendLine()
            appendLine("## Goal")
            appendLine(goal)
            appendLine()
            appendLine("## Accomplished")
            appendLine(accomplishments)
            appendLine()
            appendLine("## Remaining Work")
            appendLine(remainingWork)
            if (modifiedFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Modified Files")
                for (f in modifiedFiles) appendLine("- $f")
            }
            if (guardrails.isNotEmpty()) {
                appendLine()
                appendLine("## Guardrails")
                for (g in guardrails) appendLine("- $g")
            }
            appendLine()
            appendLine("(Facts truncated for token budget)")
            appendLine("</rotated_context>")
        }.trimEnd()

        return truncated
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ContextRotationTest" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/RotationState.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ContextRotationTest.kt
git commit -m "feat(agent): add RotationState for context rotation handoff"
```

---

### Task 9: Wire Context Rotation into SingleAgentSession and AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Add ContextRotated result type**

In `SingleAgentSession.kt`, add to the `sealed class SingleAgentResult` (after `Failed`, around line 46-51):

```kotlin
    /** Context exhausted but state externalized for rotation to a new session. */
    data class ContextRotated(
        val summary: String,
        val rotationStatePath: String,
        val tokensUsed: Int
    ) : SingleAgentResult()
```

- [ ] **Step 2: Replace TERMINATE handler with rotation logic**

In `SingleAgentSession.execute()`, replace the `BudgetEnforcer.BudgetStatus.TERMINATE` block (lines 262-272):

```kotlin
                BudgetEnforcer.BudgetStatus.TERMINATE -> {
                    LOG.warn("SingleAgentSession: budget exhausted at iteration $iteration (${budgetEnforcer.utilizationPercent()}%)")
                    eventLog?.log(AgentEventType.SESSION_FAILED, "Budget terminated at ${budgetEnforcer.utilizationPercent()}%")
                    sessionTrace?.sessionFailed("Budget terminated at ${budgetEnforcer.utilizationPercent()}%", totalTokensUsed, iteration)

                    // Attempt context rotation if we have structured state to hand off
                    val planManager = try { agentService.currentPlanManager } catch (_: Exception) { null }
                    val currentPlan = planManager?.currentPlan

                    if (currentPlan != null && sessionDir != null) {
                        // Build rotation state from current context
                        val accomplishments = currentPlan.steps
                            .filter { it.status == "done" }
                            .joinToString("; ") { it.title }
                        val remaining = currentPlan.steps
                            .filter { it.status != "done" }
                            .joinToString("; ") { it.title }
                        val guardrails = loopGuard.guardrailStore?.let { store ->
                            // Read constraints as list
                            store.toContextString().lines()
                                .filter { it.startsWith("- ") }
                                .map { it.removePrefix("- ") }
                        } ?: emptyList()
                        val facts = contextManager.factsStore?.let { store ->
                            store.toContextString().lines()
                                .filter { it.startsWith("- ") }
                                .map { it.removePrefix("- ") }
                        } ?: emptyList()

                        val rotationState = RotationState(
                            goal = currentPlan.goal,
                            accomplishments = accomplishments.ifBlank { "In progress" },
                            remainingWork = remaining.ifBlank { "Unknown — check plan" },
                            modifiedFiles = editedFiles.toList(),
                            guardrails = guardrails,
                            factsSnapshot = facts
                        )
                        RotationState.save(rotationState, sessionDir)

                        val summary = "Context full (${budgetEnforcer.utilizationPercent()}%). " +
                            "Accomplished: $accomplishments. Remaining: $remaining."

                        loopGuard.guardrailStore?.save()
                        agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = "Context rotated")

                        return SingleAgentResult.ContextRotated(
                            summary = summary,
                            rotationStatePath = File(sessionDir, "rotation-state.json").absolutePath,
                            tokensUsed = totalTokensUsed
                        )
                    }

                    // No plan — fall back to hard failure
                    val budgetTerminateError = "Context budget exhausted at ${budgetEnforcer.utilizationPercent()}%. Please start a new conversation for remaining work."
                    agentFileLogger?.logSessionEnd(sessionId, iteration, totalTokensUsed, System.currentTimeMillis() - sessionStartMs, error = budgetTerminateError)
                    loopGuard.guardrailStore?.save()
                    return SingleAgentResult.Failed(
                        error = budgetTerminateError,
                        tokensUsed = totalTokensUsed
                    )
                }
```

Note: `sessionDir` and `agentService` need to be accessible in `execute()`. Check if they're passed as parameters or available via the session context. If `sessionDir` is not available, it can be passed as a parameter to `execute()`, or accessed via the `ConversationSession` that owns this `SingleAgentSession`.

Add import:
```kotlin
import java.io.File
```

- [ ] **Step 3: Handle ContextRotated in AgentController**

In `AgentController`, find where `SingleAgentResult` is handled (in the `when` block after `executeTask()`). Add the `ContextRotated` case:

```kotlin
            is SingleAgentResult.ContextRotated -> {
                dashboard.appendStatus(
                    "Context full — rotating to fresh session. ${result.summary}",
                    RichStreamingPanel.StatusType.WARNING
                )
                // Auto-start new session with rotation state
                val rotationState = RotationState.load(File(result.rotationStatePath).parentFile)
                if (rotationState != null) {
                    // Start new conversation with rotation context
                    handleNewSession()
                    val rotatedContext = rotationState.toContextString()
                    // Inject rotation context into the new session's first message
                    scope.launch {
                        delay(500) // Wait for session setup
                        handleStartTask(
                            "Continue the previous task. Context from previous session:\n\n${rotatedContext}\n\nResume from where the previous session left off.",
                            autoApprove = sessionAutoApprove
                        )
                    }
                } else {
                    dashboard.appendStatus(
                        "Could not load rotation state. Please start a new conversation manually.",
                        RichStreamingPanel.StatusType.ERROR
                    )
                }
            }
```

Add imports:
```kotlin
import com.workflow.orchestrator.agent.runtime.RotationState
import com.workflow.orchestrator.agent.runtime.SingleAgentResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
```

- [ ] **Step 4: Compile and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire context rotation into TERMINATE path and AgentController"
```

---

### Task 10: Update CLAUDE.md and Documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `CLAUDE.md` (root)

- [ ] **Step 1: Update agent/CLAUDE.md**

Add a new section after "## Error Handling":

```markdown
## Ralph Loop Patterns

Four structural patterns integrated from the Ralph Loop technique:

1. **Learned Guardrails** — `GuardrailStore` persists failure patterns to `.workflow/agent/guardrails.md`. Auto-recorded from doom loops and circuit breakers. Manually recorded via `save_memory(type="guardrail")`. Loaded into system prompt and compression-proof `guardrailsAnchor`.

2. **Pre-Edit Search Enforcement** — `LoopGuard.checkPreEditRead()` hard-gates `edit_file` calls. If the file hasn't been read in the current session, the edit returns an error forcing the LLM to read first. Always on, not configurable.

3. **Backpressure Gates** — `BackpressureGate` tracks edits and injects verification nudges after every N edits (default 3). Escalates to stronger nudge if ignored. Test/build failures generate structured `<backpressure_error>` feedback.

4. **Context Rotation** — When budget hits TERMINATE (97%), instead of hard-failing, externalizes state to `rotation-state.json` (goal, accomplishments, remaining work, files, guardrails, facts) and returns `ContextRotated`. AgentController auto-starts a new session with `<rotated_context>` injected. Only works when an active plan exists.
```

Also update the "Key Components" table to mention `GuardrailStore`, `BackpressureGate`, and `RotationState`.

- [ ] **Step 2: Update root CLAUDE.md if needed**

No root CLAUDE.md changes needed — these are internal to `:agent` module.

- [ ] **Step 3: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): document Ralph Loop patterns integration"
```

---

### Task 11: Final Integration Test

**Files:**
- Run all agent tests

- [ ] **Step 1: Run full agent test suite**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache -v`
Expected: ALL PASS (should be ~470+ tests)

- [ ] **Step 2: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: If any failures, fix and re-run**

Fix compilation or test failures, then re-run the specific failing tests.

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "fix(agent): integration fixes for Ralph Loop patterns"
```
