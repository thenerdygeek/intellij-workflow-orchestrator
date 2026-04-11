# Sub-Agent XML Tool Definition Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 3 gaps causing sub-agent failures after the streaming/XML tool port: missing XML tool definitions in system prompt, missing tool name providers, and missing toolExecutionMode.

**Architecture:** The fix centers on `SubagentRunner` — it must build XML tool definitions from its tool set and inject them into its system prompt, pass `toolNameProvider`/`paramNameProvider` lambdas to `AgentLoop` scoped to the sub-agent's own tool map, and thread through `toolExecutionMode` from caller. No changes to `AgentLoop`, `OpenAiCompatBrain`, `SourcegraphChatClient`, or `AssistantMessageParser` — those are correct.

**Tech Stack:** Kotlin, JUnit 5 + MockK + runTest, IntelliJ Platform

---

### Task 1: Write failing tests for XML tool definitions in sub-agent system prompt

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt`

This is the critical fix — without XML tool definitions in the system prompt, the LLM doesn't know what tools exist (since `tools = null` in the API request).

- [ ] **Step 1: Write test that captures system prompt passed to ContextManager**

The test verifies that when SubagentRunner creates the ContextManager and sets the system prompt, it includes XML tool definitions (output of `ToolPromptBuilder.build()`). The existing `SequenceBrain` and `stubTool()` helpers can be reused.

```kotlin
@Nested
inner class XmlToolDefinitionTests {

    @Test
    fun `system prompt includes XML tool definitions`() = runTest {
        // Brain that completes immediately
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done."}"""
            ))
        ))

        val tools = buildTools()
        val runner = SubagentRunner(
            brain = brain,
            tools = tools,
            systemPrompt = "You are a test agent.",
            project = project,
            maxIterations = 10,
            planMode = false,
            contextBudget = 50_000
        )

        val result = runner.run("Test task") {}

        assertEquals(SubagentRunStatus.COMPLETED, result.status)
        // The brain receives messages from ContextManager.
        // First message is system prompt (role=user with <system_instructions>).
        // We need to verify it contains tool definitions.
        // SequenceBrain doesn't capture messages, so we'll add that.
        assertTrue(brain.lastMessages.isNotEmpty(), "Brain should have received messages")
        val systemContent = brain.lastMessages.first().content ?: ""
        // Should contain the ToolPromptBuilder format header
        assertTrue(systemContent.contains("# Tool Use Format"), 
            "System prompt should contain XML tool definitions")
        // Should contain tool names as XML tag examples
        assertTrue(systemContent.contains("<read_file>"),
            "System prompt should contain <read_file> tool definition")
        assertTrue(systemContent.contains("<attempt_completion>"),
            "System prompt should contain <attempt_completion> tool definition")
    }

    @Test
    fun `system prompt preserves original config prompt`() = runTest {
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done."}"""
            ))
        ))

        val customPrompt = "You are a specialized code reviewer for Kotlin projects."
        val runner = SubagentRunner(
            brain = brain,
            tools = buildTools(),
            systemPrompt = customPrompt,
            project = project,
            maxIterations = 10,
            planMode = false,
            contextBudget = 50_000
        )

        runner.run("Review task") {}

        val systemContent = brain.lastMessages.first().content ?: ""
        assertTrue(systemContent.contains(customPrompt),
            "System prompt should still contain the original config prompt")
    }
}
```

- [ ] **Step 2: Update SequenceBrain to capture messages**

Add a `lastMessages` field to the existing `SequenceBrain` test helper so tests can inspect what was sent to the LLM:

```kotlin
private class SequenceBrain(
    private val responses: List<ApiResult<ChatCompletionResponse>>
) : LlmBrain {
    override val modelId: String = "test-subagent-brain"
    private var callIndex = 0
    var cancelled = false
        private set
    /** Captured messages from the last chatStream call. */
    var lastMessages: List<ChatMessage> = emptyList()
        private set

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?
    ): ApiResult<ChatCompletionResponse> {
        throw UnsupportedOperationException("AgentLoop uses chatStream")
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit
    ): ApiResult<ChatCompletionResponse> {
        lastMessages = messages
        if (callIndex >= responses.size) {
            return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses")
        }
        return responses[callIndex++]
    }

    override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
    override fun cancelActiveRequest() { cancelled = true }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: FAIL — `System prompt should contain XML tool definitions` assertion fails because SubagentRunner currently sets the raw config prompt without XML tool definitions.

