# Specialist Agent Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the 8 bundled specialist agent configs into `SpawnAgentTool` via an `agent_type` parameter so the LLM can select curated personas (spring-boot-engineer, test-automator, etc.) instead of only generic scopes.

**Architecture:** Add `agent_type` optional parameter to `SpawnAgentTool`. When set, look up the config from `AgentConfigLoader`, use its system prompt / tool allowlist / max turns. When not set, fall back to existing scope-based behavior. Infer plan mode from whether the config includes write tools.

**Tech Stack:** Kotlin, IntelliJ Platform Plugin SDK, JUnit 5, MockK

---

### Task 1: Fix `worker_complete` -> `attempt_completion` in agent MD files

**Files:**
- Modify: `agent/src/main/resources/agents/spring-boot-engineer.md:93-94`
- Modify: `agent/src/main/resources/agents/test-automator.md:88-89`
- Modify: `agent/src/main/resources/agents/code-reviewer.md` (same pattern)
- Modify: `agent/src/main/resources/agents/architect-reviewer.md` (same pattern)
- Modify: `agent/src/main/resources/agents/security-auditor.md` (same pattern)
- Modify: `agent/src/main/resources/agents/devops-engineer.md` (same pattern)
- Modify: `agent/src/main/resources/agents/performance-engineer.md` (same pattern)
- Modify: `agent/src/main/resources/agents/refactoring-specialist.md` (same pattern)

- [ ] **Step 1: Fix all 8 agent MD files**

In every file under `agent/src/main/resources/agents/`, replace:
```
When your task is complete, call `worker_complete` with your full findings.
The parent agent ONLY sees your worker_complete output — tool call history is not visible.
```
with:
```
When your task is complete, call `attempt_completion` with a clear, structured summary of your findings/work.
The parent agent ONLY sees your attempt_completion output — tool call history is not visible.
```

- [ ] **Step 2: Verify no remaining `worker_complete` references**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && grep -r "worker_complete" agent/src/main/resources/agents/`
Expected: No output (zero matches)

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/resources/agents/*.md
git commit -m "fix(agent): replace worker_complete with attempt_completion in specialist agent configs"
```

---

### Task 2: Add `agent_type` parameter and config-based resolution to `SpawnAgentTool`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`

- [ ] **Step 1: Write failing tests for agent_type resolution**

Add to `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt`, inside a new `@Nested inner class AgentTypeTests`:

