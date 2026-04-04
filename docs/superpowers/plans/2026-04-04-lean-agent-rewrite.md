# Lean Agent Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 211-file, 48K-LOC over-engineered agent with a simple ~15-file ReAct loop (~1,500 LOC new code) modeled after Codex CLI and Claude Code.

**Architecture:** Simple ReAct loop (call LLM → parse response → execute tools → repeat) with threshold-based context compaction, explicit `attempt_completion` tool (Cline pattern — prevents premature stopping), and flat tool registry. No gates, no guards, no event sourcing, no anchors, no dynamic tool selection.

**Tech Stack:** Kotlin 2.1.10, kotlinx.coroutines, kotlinx.serialization, IntelliJ Platform SDK

---

## Why This Rewrite

The current agent responds with empty content, no tool calls, `finish_reason: stop` after <10 iterations. Root cause: context pollution from 6 gate systems, 4-stage condenser pipeline, 18+ prompt sections, 5 layers of tool selection, and injected guard messages that confuse the LLM. The fix is radical simplification, copying patterns proven across 13+ production tools.

**Patterns copied from:**
- **Codex CLI**: `bytes/4` token estimation, auto-compact at 90%, output middle-truncation
- **Claude Code**: Auto-compaction at 83.5%, simple tool schemas, tight loop
- **Cline**: `attempt_completion` explicit completion tool — response with no tool calls is an ERROR, not completion

## File Structure After Rewrite

**NEW files (~1,500 LOC):**
```
agent/src/main/kotlin/com/workflow/orchestrator/agent/
├── loop/
│   ├── AgentLoop.kt              -- Core ReAct loop
│   ├── ContextManager.kt         -- Message list + compaction
│   └── LoopResult.kt             -- Sealed result class
├── prompt/
│   └── SystemPrompt.kt           -- Simple prompt builder
├── session/
│   ├── Session.kt                -- Session state
│   └── SessionStore.kt           -- JSONL persistence
```

**REWRITTEN:**
```
├── AgentService.kt               -- Simplified service entry point
├── tools/AgentTool.kt            -- Add WorkerType + move imports
├── tools/ToolRegistry.kt         -- Flat registration
├── tools/builtin/AttemptCompletionTool.kt -- Remove gatekeeper
├── ui/AgentController.kt         -- Simplified UI bridge
```

**MOVED (to preserve tool dependencies):**
```
├── tools/process/ProcessRegistry.kt -- From runtime/ (used by RunCommand/KillProcess/SendStdin)
```

**KEPT AS-IS:** `tools/{builtin,ide,integration,framework,psi,vcs,debug,config,database}/`, `api/dto/`, `ui/{AgentCefPanel,AgentDashboardPanel,...}`, `settings/`, `security/`, `listeners/`, `util/`

**DELETED (33K+ LOC):**
- `context/` (28 files) — EventStore, CondenserPipeline, Anchors, ConversationMemory
- `runtime/` (38 files) — SingleAgentSession, WorkerSession, all gates/guards
- `orchestrator/` — PromptAssembler, AgentOrchestrator, OrchestratorPrompts
- `ralph/` — Ralph Loop
- `service/` — SkillManager, GlobalSessionIndex
- `brain/` — Model selection
- `database/` (session DB, NOT the database tools)
- 13 builtin tools deeply tied to deleted infrastructure

---

## Task 1: Relocate Shared Types

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolRegistry.kt`

Before deleting packages, move types that kept tools depend on.

- [ ] **Step 1: Move WorkerType into AgentTool.kt**

The `WorkerType` enum is used by every tool's `allowedWorkers`. Move it into `AgentTool.kt` so it survives the `runtime/` deletion.

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt`, add the enum at the bottom and remove the runtime import:

```kotlin
package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import kotlinx.serialization.json.JsonObject

interface AgentTool {
    val name: String
    val description: String
    val parameters: FunctionParameters
    val allowedWorkers: Set<WorkerType>

    suspend fun execute(params: JsonObject, project: Project): ToolResult

    fun toToolDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    )
}

data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false,
    val isCompletion: Boolean = false,
    val verifyCommand: String? = null
) {
    companion object {
        const val ERROR_TOKEN_ESTIMATE = 5
    }
}

/** Worker type for tool access control. Kept for compatibility with existing tools. */
enum class WorkerType {
    ORCHESTRATOR, ANALYZER, CODER, REVIEWER, TOOLER
}

/** Simple token estimation: bytes / 4 (Codex CLI pattern, ~80% accurate). */
fun estimateTokens(text: String): Int = (text.toByteArray().size + 3) / 4

/**
 * Middle-truncate content keeping first 60% and last 40%.
 * Preserves error messages and summaries that appear at end of output.
 * Pattern from Codex CLI (10K default output limit).
 */
fun truncateOutput(content: String, maxChars: Int = 50_000): String {
    if (content.length <= maxChars) return content
    val headChars = (maxChars * 0.6).toInt()
    val tailChars = maxChars - headChars - 200
    val omitted = content.length - headChars - tailChars
    return content.take(headChars) +
        "\n\n[... $omitted characters omitted ...]\n\n" +
        content.takeLast(tailChars)
}
```