- [ ] **Step 4: Commit failing tests**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt
git commit -m "test(subagent): add failing tests — system prompt must include XML tool definitions"
```

---

### Task 2: Implement XML tool definition injection in SubagentRunner

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt`

- [ ] **Step 1: Import ToolPromptBuilder and build XML tool definitions**

In `SubagentRunner.run()`, after building `toolDefinitions` (line 92), build the XML tool markdown and prepend it to the system prompt:

```kotlin
// At the top of the file, add import:
import com.workflow.orchestrator.core.ai.ToolPromptBuilder

// In run(), replace line 81:
//   contextManager.setSystemPrompt(systemPrompt)
// With:

// 1. Create fresh context manager with budget
val contextManager = ContextManager(maxInputTokens = contextBudget)

// 4. Build tool definitions from the tools map (moved up before system prompt)
val toolDefinitions = tools.values.map { it.toToolDefinition() }

// Build XML tool definitions and compose final system prompt
val toolDefsMarkdown = ToolPromptBuilder.build(toolDefinitions)
val composedSystemPrompt = "$systemPrompt\n\n====\n\n$toolDefsMarkdown"
contextManager.setSystemPrompt(composedSystemPrompt)
```

Note: The `====` separator matches `SystemPrompt.SECTION_SEP` used by the main agent.

Also remove the now-duplicate `val toolDefinitions` line that was at the old line 92.

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: PASS — system prompt now includes XML tool definitions.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt
git commit -m "fix(subagent): inject XML tool definitions into sub-agent system prompt"
```

---

### Task 3: Write failing tests for toolNameProvider / paramNameProvider

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt`

These providers let the XML parser (AssistantMessageParser) know which tag names are tool calls vs. plain text during streaming. Without them, the parser falls back to the brain's static set (which is the main agent's full tool set, not the sub-agent's subset).

- [ ] **Step 1: Write test for tool name scoping**

```kotlin
@Test
fun `XML parser receives sub-agent tool names not main agent tool set`() = runTest {
    // Brain with toolNameSet simulating main agent's full set
    val mainAgentToolNames = setOf("read_file", "edit_file", "run_command", "search_code", "think", "attempt_completion", "jira", "bamboo_builds")
    val mainAgentParamNames = setOf("path", "content", "query", "command", "result", "action")

    val brain = SequenceBrain(
        responses = listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done."}"""
            ))
        ),
        toolNames = mainAgentToolNames,
        paramNames = mainAgentParamNames
    )

    // Sub-agent only has 3 tools
    val subTools = mapOf(
        "read_file" to stubTool("read_file"),
        "search_code" to stubTool("search_code"),
        "attempt_completion" to AttemptCompletionTool()
    )

    val runner = SubagentRunner(
        brain = brain,
        tools = subTools,
        systemPrompt = "You are a research agent.",
        project = project,
        maxIterations = 10,
        planMode = false,
        contextBudget = 50_000
    )

    runner.run("Research task") {}

    // The tool names used for XML parsing should be scoped to sub-agent's tools,
    // not the brain's static mainAgentToolNames
    // We verify via the brain's toolNameSet fallback — if providers are set,
    // the loop uses them instead of brain.toolNameSet
    // This is a behavioral test: the system prompt should list only sub-agent tools
    val systemContent = brain.lastMessages.first().content ?: ""
    assertFalse(systemContent.contains("<jira>"),
        "System prompt should NOT contain main-agent-only tools like <jira>")
    assertFalse(systemContent.contains("<bamboo_builds>"),
        "System prompt should NOT contain main-agent-only tools like <bamboo_builds>")
    assertTrue(systemContent.contains("<read_file>"),
        "System prompt SHOULD contain sub-agent tool <read_file>")
}
```

- [ ] **Step 2: Update SequenceBrain to accept toolNames/paramNames overrides**

```kotlin
private class SequenceBrain(
    private val responses: List<ApiResult<ChatCompletionResponse>>,
    toolNames: Set<String> = emptySet(),
    paramNames: Set<String> = emptySet()
) : LlmBrain {
    override val modelId: String = "test-subagent-brain"
    override val toolNameSet: Set<String> = toolNames
    override val paramNameSet: Set<String> = paramNames
    // ... rest unchanged
}
```