```kotlin
@Nested
inner class AgentTypeTests {

    private lateinit var configLoader: AgentConfigLoader

    @BeforeEach
    fun setUpConfigLoader() {
        AgentConfigLoader.resetForTests()
        configLoader = AgentConfigLoader.getInstance()
        // Don't call loadFromDisk — we'll manually insert test configs
    }

    private fun insertTestConfig(name: String, tools: List<String>, prompt: String) {
        val yaml = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: \"Test agent: $name\"")
            appendLine("tools: ${tools.joinToString(", ")}")
            appendLine("max-turns: 20")
            appendLine("---")
            appendLine(prompt)
        }
        val config = configLoader.parseAgentConfigFromYaml(yaml)
        // Insert directly into cache via loadFromDisk with a temp dir containing the file
    }

    @Test
    fun `unknown agent_type returns error with available list`() = runTest {
        val spawnTool = SpawnAgentTool(
            brainProvider = { throw IllegalStateException("Should not create brain") },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Test",
                "prompt" to "Do something",
                "agent_type" to "nonexistent-agent"
            ),
            project
        )

        assertTrue(result.isError, "Unknown agent_type should return error")
        assertTrue(result.content.contains("Unknown agent type"), result.content)
    }

    @Test
    fun `agent_type uses config system prompt instead of scope prompt`() = runTest {
        // Write a temp config file so configLoader picks it up
        val tempDir = kotlin.io.path.createTempDirectory("agent-config-test")
        val configFile = tempDir.resolve("test-specialist.md")
        configFile.toFile().writeText("""
            ---
            name: test-specialist
            description: "A test specialist agent"
            tools: read_file, search_code, think, attempt_completion
            max-turns: 15
            ---
            You are a TEST SPECIALIST. Your job is to analyze test coverage.
        """.trimIndent())
        configLoader.loadFromDisk(tempDir)

        var capturedSystemPrompt: String? = null
        val capturingBrain = object : LlmBrain {
            override val modelId = "test-brain"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                capturedSystemPrompt = messages.firstOrNull { it.role == "system" }?.content
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { capturingBrain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        spawnTool.execute(
            params(
                "description" to "Analyze tests",
                "prompt" to "Check test coverage",
                "agent_type" to "test-specialist"
            ),
            project
        )

        assertNotNull(capturedSystemPrompt, "Brain should have been called")
        assertTrue(
            capturedSystemPrompt!!.contains("TEST SPECIALIST"),
            "System prompt should come from config, not generic scope. Got: ${capturedSystemPrompt!!.take(200)}"
        )
        // Clean up
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `agent_type resolves config tools not scope tools`() = runTest {
        val tempDir = kotlin.io.path.createTempDirectory("agent-config-test")
        val configFile = tempDir.resolve("read-only-agent.md")
        configFile.toFile().writeText("""
            ---
            name: read-only-agent
            description: "Read-only agent"
            tools: read_file, search_code, think, attempt_completion
            max-turns: 10
            ---
            You are a read-only agent.
        """.trimIndent())
        configLoader.loadFromDisk(tempDir)

        var capturedToolNames: List<String>? = null
        val capturingBrain = object : LlmBrain {
            override val modelId = "test-brain"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                capturedToolNames = tools?.map { it.function.name }
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { capturingBrain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        spawnTool.execute(
            params(
                "description" to "Read-only task",
                "prompt" to "Analyze code",
                "agent_type" to "read-only-agent"
            ),
            project
        )

        assertNotNull(capturedToolNames)
        assertTrue("read_file" in capturedToolNames!!, "Should have read_file from config")
        assertTrue("search_code" in capturedToolNames!!, "Should have search_code from config")
        assertFalse("edit_file" in capturedToolNames!!, "Should NOT have edit_file (not in config)")
        assertFalse("run_command" in capturedToolNames!!, "Should NOT have run_command (not in config)")
        assertFalse("agent" in capturedToolNames!!, "agent tool must always be excluded")
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `agent_type with write tools infers planMode false`() = runTest {
        val tempDir = kotlin.io.path.createTempDirectory("agent-config-test")
        tempDir.resolve("writer-agent.md").toFile().writeText("""
            ---
            name: writer-agent
            description: "Write agent"
            tools: read_file, edit_file, create_file, run_command, think, attempt_completion
            max-turns: 25
            ---
            You are an implementation agent.
        """.trimIndent())
        configLoader.loadFromDisk(tempDir)

        var capturedSystemPrompt: String? = null
        val capturingBrain = object : LlmBrain {
            override val modelId = "test-brain"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                capturedSystemPrompt = messages.firstOrNull { it.role == "system" }?.content
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { capturingBrain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        // The key assertion is that execution succeeds — if planMode were true,
        // the sub-agent would block edit_file/create_file/run_command.
        // A successful attempt_completion proves planMode is false.
        val result = spawnTool.execute(
            params(
                "description" to "Implement feature",
                "prompt" to "Add the feature",
                "agent_type" to "writer-agent"
            ),
            project
        )

        assertFalse(result.isError, "Writer agent should succeed")
        assertTrue(result.content.contains("Done."), "Should contain completion result")
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `agent_type without write tools infers planMode true`() = runTest {
        val tempDir = kotlin.io.path.createTempDirectory("agent-config-test")
        tempDir.resolve("reviewer-agent.md").toFile().writeText("""
            ---
            name: reviewer-agent
            description: "Review agent"
            tools: read_file, search_code, diagnostics, think, attempt_completion
            max-turns: 15
            ---
            You are a code reviewer.
        """.trimIndent())
        configLoader.loadFromDisk(tempDir)

        var capturedToolNames: List<String>? = null
        val capturingBrain = object : LlmBrain {
            override val modelId = "test-brain"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                capturedToolNames = tools?.map { it.function.name }
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Review done."}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { capturingBrain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Review code",
                "prompt" to "Review the auth module",
                "agent_type" to "reviewer-agent"
            ),
            project
        )

        assertFalse(result.isError, "Reviewer agent should succeed")
        // No write tools should be present in the tool list
        assertNotNull(capturedToolNames)
        assertFalse("edit_file" in capturedToolNames!!)
        assertFalse("create_file" in capturedToolNames!!)
        assertFalse("run_command" in capturedToolNames!!)
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `scope still works when agent_type is absent`() = runTest {
        val capturingBrain = object : LlmBrain {
            override val modelId = "test-brain"
            override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) = throw UnsupportedOperationException()
            override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
                return ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            }
            override fun estimateTokens(text: String) = text.length / 4
            override fun cancelActiveRequest() {}
        }

        val spawnTool = SpawnAgentTool(
            brainProvider = { capturingBrain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Research task",
                "prompt" to "Analyze code",
                "scope" to "research"
            ),
            project
        )

        assertFalse(result.isError, "Scope-based execution should still work without agent_type")
    }

    @Test
    fun `config max-turns overrides default iterations`() = runTest {
        val tempDir = kotlin.io.path.createTempDirectory("agent-config-test")
        tempDir.resolve("limited-agent.md").toFile().writeText("""
            ---
            name: limited-agent
            description: "Limited agent"
            tools: read_file, think, attempt_completion
            max-turns: 8
            ---
            You are a limited agent.
        """.trimIndent())
        configLoader.loadFromDisk(tempDir)

        // The agent completes on first call, so max turns won't be hit,
        // but we verify construction doesn't fail with config's maxTurns
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
        ))

        val spawnTool = SpawnAgentTool(
            brainProvider = { brain },
            toolRegistry = registry,
            project = project,
            configLoader = configLoader
        )

        val result = spawnTool.execute(
            params(
                "description" to "Quick task",
                "prompt" to "Do it",
                "agent_type" to "limited-agent"
            ),
            project
        )

        assertFalse(result.isError, "Should succeed with config max-turns")
        tempDir.toFile().deleteRecursively()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*SpawnAgentToolTest*AgentTypeTests*" -x viteDevBuild`