- [ ] **Step 2: Copy ProcessRegistry to tools/process/**

Copy `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ProcessRegistry.kt` to `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/ProcessRegistry.kt` and update the package declaration:

```kotlin
package com.workflow.orchestrator.agent.tools.process
// ... rest of file identical, just change package
```

Also copy `ManagedProcess` (it's in the same file).

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/process/
git commit -m "refactor(agent): relocate WorkerType, TokenEstimator, ProcessRegistry before deletion"
```

---

## Task 2: Delete Over-Engineered Packages

**Files:**
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ralph/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/service/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/brain/` (entire directory)
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/database/` (entire directory — DB tools get their own connection management)

- [ ] **Step 1: Delete packages**

```bash
cd agent/src/main/kotlin/com/workflow/orchestrator/agent
rm -rf context/ runtime/ orchestrator/ ralph/ service/ brain/ database/
```

- [ ] **Step 2: Delete deeply-tied builtin tools**

These tools are impossible to decouple from the deleted infrastructure:

```bash
cd agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin
rm -f SpawnAgentTool.kt SendMessageToParentTool.kt WorkerCompleteTool.kt
rm -f SkillTool.kt CoreMemoryTools.kt ArchivalMemoryTools.kt
rm -f RequestToolsTool.kt RollbackChangesTool.kt
rm -f UpdatePlanStepTool.kt CreatePlanTool.kt EnablePlanModeTool.kt
rm -f ListChangesTool.kt
```

- [ ] **Step 3: Delete all test files for deleted code**

```bash
cd agent/src/test/kotlin/com/workflow/orchestrator/agent
rm -rf context/ runtime/ orchestrator/ ralph/ service/ brain/ database/
# Also delete tests for deleted tools:
find . -name "*SpawnAgent*" -o -name "*WorkerComplete*" -o -name "*Skill*Tool*" \
  -o -name "*CoreMemory*" -o -name "*ArchivalMemory*" -o -name "*RequestTools*" \
  -o -name "*RollbackChanges*" -o -name "*UpdatePlanStep*" -o -name "*CreatePlan*" \
  -o -name "*EnablePlanMode*" -o -name "*ListChanges*" -o -name "*SendMessageToParent*" | xargs rm -f
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(agent): delete over-engineered packages — context, runtime, orchestrator, ralph, service, brain, database

Removed ~33K LOC of over-engineering:
- 6 gate systems (ApprovalGate, BackpressureGate, SelfCorrectionGate, etc.)
- 4-stage condenser pipeline (EventStore, CondenserPipeline, Anchors)
- 18-section PromptAssembler
- 5-layer tool selection (DynamicToolSelector, ToolPreferences, etc.)
- Sub-agent orchestration (WorkerSession, FileOwnershipRegistry)
- 13 deeply-coupled builtin tools

Kept: all tool implementations, UI, API DTOs, settings, security, listeners"
```

---

## Task 3: Fix Tool Compilation Errors

**Files:**
- Modify: ~40 tool files (update imports)
- Modify: UI files (will be rewritten later, just make them compile)
- Modify: `AgentService.kt` (stub for now)

After the mass deletion, many files have broken imports. This task fixes them mechanically.

- [ ] **Step 1: Fix all `import ...runtime.WorkerType` → `import ...tools.WorkerType`**

Run across ALL tool files:

```bash
cd /path/to/worktree
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  's/import com.workflow.orchestrator.agent.runtime.WorkerType/import com.workflow.orchestrator.agent.tools.WorkerType/g' {} +
```

- [ ] **Step 2: Fix all `import ...context.TokenEstimator` → use core directly**

The old `TokenEstimator` was a typealias to `com.workflow.orchestrator.core.ai.TokenEstimator`. Replace all usages with the `estimateTokens()` function now in `AgentTool.kt`:

```bash
# Remove the old import
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  '/import com.workflow.orchestrator.agent.context.TokenEstimator/d' {} +

# Add the new import where TokenEstimator was used
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  's/TokenEstimator.estimate(\([^)]*\))/estimateTokens(\1)/g' {} +
```

Manually verify files and add `import com.workflow.orchestrator.agent.tools.estimateTokens` where the function is called.

- [ ] **Step 3: Fix ProcessRegistry imports**

```bash
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  's/import com.workflow.orchestrator.agent.runtime.ProcessRegistry/import com.workflow.orchestrator.agent.tools.process.ProcessRegistry/g' {} +
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  's/import com.workflow.orchestrator.agent.runtime.ManagedProcess/import com.workflow.orchestrator.agent.tools.process.ManagedProcess/g' {} +
```

- [ ] **Step 4: Fix ToolOutputStore references**

Tools that used `ToolOutputStore` (GitDiffTool, GitLogTool, GitShowCommitTool): replace with `truncateOutput()` from AgentTool.kt.

```bash
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  '/import com.workflow.orchestrator.agent.context.ToolOutputStore/d' {} +
```

Then in each git tool that used `ToolOutputStore.middleTruncate(content, maxChars)`, replace with `truncateOutput(content, maxChars)` and add `import com.workflow.orchestrator.agent.tools.truncateOutput`.

- [ ] **Step 5: Fix WorkerContext references**

Remove `WorkerContext` usage from tools. In `ReadFileTool` and other tools that check `coroutineContext[WorkerContext]`, remove the check and default to allowing the operation:

```bash
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  '/import com.workflow.orchestrator.agent.runtime.WorkerContext/d' {} +
```

Remove any `coroutineContext[WorkerContext]` blocks — they checked if the tool was running in a sub-agent, which no longer exists.

- [ ] **Step 6: Fix wildcard runtime imports**

Files like `CreateFileTool.kt` and `AskQuestionsTool.kt` have `import ...runtime.*`. Remove and replace with specific needed imports:

```bash
find agent/src/main/kotlin -name "*.kt" -exec sed -i '' \
  '/import com.workflow.orchestrator.agent.runtime.\*/d' {} +
```

Then add back just `import com.workflow.orchestrator.agent.tools.WorkerType` to each file.

- [ ] **Step 7: Simplify AttemptCompletionTool**

Rewrite `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt` — remove `CompletionGatekeeper` dependency, always allow completion:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttemptCompletionTool : AgentTool {

    override val name = "attempt_completion"

    override val description = "Signal that you have finished the user's task. " +
        "Include a summary of what was accomplished. " +
        "Only call this when the ENTIRE task is fully resolved."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "Summary of what was accomplished — key changes and outcomes."
            ),
            "command" to ParameterProperty(
                type = "string",
                description = "Optional verification command (e.g., './gradlew test')"
            )
        ),
        required = listOf("result")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: result",
                summary = "attempt_completion failed: missing result",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val command = params["command"]?.jsonPrimitive?.content

        return ToolResult(
            content = result,
            summary = "Task completed: ${result.take(200)}",
            tokenEstimate = estimateTokens(result),
            isCompletion = true,
            verifyCommand = command
        )
    }
}
```

- [ ] **Step 8: Stub AgentService.kt to compile**

Temporarily replace `AgentService.kt` with a minimal stub so the project compiles. Full rewrite in Task 10.

```kotlin
package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.core.ai.LlmBrain
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): AgentService = project.service<AgentService>()
        val planModeActive = AtomicBoolean(false)
    }

    val brain: LlmBrain by lazy {
        // Will be properly wired in Task 10
        throw IllegalStateException("Brain not configured yet")
    }

    override fun dispose() {
        ProcessRegistry.killAll()
    }
}
```

- [ ] **Step 9: Stub AgentController and HistoryPanel to compile**