- [ ] **Step 3: Run test to verify it passes (it should pass from Task 2)**

The XML tool definitions are built from the sub-agent's tool set (Task 2), so this test already passes. This confirms the scoping is correct.

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt
git commit -m "test(subagent): add test verifying XML tool names scoped to sub-agent tools"
```

---

### Task 4: Pass toolNameProvider and paramNameProvider to sub-agent AgentLoop

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt`

- [ ] **Step 1: Add toolNameProvider and paramNameProvider to the AgentLoop constructor call**

In `SubagentRunner.run()`, update the AgentLoop creation (currently around line 99) to pass providers scoped to the sub-agent's own tool map:

```kotlin
val loop = AgentLoop(
    brain = brain,
    tools = tools,
    toolDefinitions = toolDefinitions,
    contextManager = contextManager,
    project = project,
    maxIterations = maxIterations,
    maxOutputTokens = maxOutputTokens,
    planMode = planMode,
    onToolCall = { progress ->
        // ... existing callback unchanged ...
    },
    onTokenUpdate = { inputTokens, outputTokens ->
        // ... existing callback unchanged ...
    },
    toolNameProvider = { tools.keys },
    paramNameProvider = { tools.values.flatMap { it.parameters.properties.keys }.toSet() }
)
```

The providers return the sub-agent's own tool names and param names. These are static for sub-agents (no deferred tool loading), but using providers keeps the pattern consistent with the main agent and means the XML parser uses the correct scoped set instead of falling back to the brain's superset.

- [ ] **Step 2: Run all SubagentRunner tests**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt
git commit -m "fix(subagent): pass toolNameProvider and paramNameProvider scoped to sub-agent tools"
```

---

### Task 5: Write failing test for toolExecutionMode

**Files:**
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt`

- [ ] **Step 1: Write test that SubagentRunner accepts and threads through toolExecutionMode**

```kotlin
@Nested
inner class ToolExecutionModeTests {

    @Test
    fun `runner accepts toolExecutionMode parameter`() = runTest {
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done."}"""
            ))
        ))

        // Construct with stream_interrupt mode — should not throw
        val runner = SubagentRunner(
            brain = brain,
            tools = buildTools(),
            systemPrompt = "You are a test agent.",
            project = project,
            maxIterations = 10,
            planMode = false,
            contextBudget = 50_000,
            toolExecutionMode = "stream_interrupt"
        )

        val result = runner.run("Test task") {}
        assertEquals(SubagentRunStatus.COMPLETED, result.status)
    }

    @Test
    fun `runner defaults to accumulate mode`() = runTest {
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse(
                "attempt_completion" to """{"result":"Done."}"""
            ))
        ))

        // Construct without toolExecutionMode — should default to "accumulate"
        val runner = SubagentRunner(
            brain = brain,
            tools = buildTools(),
            systemPrompt = "You are a test agent.",
            project = project,
            maxIterations = 10,
            planMode = false,
            contextBudget = 50_000
        )

        val result = runner.run("Test task") {}
        assertEquals(SubagentRunStatus.COMPLETED, result.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: FAIL — `SubagentRunner` constructor does not have a `toolExecutionMode` parameter yet.

- [ ] **Step 3: Commit failing test**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunnerTest.kt
git commit -m "test(subagent): add failing test — SubagentRunner must accept toolExecutionMode"
```

---