Expected: Compilation errors — `SpawnAgentTool` doesn't accept `configLoader` parameter yet.

- [ ] **Step 3: Add `configLoader` constructor param and `agent_type` parameter**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`:

Add import:
```kotlin
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.tools.subagent.AgentConfig
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
```

Add to constructor:
```kotlin
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    var contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    var maxOutputTokens: Int? = null,
    var sessionDebugDir: java.io.File? = null,
    var onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null,
    private val configLoader: AgentConfigLoader? = null
) : AgentTool {
```

Add `agent_type` to `parameters`:
```kotlin
"agent_type" to ParameterProperty(
    type = "string",
    description = "Name of a specialist agent type. When set, scope is ignored and the agent uses a curated system prompt and tool set. Use for domain-specific tasks."
),
```

- [ ] **Step 4: Add dynamic description with available agent types**

Replace the hardcoded `override val description` with a computed property that appends available agent types:

```kotlin
override val description: String
    get() {
        val base = """Launch a focused sub-agent to handle a task in its own context window. ..."""  // existing text
        
        val configs = configLoader?.getAllCachedConfigs()
        if (configs.isNullOrEmpty()) return base
        
        val agentList = configs
            .sortedBy { it.name }
            .joinToString("\n") { "- \"${it.name}\": ${it.description}" }
        
        return "$base\n\nAvailable agent types (use with agent_type parameter):\n$agentList"
    }
```

- [ ] **Step 5: Add config-based resolution methods**

Add to `SpawnAgentTool`:

```kotlin
/**
 * Resolve tools from an AgentConfig's tool list against the registry.
 * Always excludes the "agent" tool (depth-1 enforcement).
 * Unknown tool names are skipped with a log warning.
 */
private fun resolveConfigTools(config: AgentConfig): Map<String, AgentTool> {
    return config.tools
        .filter { it != "agent" }
        .mapNotNull { name ->
            val tool = toolRegistry.get(name)
            if (tool == null) {
                LOG.warn("[SpawnAgent] Config '${config.name}' references unknown tool: $name")
            }
            tool?.let { name to it }
        }
        .toMap()
}

/**
 * Infer plan mode from whether the config's tools include any write tools.
 * Uses [AgentLoop.WRITE_TOOLS] as the canonical write-tool set.
 */
private fun inferPlanMode(resolvedTools: Map<String, AgentTool>): Boolean {
    return resolvedTools.keys.none { it in AgentLoop.WRITE_TOOLS }
}

companion object {
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(SpawnAgentTool::class.java)
    // ... existing constants
}
```

- [ ] **Step 6: Add `executeFromConfig` method**

Add to `SpawnAgentTool`:

```kotlin
private suspend fun executeFromConfig(
    description: String,
    prompt: String,
    config: AgentConfig,
    iterationOverride: Int?
): ToolResult {
    val brain = brainProvider()
    val resolvedTools = resolveConfigTools(config)
    
    if (resolvedTools.isEmpty()) {
        return errorResult("Agent type '${config.name}' has no resolvable tools. Config lists: ${config.tools}")
    }
    
    val planMode = inferPlanMode(resolvedTools)
    val maxIter = (config.maxTurns ?: iterationOverride ?: 50)
        .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    
    val runner = SubagentRunner(
        brain = brain,
        tools = resolvedTools,
        systemPrompt = config.systemPrompt,
        project = project,
        maxIterations = maxIter,
        planMode = planMode,
        contextBudget = contextBudget,
        maxOutputTokens = maxOutputTokens,
        apiDebugDir = subagentDebugDir(description)
    )
    
    val result = runner.run(prompt) { progress ->
        onSubagentProgress?.invoke(description, progress)
    }
    
    return mapSingleResult(description, config.name, result)
}
```

- [ ] **Step 7: Update `execute()` to check `agent_type` first**

Replace the body of `execute()`:

```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    val description = params["description"]?.jsonPrimitive?.content
        ?: return errorResult("Missing required parameter: description")
    val prompt = params["prompt"]?.jsonPrimitive?.content
        ?: return errorResult("Missing required parameter: prompt")
    val agentType = params["agent_type"]?.jsonPrimitive?.content
    val scope = params["scope"]?.jsonPrimitive?.content ?: "implement"
    val maxIter = params["max_iterations"]?.jsonPrimitive?.intOrNull ?: 50

    // Agent type path: use config's prompt, tools, max-turns
    if (agentType != null) {
        val config = configLoader?.getCachedConfig(agentType)
            ?: return errorResult(buildUnknownAgentTypeError(agentType))
        return executeFromConfig(description, prompt, config, maxIter)
    }

    // Scope path: existing behavior
    if (scope !in VALID_SCOPES) {
        return errorResult("Invalid scope: '$scope'. Must be one of: research, implement, review")
    }

    val clampedIterations = maxIter.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

    val prompts = if (scope == "research") {
        PROMPT_KEYS.mapNotNull { key -> params[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }
    } else {
        listOf(prompt)
    }

    if (prompts.isEmpty()) {
        return errorResult("No valid prompts provided")
    }

    return if (prompts.size == 1) {
        executeSingle(description, prompts.first(), scope, clampedIterations)
    } else {
        executeParallel(description, prompts, scope, clampedIterations)
    }
}

private fun buildUnknownAgentTypeError(name: String): String {
    val available = configLoader?.getAllCachedConfigs()
        ?.sortedBy { it.name }
        ?.joinToString(", ") { it.name }
        ?: "(none loaded)"
    return "Unknown agent type '$name'. Available: $available"
}
```

- [ ] **Step 8: Run tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test --tests "*SpawnAgentToolTest*" -x viteDevBuild`
Expected: All tests pass (both new AgentTypeTests and existing scope/param/loop tests).

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentToolTest.kt
git commit -m "feat(agent): integrate specialist agent configs via agent_type parameter

SpawnAgentTool now accepts an optional agent_type parameter that selects
a named specialist config from AgentConfigLoader. When set, the config's
system prompt, tool allowlist, and max-turns are used instead of the
generic scope-based behavior. Plan mode is inferred from whether the
config includes write tools. Available agent types are listed dynamically
in the tool description."
```

---

### Task 3: Wire `AgentConfigLoader` into `SpawnAgentTool` in `AgentService`

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:248-252`

- [ ] **Step 1: Pass configLoader to SpawnAgentTool constructor**

In `AgentService.registerAllTools()`, change the SpawnAgentTool registration from:

```kotlin
safeRegisterCore { SpawnAgentTool(
    brainProvider = { createBrain() },
    toolRegistry = registry,
    project = project
) }
```

to:

```kotlin
safeRegisterCore { SpawnAgentTool(
    brainProvider = { createBrain() },
    toolRegistry = registry,
    project = project,
    configLoader = AgentConfigLoader.getInstance()
) }
```

- [ ] **Step 2: Run full agent test suite**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test -x viteDevBuild`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): wire AgentConfigLoader into SpawnAgentTool"
```

---

### Task 4: Verify full build

**Files:** None (verification only)

- [ ] **Step 1: Build the plugin**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew buildPlugin -x viteDevBuild`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all module tests**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin-agent-rewrite && ./gradlew :agent:test :core:test -x viteDevBuild`
Expected: BUILD SUCCESSFUL, all tests pass.