Comment out the bodies of `AgentController.kt` and `HistoryPanel.kt` (they'll be rewritten in Task 11). Just make them compile with empty class bodies.

- [ ] **Step 10: Remove deleted tools from ToolRegistry**

The current `ToolRegistry.kt` registers all tools. Remove registrations for deleted tools. Also simplify to a flat map:

```kotlin
package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.api.dto.ToolDefinition

class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]
    fun getAll(): Map<String, AgentTool> = tools.toMap()
    fun getDefinitions(): List<ToolDefinition> = tools.values.map { it.toToolDefinition() }
    fun count(): Int = tools.size
}
```

- [ ] **Step 11: Fix DatabaseConnectionManager dependency in DB tools**

The `DbSchemaTool`, `DbQueryTool`, and `DbListProfilesTool` imported from `agent.database`. Since we deleted that package, either:
- (a) Move `DatabaseConnectionManager` and `DatabaseSettings` to `tools/database/` package, OR
- (b) Temporarily disable DB tools by commenting them out

Option (a) is preferred. Copy the files, update package declarations.

- [ ] **Step 12: Verify compilation**

```bash
./gradlew :agent:compileKotlin 2>&1 | head -100
```

Fix any remaining compilation errors iteratively until clean.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "fix(agent): resolve all compilation errors after package deletion

- Updated ~40 tool files: WorkerType, TokenEstimator, ProcessRegistry imports
- Simplified AttemptCompletionTool (removed CompletionGatekeeper)
- Stubbed AgentService and AgentController for rewrite
- Simplified ToolRegistry to flat map
- Moved DatabaseConnectionManager to tools/database/"
```

---

## Task 4: Write ContextManager (TDD)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerTest.kt`

The ContextManager is a simple message list with token tracking and 3-stage compaction. Modeled after Codex CLI's `ContextManager` — no event sourcing, no anchors, no pipeline.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.core.ai.ApiResult
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse
import com.workflow.orchestrator.agent.api.dto.Choice
import com.workflow.orchestrator.agent.api.dto.UsageInfo
import com.workflow.orchestrator.agent.api.dto.StreamChunk
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContextManagerTest {

    @Test
    fun `system prompt is first message`() {
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("You are a coding agent.")
        cm.addUserMessage("Fix the bug")

        val messages = cm.getMessages()
        assertEquals("system", messages[0].role)
        assertEquals("You are a coding agent.", messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals("Fix the bug", messages[1].content)
    }

    @Test
    fun `tracks messages in order`() {
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("System")
        cm.addUserMessage("Hello")
        cm.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))
        cm.addUserMessage("Do X")

        assertEquals(4, cm.getMessages().size)
        assertEquals("user", cm.getMessages()[1].role)
        assertEquals("assistant", cm.getMessages()[2].role)
        assertEquals("user", cm.getMessages()[3].role)
    }

    @Test
    fun `tool results added correctly`() {
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("System")
        cm.addAssistantMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("read_file", """{"path":"foo.kt"}""")))
        ))
        cm.addToolResult("tc1", "file contents here")

        val messages = cm.getMessages()
        val toolMsg = messages.last()
        assertEquals("tool", toolMsg.role)
        assertEquals("tc1", toolMsg.toolCallId)
        assertEquals("file contents here", toolMsg.content)
    }

    @Test
    fun `utilization uses API-reported tokens when available`() {
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("System")
        cm.addUserMessage("Hello")

        // Before any API call, uses estimate
        assertTrue(cm.utilizationPercent() < 0.01)

        // After API reports token usage
        cm.updateTokens(50_000)
        assertEquals(0.5, cm.utilizationPercent(), 0.01)
    }

    @Test
    fun `shouldCompact returns true above threshold`() {
        val cm = ContextManager(maxInputTokens = 100_000, compactionThreshold = 0.85)
        cm.updateTokens(90_000)
        assertTrue(cm.shouldCompact())

        cm.updateTokens(50_000)
        assertFalse(cm.shouldCompact())
    }

    @Test
    fun `trimOldToolResults replaces old results with placeholders`() {
        val cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.5)
        cm.setSystemPrompt("S")

        // Add 10 tool result cycles
        for (i in 1..10) {
            cm.addAssistantMessage(ChatMessage(
                role = "assistant", content = null,
                toolCalls = listOf(ToolCall("tc$i", "function", FunctionCall("read_file", "{}")))
            ))
            cm.addToolResult("tc$i", "x".repeat(1000)) // 1000 char result
        }

        cm.updateTokens(6_000) // 60% — triggers trimming at 50%
        cm.trimOldToolResults()

        val toolMessages = cm.getMessages().filter { it.role == "tool" }
        // Last 5 should be intact, first 5 should be trimmed
        assertTrue(toolMessages[0].content!!.startsWith("[Result trimmed"))
        assertEquals("x".repeat(1000), toolMessages.last().content)
    }

    @Test
    fun `compact with LLM summarization replaces old messages`() = runTest {
        val fakeBrain = object : LlmBrain {
            override val modelId = "test-model"
            override fun estimateTokens(text: String) = text.length / 4
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?
            ): ApiResult<ChatCompletionResponse> {
                return ApiResult.Success(ChatCompletionResponse(
                    id = "test",
                    choices = listOf(Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "Summary: work was done on foo.kt"),
                        finishReason = "stop"
                    )),
                    usage = UsageInfo(100, 50, 150)
                ))
            }
            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit
            ) = chat(messages, tools, maxTokens)
        }

        val cm = ContextManager(maxInputTokens = 10_000, compactionThreshold = 0.50)
        cm.setSystemPrompt("System")
        for (i in 1..20) {
            cm.addUserMessage("Message $i")
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = "Reply $i"))
        }
        cm.updateTokens(6_000) // 60% of 10K

        val beforeCount = cm.messageCount()
        cm.compact(fakeBrain)
        val afterCount = cm.messageCount()

        assertTrue(afterCount < beforeCount, "Message count should decrease after compaction")
        // First message after system should be the compaction summary
        val messages = cm.getMessages()
        assertTrue(messages[1].content!!.contains("Summary:") || messages[1].content!!.contains("[Context compacted"))
    }

    @Test
    fun `sliding window keeps recent messages when critically full`() {
        val cm = ContextManager(maxInputTokens = 1_000)
        cm.setSystemPrompt("S")
        for (i in 1..50) {
            cm.addUserMessage("M$i")
        }
        cm.updateTokens(960) // 96% — triggers sliding window

        cm.slidingWindow()

        val messages = cm.getMessages()
        // Should have system + compaction notice + last ~30% of messages
        assertTrue(messages.size < 25, "Should have fewer messages after sliding window, got ${messages.size}")
        assertEquals("system", messages[0].role)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.ContextManagerTest" 2>&1 | tail -20
```

Expected: FAIL — `ContextManager` class does not exist.

- [ ] **Step 3: Write ContextManager**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.ApiResult
import com.workflow.orchestrator.core.ai.LlmBrain

/**
 * Simple conversation history + token tracking + 3-stage compaction.
 *
 * Modeled after Codex CLI's ContextManager:
 * - Messages stored as a flat list
 * - Token count from API response (authoritative) or bytes/4 estimate (fallback)
 * - Three compaction stages: trim tool results → LLM summarize → sliding window
 *
 * No event sourcing. No anchors. No condenser pipeline.
 */
class ContextManager(
    private val maxInputTokens: Int = 190_000,
    val compactionThreshold: Double = 0.85,
    private val trimThreshold: Double = 0.70,
    private val emergencyThreshold: Double = 0.95,
    private val recentToolResultsToKeep: Int = 5
) {
    private val messages = mutableListOf<ChatMessage>()
    private var systemPrompt: String = ""
    private var lastPromptTokens: Int = 0

    fun setSystemPrompt(prompt: String) {
        systemPrompt = prompt
    }

    fun addUserMessage(content: String) {
        messages.add(ChatMessage(role = "user", content = content))
    }

    fun addAssistantMessage(message: ChatMessage) {
        messages.add(message)
    }

    fun addToolResult(toolCallId: String, content: String, isError: Boolean = false) {
        messages.add(ChatMessage(
            role = "tool",
            content = if (isError) "[ERROR] $content" else content,
            toolCallId = toolCallId
        ))
    }

    fun getMessages(): List<ChatMessage> {
        return listOf(ChatMessage(role = "system", content = systemPrompt)) + messages
    }

    fun updateTokens(promptTokens: Int) {
        lastPromptTokens = promptTokens
    }

    fun utilizationPercent(): Double {
        val tokens = if (lastPromptTokens > 0) lastPromptTokens else estimateTokensLocal()
        return tokens.toDouble() / maxInputTokens
    }

    fun shouldCompact(): Boolean = utilizationPercent() > compactionThreshold

    fun messageCount(): Int = messages.size

    fun tokenEstimate(): Int = if (lastPromptTokens > 0) lastPromptTokens else estimateTokensLocal()

    /**
     * 3-stage compaction pipeline (industry standard across 13+ production tools):
     *
     * Stage 1 (>70%): Trim old tool results — no LLM, zero cost
     * Stage 2 (>85%): LLM summarization — cheap model, structured summary
     * Stage 3 (>95%): Sliding window — drop oldest messages, last resort
     */
    suspend fun compact(brain: LlmBrain) {
        // Stage 1: Trim old tool results
        if (utilizationPercent() > trimThreshold) {
            trimOldToolResults()
        }

        // Stage 2: LLM summarization
        if (utilizationPercent() > compactionThreshold) {
            summarizeWithLlm(brain)
        }

        // Stage 3: Emergency sliding window
        if (utilizationPercent() > emergencyThreshold) {
            slidingWindow()
        }
    }

    /**
     * Stage 1: Replace old tool results with short placeholders.
     * Keeps the last [recentToolResultsToKeep] tool results intact.
     * Zero cost — no LLM call needed.
     */
    fun trimOldToolResults() {
        val toolResultIndices = messages.indices.filter { messages[it].role == "tool" }
        if (toolResultIndices.size <= recentToolResultsToKeep) return

        val toTrim = toolResultIndices.dropLast(recentToolResultsToKeep)
        for (i in toTrim) {
            val msg = messages[i]
            val content = msg.content ?: continue
            if (content.length > 200) {
                messages[i] = msg.copy(
                    content = "[Result trimmed — was ${content.length} chars]"
                )
            }
        }
    }

    /**
     * Stage 2: Summarize older 70% of messages using LLM.
     * Uses the 9-section structured summary proven across production tools.
     * Previous summary is included for chaining (prevents information loss).
     */
    private suspend fun summarizeWithLlm(brain: LlmBrain) {
        if (messages.size < 4) return // Not enough to summarize

        val splitPoint = (messages.size * 0.7).toInt().coerceAtLeast(2)
        val toSummarize = messages.subList(0, splitPoint)

        val summaryPrompt = buildString {
            appendLine("Summarize this conversation concisely. Structure your summary as:")
            appendLine("1. TASK: What is being worked on")
            appendLine("2. FILES: What files were read/modified")
            appendLine("3. DONE: What was accomplished")
            appendLine("4. ERRORS: Errors encountered and how they were resolved")
            appendLine("5. PENDING: What still needs to be done")
            appendLine()
            appendLine("Conversation to summarize:")
            for (msg in toSummarize) {
                val preview = msg.content?.take(500) ?: "(tool call)"
                appendLine("[${msg.role}]: $preview")
            }
        }

        val result = brain.chat(
            messages = listOf(ChatMessage(role = "user", content = summaryPrompt)),
            maxTokens = 2000
        )

        val summary = when (result) {
            is ApiResult.Success -> result.data.choices.firstOrNull()?.message?.content
                ?: "Summary unavailable"
            is ApiResult.Error -> "Summary unavailable: ${result.message}"
        }

        val kept = messages.subList(splitPoint, messages.size).toList()
        messages.clear()
        messages.add(ChatMessage(
            role = "user",
            content = "[Context compacted. Previous conversation summary:]\n$summary"
        ))
        messages.addAll(kept)

        // Reset token count — will be updated on next API call
        lastPromptTokens = 0
    }

    /**
     * Stage 3: Emergency sliding window. Keeps last 30% of messages.
     * Only used when stages 1+2 weren't enough.
     */
    fun slidingWindow() {
        val keepCount = (messages.size * 0.3).toInt().coerceAtLeast(4)
        val kept = messages.takeLast(keepCount).toList()
        messages.clear()
        messages.add(ChatMessage(
            role = "user",
            content = "[Earlier context was dropped due to size limits. Continue working on the task.]"
        ))
        messages.addAll(kept)
        lastPromptTokens = 0
    }

    /** Codex CLI pattern: bytes / 4. ~80% accurate, instant. */
    private fun estimateTokensLocal(): Int {
        var total = systemPrompt.length / 4 + 4 // system message overhead
        for (msg in messages) {
            total += (msg.content?.length ?: 0) / 4
            total += 4 // per-message overhead
            msg.toolCalls?.forEach { tc ->
                total += tc.function.arguments.length / 4
                total += tc.function.name.length / 4 + 10
            }
        }
        return total
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.ContextManagerTest" 2>&1 | tail -20
```

Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/ContextManagerTest.kt
git commit -m "feat(agent): add ContextManager — simple message list with 3-stage compaction

Modeled after Codex CLI: bytes/4 estimation, API-reported tokens, three
compaction stages (trim tool results → LLM summarize → sliding window)."
```

---

## Task 5: Write AgentLoop (TDD)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopResult.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopTest.kt`

The core ReAct loop. Modeled after Codex CLI + Cline:
- `while not cancelled`: call LLM → execute tools → repeat
- No tool calls + no content = ERROR (inject continuation prompt)
- No tool calls + content = ERROR (force tool use or completion)
- `attempt_completion` tool = REAL exit
- Max 200 iterations as safety net (not normal exit)

- [ ] **Step 1: Write LoopResult**

```kotlin
package com.workflow.orchestrator.agent.loop

sealed class LoopResult {
    data class Completed(
        val summary: String,
        val iterations: Int,
        val tokensUsed: Int = 0,
        val verifyCommand: String? = null
    ) : LoopResult()

    data class Failed(
        val error: String,
        val iterations: Int = 0,
        val tokensUsed: Int = 0
    ) : LoopResult()

    data class Cancelled(
        val iterations: Int,
        val tokensUsed: Int = 0
    ) : LoopResult()
}

/** Progress info emitted during the loop for UI updates. */
data class ToolCallProgress(
    val toolName: String,
    val args: String = "",
    val result: String = "",
    val durationMs: Long = 0,
    val isError: Boolean = false,
    val toolCallId: String = ""
)
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.core.ai.ApiResult
import com.workflow.orchestrator.core.ai.LlmBrain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class AgentLoopTest {

    private val mockProject: Project = mock()

    /** Creates a fake LlmBrain that returns pre-defined responses in sequence. */
    private fun fakeBrain(vararg responses: ChatMessage): LlmBrain {
        val responseQueue = responses.toMutableList()
        return object : LlmBrain {
            override val modelId = "test"
            override fun estimateTokens(text: String) = text.length / 4
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?
            ): ApiResult<ChatCompletionResponse> {
                val msg = if (responseQueue.isNotEmpty()) responseQueue.removeFirst() else
                    ChatMessage(role = "assistant", content = "Done")
                return ApiResult.Success(ChatCompletionResponse(
                    id = "test",
                    choices = listOf(Choice(index = 0, message = msg, finishReason = "stop")),
                    usage = UsageInfo(1000, 100, 1100)
                ))
            }
            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit
            ) = chat(messages, tools, maxTokens)
        }
    }

    /** A simple echo tool that returns its input. */
    private val echoTool = object : AgentTool {
        override val name = "echo"
        override val description = "Echo input"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult("echoed", "echoed", 5)
    }

    /** A completion tool that signals task done. */
    private val completionTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Signal completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult("All done!", "completed", 5, isCompletion = true)
    }

    @Test
    fun `completes when attempt_completion is called`() = runTest {
        val brain = fakeBrain(
            // First response: call echo tool
            ChatMessage(role = "assistant", content = "Let me check",
                toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("echo", "{}")))),
            // Second response: call attempt_completion
            ChatMessage(role = "assistant", content = "Done",
                toolCalls = listOf(ToolCall("tc2", "function", FunctionCall("attempt_completion", "{}"))))
        )

        val tools = mapOf("echo" to echoTool, "attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("You are an agent.")

        val loop = AgentLoop(
            brain = brain,
            tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm,
            project = mockProject
        )

        val result = loop.run("Do something")
        assertTrue(result is LoopResult.Completed)
        assertEquals("All done!", (result as LoopResult.Completed).summary)
        assertEquals(2, result.iterations)
    }

    @Test
    fun `injects continuation on empty response`() = runTest {
        val brain = fakeBrain(
            // First: empty response (no content, no tools)
            ChatMessage(role = "assistant", content = null),
            // Second: still empty
            ChatMessage(role = "assistant", content = null),
            // Third: still empty — should fail after 3 empties
            ChatMessage(role = "assistant", content = null)
        )

        val tools = mapOf("attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = brain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        val result = loop.run("Do something")
        assertTrue(result is LoopResult.Failed)
        assertTrue((result as LoopResult.Failed).error.contains("stopped responding"))
    }

    @Test
    fun `injects tool-use nudge on text-only response`() = runTest {
        val brain = fakeBrain(
            // First: text only, no tool calls
            ChatMessage(role = "assistant", content = "I think we should..."),
            // Second: now calls completion
            ChatMessage(role = "assistant", content = "Done",
                toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("attempt_completion", "{}"))))
        )

        val tools = mapOf("attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = brain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        val result = loop.run("Do X")
        assertTrue(result is LoopResult.Completed)
        // Verify nudge was injected (check context has a user message with nudge)
        val messages = cm.getMessages()
        assertTrue(messages.any { it.role == "user" && it.content?.contains("use tools") == true })
    }

    @Test
    fun `handles unknown tool gracefully`() = runTest {
        val brain = fakeBrain(
            // Calls a tool that doesn't exist
            ChatMessage(role = "assistant", content = null,
                toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("nonexistent", "{}")))),
            // Then completes
            ChatMessage(role = "assistant", content = "Done",
                toolCalls = listOf(ToolCall("tc2", "function", FunctionCall("attempt_completion", "{}"))))
        )

        val tools = mapOf("attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = brain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        val result = loop.run("Do X")
        assertTrue(result is LoopResult.Completed)
    }

    @Test
    fun `handles invalid JSON arguments`() = runTest {
        val brain = fakeBrain(
            ChatMessage(role = "assistant", content = null,
                toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("echo", "not json")))),
            ChatMessage(role = "assistant", content = "Done",
                toolCalls = listOf(ToolCall("tc2", "function", FunctionCall("attempt_completion", "{}"))))
        )

        val tools = mapOf("echo" to echoTool, "attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = brain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        val result = loop.run("Do X")
        assertTrue(result is LoopResult.Completed) // Should recover and continue
    }

    @Test
    fun `cancel stops the loop`() = runTest {
        val brain = fakeBrain(
            ChatMessage(role = "assistant", content = null,
                toolCalls = listOf(ToolCall("tc1", "function", FunctionCall("echo", "{}"))))
        )

        val tools = mapOf("echo" to echoTool, "attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = brain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        loop.cancel() // Cancel before running
        val result = loop.run("Do X")
        assertTrue(result is LoopResult.Cancelled)
    }

    @Test
    fun `fails on API error`() = runTest {
        val errorBrain = object : LlmBrain {
            override val modelId = "test"
            override fun estimateTokens(text: String) = text.length / 4
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?
            ): ApiResult<ChatCompletionResponse> {
                return ApiResult.Error("Server error", 500)
            }
            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit
            ) = chat(messages, tools, maxTokens)
        }

        val tools = mapOf("attempt_completion" to completionTool)
        val cm = ContextManager(maxInputTokens = 100_000)
        cm.setSystemPrompt("Agent")

        val loop = AgentLoop(brain = errorBrain, tools = tools,
            toolDefinitions = tools.values.map { it.toToolDefinition() },
            contextManager = cm, project = mockProject)

        val result = loop.run("Do X")
        assertTrue(result is LoopResult.Failed)
        assertTrue((result as LoopResult.Failed).error.contains("API error"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.AgentLoopTest" 2>&1 | tail -20
```

Expected: FAIL — `AgentLoop` class does not exist.

- [ ] **Step 4: Write AgentLoop**

```kotlin
package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.ApiResult
import com.workflow.orchestrator.core.ai.LlmBrain
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The core ReAct loop. Modeled after Codex CLI + Cline.
 *
 * ```
 * while not done:
 *   1. Compact context if needed
 *   2. Call LLM (streaming)
 *   3. Parse response:
 *      - Has tool calls → execute them, check for attempt_completion
 *      - No tool calls, has content → nudge to use tools
 *      - No tool calls, no content → inject continuation (fail after 3)
 *   4. Repeat
 * ```
 *
 * Exit conditions:
 * - Tool returns isCompletion=true (attempt_completion called)
 * - Cancelled by user
 * - 3 consecutive empty responses
 * - Non-retryable API error
 * - Max iterations (200, safety net only)
 */
class AgentLoop(
    private val brain: LlmBrain,
    private val tools: Map<String, AgentTool>,
    private val toolDefinitions: List<ToolDefinition>,
    private val contextManager: ContextManager,
    private val project: Project,
    private val onStreamChunk: (String) -> Unit = {},
    private val onToolCall: (ToolCallProgress) -> Unit = {},
    private val maxIterations: Int = 200
) {
    private val cancelled = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    fun cancel() {
        cancelled.set(true)
        brain.cancelActiveRequest()
    }

    suspend fun run(task: String): LoopResult {
        contextManager.addUserMessage(task)
        var iteration = 0
        var emptyResponseCount = 0
        var totalTokens = 0

        while (!cancelled.get() && iteration < maxIterations) {
            iteration++

            // Compact if needed
            if (contextManager.shouldCompact()) {
                LOG.info("[AgentLoop] Compacting context at ${(contextManager.utilizationPercent() * 100).toInt()}% utilization")
                contextManager.compact(brain)
            }

            // Call LLM
            val response = try {
                brain.chatStream(
                    messages = contextManager.getMessages(),
                    tools = toolDefinitions,
                    onChunk = { chunk ->
                        chunk.choices.firstOrNull()?.delta?.content?.let { onStreamChunk(it) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("[AgentLoop] LLM call failed: ${e.message}")
                return LoopResult.Failed("LLM call failed: ${e.message}", iteration, totalTokens)
            }

            when (response) {
                is ApiResult.Error -> {
                    LOG.warn("[AgentLoop] API error: ${response.message} (${response.statusCode})")
                    return LoopResult.Failed(
                        "API error (${response.statusCode}): ${response.message}",
                        iteration, totalTokens
                    )
                }
                is ApiResult.Success -> {
                    val choice = response.data.choices.firstOrNull()
                        ?: return LoopResult.Failed("No choices in response", iteration, totalTokens)
                    val message = choice.message

                    // Update token tracking from API response
                    response.data.usage?.let {
                        contextManager.updateTokens(it.promptTokens)
                        totalTokens = it.totalTokens
                    }

                    // Add assistant message to context
                    contextManager.addAssistantMessage(message)

                    val toolCalls = message.toolCalls

                    if (toolCalls.isNullOrEmpty()) {
                        // No tool calls — this is NOT completion (Cline pattern)
                        if (message.content.isNullOrBlank()) {
                            // Completely empty response
                            emptyResponseCount++
                            if (emptyResponseCount >= 3) {
                                return LoopResult.Failed(
                                    "Agent stopped responding after $iteration iterations",
                                    iteration, totalTokens
                                )
                            }
                            contextManager.addUserMessage(
                                "You didn't provide any response or tool calls. " +
                                "Please continue working on the task. " +
                                "If you're done, use the attempt_completion tool."
                            )
                            continue
                        }
                        // Has text but no tools — nudge to take action
                        emptyResponseCount = 0
                        contextManager.addUserMessage(
                            "Please use tools to take action, or call attempt_completion if you're done."
                        )
                        continue
                    }

                    // Execute tool calls
                    emptyResponseCount = 0
                    for (toolCall in toolCalls) {
                        if (cancelled.get()) return LoopResult.Cancelled(iteration, totalTokens)

                        val tool = tools[toolCall.function.name]
                        if (tool == null) {
                            contextManager.addToolResult(
                                toolCall.id,
                                "Error: Unknown tool '${toolCall.function.name}'. Available tools: ${tools.keys.joinToString()}",
                                isError = true
                            )
                            continue
                        }

                        val startTime = System.currentTimeMillis()
                        val params = try {
                            json.parseToJsonElement(toolCall.function.arguments).jsonObject
                        } catch (e: Exception) {
                            contextManager.addToolResult(
                                toolCall.id,
                                "Error: Invalid JSON arguments for ${tool.name}: ${e.message}",
                                isError = true
                            )
                            onToolCall(ToolCallProgress(tool.name, toolCall.function.arguments,
                                "Invalid JSON", 0, isError = true, toolCallId = toolCall.id))
                            continue
                        }

                        val result = try {
                            tool.execute(params, project)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ToolResult(
                                content = "Error executing ${tool.name}: ${e.message}",
                                summary = "Error: ${e.message}",
                                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )
                        }

                        val duration = System.currentTimeMillis() - startTime
                        onToolCall(ToolCallProgress(
                            tool.name, toolCall.function.arguments,
                            result.summary, duration, result.isError, toolCall.id
                        ))

                        // Check for completion
                        if (result.isCompletion) {
                            return LoopResult.Completed(
                                result.content, iteration, totalTokens, result.verifyCommand
                            )
                        }

                        // Add result to context (truncate large outputs)
                        val truncated = truncateOutput(result.content, MAX_RESULT_CHARS)
                        contextManager.addToolResult(toolCall.id, truncated, result.isError)
                    }
                }
            }
        }

        return if (cancelled.get()) {
            LoopResult.Cancelled(iteration, totalTokens)
        } else {
            LoopResult.Failed("Reached max iterations ($maxIterations)", iteration, totalTokens)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(AgentLoop::class.java)
        private const val MAX_RESULT_CHARS = 40_000
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.loop.AgentLoopTest" 2>&1 | tail -30
```

Expected: All 7 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/loop/
git commit -m "feat(agent): add AgentLoop — simple ReAct loop modeled after Codex CLI + Cline

Core pattern: call LLM → execute tools → repeat. Exit via attempt_completion
tool only (Cline pattern). Empty responses get continuation prompts. Text-only
responses get tool-use nudges. 3 consecutive empties = fail. ~150 LOC."
```

---

## Task 6: Write SystemPrompt

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/SystemPromptTest.kt`

Simple, focused system prompt. NOT 18 sections. NOT conditional injection. Just the essentials that tell the LLM what it is and how to behave.

- [ ] **Step 1: Write test**

```kotlin
package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SystemPromptTest {

    @Test
    fun `includes project name and path`() {
        val prompt = SystemPrompt.build(projectName = "MyApp", projectPath = "/home/user/myapp")
        assertTrue(prompt.contains("MyApp"))
        assertTrue(prompt.contains("/home/user/myapp"))
    }

    @Test
    fun `includes attempt_completion instruction`() {
        val prompt = SystemPrompt.build(projectName = "MyApp", projectPath = "/p")
        assertTrue(prompt.contains("attempt_completion"))
    }

    @Test
    fun `plan mode adds read-only constraint`() {
        val prompt = SystemPrompt.build(projectName = "P", projectPath = "/p", planModeEnabled = true)
        assertTrue(prompt.contains("plan mode", ignoreCase = true))
        assertTrue(prompt.contains("CANNOT edit", ignoreCase = true) ||
                   prompt.contains("cannot modify", ignoreCase = true) ||
                   prompt.contains("read-only", ignoreCase = true))
    }

    @Test
    fun `includes repo map when provided`() {
        val prompt = SystemPrompt.build(
            projectName = "P", projectPath = "/p",
            repoMap = "src/\n  main/\n    App.kt"
        )
        assertTrue(prompt.contains("App.kt"))
    }

    @Test
    fun `omits repo map section when null`() {
        val prompt = SystemPrompt.build(projectName = "P", projectPath = "/p", repoMap = null)
        assertFalse(prompt.contains("Repository Structure"))
    }
}
```

- [ ] **Step 2: Write SystemPrompt**

```kotlin
package com.workflow.orchestrator.agent.prompt

/**
 * Simple system prompt builder. ~50 lines, not 605.
 *
 * Follows the Anthropic-recommended structure:
 * Role → Context → Rules → Constraints
 */
object SystemPrompt {

    fun build(
        projectName: String,
        projectPath: String,
        repoMap: String? = null,
        planModeEnabled: Boolean = false,
        additionalContext: String? = null
    ): String = buildString {
        // Role
        appendLine("You are an AI coding agent working inside an IDE on project '$projectName'.")
        appendLine("Project path: $projectPath")
        appendLine()

        // Context
        if (repoMap != null) {
            appendLine("## Repository Structure")
            appendLine(repoMap)
            appendLine()
        }

        if (additionalContext != null) {
            appendLine(additionalContext)
            appendLine()
        }

        // Rules
        appendLine("## Rules")
        appendLine("- Always read a file before editing it.")
        appendLine("- After editing a file, verify the change compiles/works.")
        appendLine("- Keep working until the task is FULLY resolved — do not stop early.")
        appendLine("- If you encounter an error, investigate and fix it — do not give up.")
        appendLine("- Explain what you're doing briefly as you work.")
        appendLine("- When you've finished the task, call the attempt_completion tool with a summary.")
        appendLine("- NEVER respond without calling a tool. Always take action or call attempt_completion.")
        appendLine()

        // Plan mode constraint
        if (planModeEnabled) {
            appendLine("## Plan Mode (ACTIVE)")
            appendLine("You are in read-only plan mode. You CANNOT edit files, create files, or run commands")
            appendLine("that modify the project. You can ONLY read, search, and analyze code.")
            appendLine("Create a detailed plan describing what changes are needed.")
            appendLine()
        }
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.prompt.SystemPromptTest" 2>&1 | tail -20
```

Expected: All 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/prompt/
git commit -m "feat(agent): add SystemPrompt — simple prompt builder, not 18 sections"
```

---

## Task 7: Write Session + SessionStore

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/Session.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/SessionStore.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/session/SessionStoreTest.kt`

Simple session state and JSONL persistence. No event sourcing.

- [ ] **Step 1: Write Session**

```kotlin
package com.workflow.orchestrator.agent.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Session(
    val id: String,
    val title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var lastMessageAt: Long = createdAt,
    var messageCount: Int = 0,
    var status: SessionStatus = SessionStatus.ACTIVE,
    var totalTokens: Int = 0
)

@Serializable
enum class SessionStatus(val value: String) {
    @SerialName("active") ACTIVE("active"),
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("cancelled") CANCELLED("cancelled")
}
```

- [ ] **Step 2: Write SessionStore test**

```kotlin
package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save and load session`() {
        val store = SessionStore(tempDir.toFile())
        val session = Session(id = "s1", title = "Fix bug")

        store.save(session)
        val loaded = store.load("s1")

        assertNotNull(loaded)
        assertEquals("s1", loaded!!.id)
        assertEquals("Fix bug", loaded.title)
    }

    @Test
    fun `list returns all sessions sorted by creation time`() {
        val store = SessionStore(tempDir.toFile())
        store.save(Session(id = "s1", title = "First", createdAt = 1000))
        store.save(Session(id = "s2", title = "Second", createdAt = 2000))

        val sessions = store.list()
        assertEquals(2, sessions.size)
        assertEquals("s2", sessions[0].id) // Most recent first
    }

    @Test
    fun `load returns null for missing session`() {
        val store = SessionStore(tempDir.toFile())
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `update overwrites existing session`() {
        val store = SessionStore(tempDir.toFile())
        store.save(Session(id = "s1", title = "Original"))
        store.save(Session(id = "s1", title = "Updated"))

        val loaded = store.load("s1")
        assertEquals("Updated", loaded!!.title)
    }
}
```

- [ ] **Step 3: Write SessionStore**

```kotlin
package com.workflow.orchestrator.agent.session

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple session persistence to JSON files.
 * One file per session: {baseDir}/sessions/{sessionId}.json
 */
class SessionStore(private val baseDir: File) {

    private val sessionsDir: File get() = File(baseDir, "sessions").also { it.mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun save(session: Session) {
        try {
            val file = File(sessionsDir, "${session.id}.json")
            file.writeText(json.encodeToString(session))
        } catch (e: Exception) {
            LOG.warn("Failed to save session ${session.id}: ${e.message}")
        }
    }

    fun load(sessionId: String): Session? {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Session>(file.readText())
        } catch (e: Exception) {
            LOG.warn("Failed to load session $sessionId: ${e.message}")
            null
        }
    }

    fun list(): List<Session> {
        val dir = sessionsDir
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try { json.decodeFromString<Session>(file.readText()) }
                catch (_: Exception) { null }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    companion object {
        private val LOG = Logger.getInstance(SessionStore::class.java)
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.session.SessionStoreTest" 2>&1 | tail -20
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/session/
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/session/
git commit -m "feat(agent): add Session + SessionStore — simple JSON persistence"
```

---

## Task 8: Rewrite AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

Wire the new components together. This is the top-level service the UI calls.

- [ ] **Step 1: Write AgentService**

```kotlin
package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.session.SessionStore
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.credentials.CredentialStore
import com.workflow.orchestrator.core.credentials.ServiceType
import com.workflow.orchestrator.core.model.ModelCache
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val credentialStore = CredentialStore.getInstance()

    val planModeActive = AtomicBoolean(false)
    var currentLoop: AgentLoop? = null
        private set
    var currentJob: Job? = null
        private set

    val toolRegistry: ToolRegistry by lazy { createToolRegistry() }
    val sessionStore: SessionStore by lazy {
        val baseDir = File(System.getProperty("user.home"), ".workflow-orchestrator/${project.name}")
        SessionStore(baseDir)
    }

    fun createBrain(): LlmBrain {
        val settings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val model = settings.state.sourcegraphChatModel
            ?: ModelCache.pickBest(ModelCache.getCached())?.id
            ?: throw IllegalStateException("No model configured. Set one in Settings > AI & Advanced.")
        return OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = model
        )
    }

    /**
     * Execute a task. This is the main entry point called by the UI.
     *
     * Creates an AgentLoop with the configured brain and tools, runs the task,
     * and returns the result. Streaming and progress callbacks allow real-time UI updates.
     */
    fun executeTask(
        task: String,
        contextManager: ContextManager? = null,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job {
        val brain = createBrain()
        val tools = toolRegistry.getAll()
        val definitions = if (planModeActive.get()) {
            // In plan mode, filter out write tools
            toolRegistry.getDefinitions().filter { def ->
                def.function.name !in WRITE_TOOLS
            }
        } else {
            toolRegistry.getDefinitions()
        }

        val cm = contextManager ?: ContextManager()
        cm.setSystemPrompt(SystemPrompt.build(
            projectName = project.name,
            projectPath = project.basePath ?: "",
            planModeEnabled = planModeActive.get()
        ))

        val loop = AgentLoop(
            brain = brain,
            tools = tools,
            toolDefinitions = definitions,
            contextManager = cm,
            project = project,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall
        )
        currentLoop = loop

        val sessionId = UUID.randomUUID().toString().take(8)
        val session = Session(id = sessionId, title = task.take(100))
        sessionStore.save(session)

        val job = scope.launch {
            val result = loop.run(task)

            // Update session status
            session.status = when (result) {
                is LoopResult.Completed -> SessionStatus.COMPLETED
                is LoopResult.Failed -> SessionStatus.FAILED
                is LoopResult.Cancelled -> SessionStatus.CANCELLED
            }
            session.totalTokens = when (result) {
                is LoopResult.Completed -> result.tokensUsed
                is LoopResult.Failed -> result.tokensUsed
                is LoopResult.Cancelled -> result.tokensUsed
            }
            sessionStore.save(session)

            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
        currentJob = job
        return job
    }

    fun cancelCurrentTask() {
        currentLoop?.cancel()
        currentJob?.cancel()
    }

    private fun createToolRegistry(): ToolRegistry {
        val registry = ToolRegistry()
        // Builtin tools
        registry.register(ReadFileTool())
        registry.register(EditFileTool())
        registry.register(CreateFileTool())
        registry.register(SearchCodeTool())
        registry.register(GlobFilesTool())
        registry.register(RunCommandTool())
        registry.register(AttemptCompletionTool())
        registry.register(AskUserInputTool())
        registry.register(ThinkTool())
        registry.register(ProjectContextTool())
        registry.register(CurrentTimeTool())
        registry.register(KillProcessTool())
        registry.register(SendStdinTool())
        registry.register(RevertFileTool())
        registry.register(AskQuestionsTool())

        // IDE tools — register all from tools/ide/
        // (These constructors should be zero-arg; if not, fix in Task 3)
        registerIdeTools(registry)
        registerIntegrationTools(registry)
        registerFrameworkTools(registry)
        registerPsiTools(registry)
        registerVcsTools(registry)
        registerDebugTools(registry)
        registerConfigTools(registry)

        LOG.info("[AgentService] Registered ${registry.count()} tools")
        return registry
    }

    // Register tool categories — each method adds tools from its package.
    // If a tool fails to instantiate (missing dependency), log and skip it.
    private fun registerIdeTools(registry: ToolRegistry) {
        safeRegister(registry) {
            // Add IDE tool registrations here — copy from existing code
            // Example: registry.register(FormatCodeTool())
        }
    }

    private fun registerIntegrationTools(registry: ToolRegistry) {
        safeRegister(registry) {
            // Add integration tool registrations (JiraTool, BambooBuildsTool, etc.)
        }
    }

    private fun registerFrameworkTools(registry: ToolRegistry) {
        safeRegister(registry) { /* SpringTool, BuildTool */ }
    }

    private fun registerPsiTools(registry: ToolRegistry) {
        safeRegister(registry) { /* FileStructureTool, TypeHierarchyTool, etc. */ }
    }

    private fun registerVcsTools(registry: ToolRegistry) {
        safeRegister(registry) { /* GitLogTool, GitDiffTool, etc. */ }
    }

    private fun registerDebugTools(registry: ToolRegistry) {
        safeRegister(registry) { /* DebugBreakpointsTool, etc. */ }
    }

    private fun registerConfigTools(registry: ToolRegistry) {
        safeRegister(registry) { /* CreateRunConfigTool, etc. */ }
    }

    private inline fun safeRegister(registry: ToolRegistry, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            LOG.warn("[AgentService] Failed to register tools: ${e.message}")
        }
    }

    override fun dispose() {
        scope.cancel()
        ProcessRegistry.killAll()
    }

    companion object {
        private val LOG = Logger.getInstance(AgentService::class.java)

        fun getInstance(project: Project): AgentService = project.service<AgentService>()

        /** Tools filtered out in plan mode */
        private val WRITE_TOOLS = setOf(
            "edit_file", "create_file", "run_command", "revert_file",
            "format_code", "optimize_imports", "refactor_rename",
            "kill_process", "send_stdin"
        )
    }
}
```

**Note to implementer:** The `registerXxxTools` methods need to be filled with actual tool registrations from the existing code. Grep each tool package for classes that implement `AgentTool` and register them. Some may need constructor changes if they previously required runtime/context dependencies.

- [ ] **Step 2: Fill in tool registrations**

For each tool package, find all `AgentTool` implementations and register them. Many tools have zero-arg constructors and will work directly. For tools that had dependencies on deleted code (like tools that took `CompletionGatekeeper` or `SkillManager` as constructor args), those tools were already deleted in Task 2.

```bash
# Find all AgentTool implementations
grep -rn "class.*: AgentTool" agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ | grep -v "Test"
```

Register each one in the appropriate `registerXxxTools` method.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -30
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): rewrite AgentService — simple wiring of AgentLoop + ToolRegistry + SessionStore"
```

---

## Task 9: Rewrite AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

Simplified UI bridge. The controller's job is simple: take user input → create AgentLoop → wire callbacks → display results.

- [ ] **Step 1: Rewrite AgentController**

The exact code depends on the existing `AgentDashboardPanel` API. The controller should:

```kotlin
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import kotlinx.coroutines.Job

/**
 * Simplified UI controller. Bridges AgentDashboardPanel ↔ AgentService.
 *
 * Responsibilities:
 * 1. Take user input from dashboard
 * 2. Start/cancel agent tasks via AgentService
 * 3. Stream text and tool progress to dashboard
 * 4. Display completion/error results
 */
class AgentController(
    private val project: Project,
    private val dashboard: AgentDashboardPanel
) {
    private val service = AgentService.getInstance(project)
    private var currentJob: Job? = null
    private var contextManager: ContextManager? = null

    init {
        dashboard.onSendMessage = { text -> executeTask(text) }
        dashboard.onCancel = { cancelTask() }
        dashboard.onNewChat = { newChat() }
    }

    fun executeTask(task: String) {
        if (task.isBlank()) return

        // Reuse context manager for multi-turn conversation, or create new
        val cm = contextManager ?: ContextManager().also { contextManager = it }

        // Show user message in UI
        dashboard.addUserMessage(task)
        dashboard.setLoading(true)

        currentJob = service.executeTask(
            task = task,
            contextManager = cm,
            onStreamChunk = { chunk ->
                dashboard.appendAssistantText(chunk)
            },
            onToolCall = { progress ->
                dashboard.addToolCall(
                    toolName = progress.toolName,
                    args = progress.args,
                    result = progress.result,
                    durationMs = progress.durationMs,
                    isError = progress.isError
                )
            },
            onComplete = { result ->
                dashboard.setLoading(false)
                when (result) {
                    is LoopResult.Completed -> {
                        dashboard.addCompletionCard(result.summary, result.verifyCommand)
                    }
                    is LoopResult.Failed -> {
                        dashboard.addErrorMessage(result.error)
                    }
                    is LoopResult.Cancelled -> {
                        dashboard.addSystemMessage("Task cancelled after ${result.iterations} iterations.")
                    }
                }
            }
        )
    }

    fun cancelTask() {
        service.cancelCurrentTask()
    }

    fun newChat() {
        cancelTask()
        contextManager = null
        dashboard.clearChat()
    }

    companion object {
        private val LOG = Logger.getInstance(AgentController::class.java)
    }
}
```

**Note:** This assumes `AgentDashboardPanel` has the callback/method signatures listed above. The implementer should read the existing `AgentDashboardPanel` class and adapt the controller to match its actual API (it communicates via JCEF JavaScript bridge). The key methods needed are:
- `addUserMessage(text)` — display user message
- `appendAssistantText(chunk)` — stream assistant response
- `addToolCall(name, args, result, duration, isError)` — show tool execution
- `addCompletionCard(summary, verifyCommand)` — show completion
- `addErrorMessage(error)` — show error
- `addSystemMessage(text)` — show system message
- `setLoading(boolean)` — show/hide loading state
- `clearChat()` — reset chat

If the dashboard uses a JCEF bridge (JavaScript calls), adapt the method names to match the existing bridge protocol.

- [ ] **Step 2: Fix HistoryPanel if needed**

`HistoryPanel.kt` imported `GlobalSessionIndex` from the deleted `service/` package. Replace with `SessionStore.list()`:

```kotlin
// Replace GlobalSessionIndex usage with:
val sessions = AgentService.getInstance(project).sessionStore.list()
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :agent:compileKotlin 2>&1 | tail -30
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/
git commit -m "feat(agent): rewrite AgentController — simple bridge between UI and AgentLoop"
```

---

## Task 10: Full Build + Integration Test

**Files:**
- Modify: Any remaining compilation issues
- Run: Full test suite

- [ ] **Step 1: Run all agent tests**

```bash
./gradlew :agent:test 2>&1 | tail -50
```

Fix any test failures. Many old tests were deleted in Task 2. The remaining tests should be for the kept tools and the new loop/context/session code.

- [ ] **Step 2: Run verifyPlugin**

```bash
./gradlew verifyPlugin 2>&1 | tail -30
```

- [ ] **Step 3: Run buildPlugin**

```bash
./gradlew buildPlugin 2>&1 | tail -20
```

- [ ] **Step 4: Fix any remaining issues**

Iterate until clean compilation + green tests + successful build.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(agent): resolve all remaining compilation and test issues

Clean build: all agent tests pass, verifyPlugin clean, buildPlugin produces ZIP."
```

---

## Task 11: Smoke Test + Documentation

- [ ] **Step 1: Run the IDE sandbox**

```bash
./gradlew runIde
```

In the IDE:
1. Open the Workflow tool window → Agent tab
2. Type a simple task: "Read the README.md file and summarize it"
3. Verify: assistant streams text, tool calls are displayed, completion card appears
4. Test cancel: start a task, click cancel
5. Test new chat: verify context is cleared

- [ ] **Step 2: Verify file count reduction**

```bash
find agent/src/main/kotlin -name "*.kt" | wc -l
# Should be ~60-80 files (down from 211)
```

- [ ] **Step 3: Update CLAUDE.md**

Update the root `CLAUDE.md` to reflect the new architecture:
- Remove references to EventSourcedContextBridge, CondenserPipeline, gates/guards
- Update the Agent module description
- Remove references to sub-agent orchestration, skills, Ralph Loop
- Simplify the module table entry

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "docs: update CLAUDE.md for lean agent architecture

Removed references to deleted systems (EventStore, CondenserPipeline, gates,
guards, sub-agents, skills, Ralph Loop). Updated agent module description to
reflect simple ReAct loop architecture."
```

---

## Summary: What Changed

| Metric | Before | After |
|--------|--------|-------|
| Agent Kotlin files | 211 | ~70 |
| Agent LOC | 48,027 | ~15,000 |
| New core LOC | — | ~1,500 |
| Gate/guard systems | 6 | 0 |
| Condenser stages | 4 | 3 (inline) |
| Prompt sections | 18+ | 1 |
| Tool selection layers | 5 | 1 (flat) |
| Session types | 3 | 1 |
| Core loop file | 1,915 LOC | ~180 LOC |

**What was deleted:** EventStore, CondenserPipeline, Anchors, ConversationMemory, SingleAgentSession, WorkerSession, ConversationSession, ApprovalGate, BackpressureGate, SelfCorrectionGate, CompletionGatekeeper, LoopGuard, BudgetEnforcer, PromptAssembler, AgentOrchestrator, DynamicToolSelector, ToolPreferences, SkillManager, Ralph Loop, FileOwnershipRegistry, WorkerMessageBus, 13 deeply-coupled tools.

**What was kept:** All 55+ tool implementations, JCEF chat UI, API DTOs, settings, security (CommandSafetyAnalyzer), listeners.

**What was added:** AgentLoop (180 LOC), ContextManager (220 LOC), SystemPrompt (60 LOC), Session/SessionStore (160 LOC), LoopResult (20 LOC), simplified AgentService (120 LOC), simplified AgentController (80 LOC).

**Key architectural decisions:**
1. **Explicit completion tool** (Cline pattern) — "no tool calls" is an ERROR, not completion. This fixes the premature stopping problem.
2. **3-stage inline compaction** — trim → summarize → window. No separate pipeline, no event sourcing.
3. **Flat tool registry** — register all tools, send all definitions. No dynamic selection, no preferences, no keyword matching.
4. **Simple system prompt** — role + context + rules. Not 18 conditional sections.