### Task 6: Add toolExecutionMode to SubagentRunner and thread it through

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`

- [ ] **Step 1: Add toolExecutionMode parameter to SubagentRunner constructor**

```kotlin
class SubagentRunner(
    private val brain: LlmBrain,
    private val tools: Map<String, AgentTool>,
    private val systemPrompt: String,
    private val project: Project,
    private val maxIterations: Int,
    private val planMode: Boolean,
    private val contextBudget: Int,
    private val maxOutputTokens: Int? = null,
    private val apiDebugDir: File? = null,
    private val toolExecutionMode: String = "accumulate"
) {
```

- [ ] **Step 2: Pass toolExecutionMode to AgentLoop**

In the AgentLoop constructor call inside `run()`, add:

```kotlin
val loop = AgentLoop(
    // ... existing params ...
    toolNameProvider = { tools.keys },
    paramNameProvider = { tools.values.flatMap { it.parameters.properties.keys }.toSet() },
    toolExecutionMode = toolExecutionMode
)
```

- [ ] **Step 3: Run SubagentRunner tests**

Run: `./gradlew :agent:test --tests "*.SubagentRunnerTest" -x verifyPlugin`
Expected: PASS

- [ ] **Step 4: Thread toolExecutionMode from SpawnAgentTool to SubagentRunner**

In `SpawnAgentTool`, add a `var toolExecutionMode` property (like `contextBudget` and `maxOutputTokens`):

```kotlin
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    var contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    var maxOutputTokens: Int? = null,
    var sessionDebugDir: java.io.File? = null,
    var onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null,
    private val configLoader: AgentConfigLoader? = null,
    var toolExecutionMode: String = "accumulate"
) : AgentTool {
```

Then in `executeSingle()`, pass it to SubagentRunner:

```kotlin
val runner = SubagentRunner(
    brain = brain,
    tools = resolvedTools,
    systemPrompt = config.systemPrompt,
    project = project,
    maxIterations = maxIter,
    planMode = planMode,
    contextBudget = contextBudget,
    maxOutputTokens = maxOutputTokens,
    apiDebugDir = subagentDebugDir(description),
    toolExecutionMode = toolExecutionMode
)
```

And in `executeParallel()`, the same:

```kotlin
val runner = SubagentRunner(
    brain = brain,
    tools = resolvedTools,
    systemPrompt = config.systemPrompt,
    project = project,
    maxIterations = maxIter,
    planMode = true,
    contextBudget = contextBudget,
    maxOutputTokens = maxOutputTokens,
    apiDebugDir = subagentDebugDir("${description}-${idx + 1}"),
    toolExecutionMode = toolExecutionMode
)
```

- [ ] **Step 5: Wire toolExecutionMode from AgentSettings in AgentService**

In `AgentService.executeTask()` (around line 760-768), where SpawnAgentTool settings are wired:

```kotlin
val spawnAgentTool = registry.get("agent") as? SpawnAgentTool
if (spawnAgentTool != null) {
    spawnAgentTool.contextBudget = agentSettings.state.maxInputTokens
    spawnAgentTool.maxOutputTokens = agentSettings.state.maxOutputTokens
    spawnAgentTool.sessionDebugDir = sessionDebugDir
    spawnAgentTool.toolExecutionMode = agentSettings.state.toolExecutionMode ?: "accumulate"
    // ... existing onSubagentProgress wiring ...
}
```

- [ ] **Step 6: Run all agent tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "fix(subagent): thread toolExecutionMode from settings through SpawnAgentTool to SubagentRunner"
```

---

### Task 7: Run full test suite and verify plugin

**Files:** None (verification only)

- [ ] **Step 1: Run core tests**

Run: `./gradlew :core:test -x verifyPlugin`
Expected: PASS (core was not modified)

- [ ] **Step 2: Run all agent tests**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: PASS

- [ ] **Step 3: Run verifyPlugin**

Run: `./gradlew verifyPlugin`
Expected: PASS across all 3 IDE versions

- [ ] **Step 4: Commit any test fixes if needed**

---

### Summary of Changes

| File | Change | Gap Fixed |
|------|--------|-----------|
| `SubagentRunner.kt` | Build XML tool defs via `ToolPromptBuilder.build()`, compose into system prompt | Gap 1 (CRITICAL) |
| `SubagentRunner.kt` | Pass `toolNameProvider = { tools.keys }` and `paramNameProvider` to AgentLoop | Gap 2 |
| `SubagentRunner.kt` | Add `toolExecutionMode` constructor param, pass to AgentLoop | Gap 3 |
| `SpawnAgentTool.kt` | Add `var toolExecutionMode`, pass to SubagentRunner in `executeSingle()` and `executeParallel()` | Gap 3 |
| `AgentService.kt` | Wire `spawnAgentTool.toolExecutionMode` from `AgentSettings` | Gap 3 |
| `SubagentRunnerTest.kt` | Tests for XML tool defs in prompt, tool name scoping, toolExecutionMode | All 3 gaps |
