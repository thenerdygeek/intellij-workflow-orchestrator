# Phase 3: Agentic AI Foundation — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `:agent` module — a workflow-aware agentic AI system that uses Sourcegraph's OpenAI-compatible API as the brain, IntelliJ PSI for code intelligence, and the plugin's existing enterprise integrations (Jira, Bamboo, SonarQube, Bitbucket) as tools.

**Architecture:** LLM-brained orchestrator (persistent chat session for planning/routing) dispatches serialized workers (Analyzer, Coder, Reviewer, Tooler), each with filtered tool schemas and fresh conversation state. A Complexity Router fast-paths simple tasks. The Kotlin runtime handles dispatch, checkpointing, context compression, file guarding, and PSI access via `ReadAction.nonBlocking().inSmartMode()`. Cody CLI serves as a supplementary context enrichment service only.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, OkHttp (HTTP to Sourcegraph `/chat/completions`), kotlinx.serialization (tool schemas, API payloads), IntelliJ PSI APIs, IntelliJ UI DSL v2 (Agent tab).

**Hard Prerequisites:**
- Self-hosted Sourcegraph with `openaicompatible` provider configured
- Enterprise `enhanced-context-window` flag enabled (150K input, 64K output)
- The underlying LLM must support OpenAI-compatible tool/function calling

**Spec:** `~/Documents/Agentic_AI_Plugin_Architecture_Research_20260317/research_report_20260317_agentic_ai_plugin_architecture.md` (v2.0)

---

## File Structure

### New Module: `:agent`

```
agent/
  build.gradle.kts
  src/main/kotlin/com/workflow/orchestrator/agent/
    api/
      SourcegraphChatClient.kt          — HTTP client for /chat/completions with tool calling
      dto/
        ChatCompletionModels.kt          — Request/response DTOs (OpenAI-compatible format)
        ToolCallModels.kt                — Tool definition, tool call, tool result DTOs
    brain/
      LlmBrain.kt                        — Interface: sendMessage, sendWithTools, estimateTokens
      OpenAiCompatBrain.kt               — Implementation via SourcegraphChatClient
      CodyContextService.kt              — Supplementary: uses existing Cody CLI for code search
    context/
      ContextManager.kt                  — Two-threshold compression, anchored summaries
      TokenEstimator.kt                  — Token counting utility (char/4 heuristic + calibration)
      ToolResultCompressor.kt            — Summarizes tool results before storing in history
    tools/
      AgentTool.kt                        — Interface: name, description, parameters, execute
      ToolRegistry.kt                     — Registers tools, generates filtered schemas per worker
      builtin/
        ReadFileTool.kt                   — Read file with optional line range
        EditFileTool.kt                   — Precise string replacement in files
        SearchCodeTool.kt                 — Grep-like code search
        RunCommandTool.kt                 — Terminal execution with approval gate
        DiagnosticsTool.kt                — IDE errors/warnings via PSI inspections
      psi/
        FindReferencesTool.kt             — PSI-powered reference lookup
        FindDefinitionTool.kt             — Go-to-definition via PSI
        TypeHierarchyTool.kt              — Class hierarchy traversal
        CallHierarchyTool.kt              — Caller/callee analysis
        FileStructureTool.kt              — Module-level overview (classes, methods, fields)
      integration/
        JiraGetTicketTool.kt              — Wraps existing JiraApiClient
        JiraTransitionTool.kt             — Wraps existing transition API
        JiraCommentTool.kt                — Wraps existing comment API
        BambooBuildTool.kt                — Wraps existing BambooApiClient
        SonarIssuesTool.kt                — Wraps existing SonarApiClient
        BitbucketPrTool.kt                — Wraps existing PrService
        ConfluenceSearchTool.kt           — New: Confluence REST API search
    runtime/
      AgentRuntime.kt                     — Execution engine: dispatch, checkpoint, progress
      ComplexityRouter.kt                 — Single LLM call: simple (fast path) vs complex
      WorkerType.kt                       — Enum: ANALYZER, CODER, REVIEWER, TOOLER
      WorkerSession.kt                    — Single worker lifecycle: create, execute, summarize
      TaskGraph.kt                        — DAG of agent tasks with dependency tracking
      FileGuard.kt                        — Prevents concurrent edit conflicts, manages snapshots
      CheckpointStore.kt                  — Persists task state to .workflow/agent/
    orchestrator/
      AgentOrchestrator.kt                — LLM-brained orchestrator: plans, routes, re-plans
      OrchestratorPrompts.kt              — System prompts for orchestrator and each worker type
    security/
      InputSanitizer.kt                   — Sanitizes external data (Jira, BB, Confluence)
      OutputValidator.kt                  — Checks agent output for sensitive data patterns
    ui/
      AgentTabProvider.kt                 — WorkflowTabProvider for "Agent" tab (order=5)
      AgentDashboardPanel.kt              — Main panel: task plan tree, progress, controls
      AgentPlanTreeModel.kt               — Tree model for plan display with status icons
      DiffPreviewPanel.kt                 — Shows agent edits as diffs before applying
      TokenBudgetWidget.kt                — Live token usage display
      AgentProgressNotifier.kt            — Notification-based progress updates
    settings/
      AgentSettingsConfigurable.kt        — Settings page: Sourcegraph API, model, token budgets
      AgentSettings.kt                    — Persistent state for agent-specific settings
  src/test/kotlin/com/workflow/orchestrator/agent/
    api/
      SourcegraphChatClientTest.kt
    brain/
      OpenAiCompatBrainTest.kt
      CodyContextServiceTest.kt
    context/
      ContextManagerTest.kt
      TokenEstimatorTest.kt
      ToolResultCompressorTest.kt
    tools/
      ToolRegistryTest.kt
      builtin/
        ReadFileToolTest.kt
        EditFileToolTest.kt
        SearchCodeToolTest.kt
        DiagnosticsToolTest.kt
      psi/
        FindReferencesToolTest.kt
        TypeHierarchyToolTest.kt
        FileStructureToolTest.kt
      integration/
        JiraGetTicketToolTest.kt
        BambooBuildToolTest.kt
    runtime/
      ComplexityRouterTest.kt
      TaskGraphTest.kt
      FileGuardTest.kt
      CheckpointStoreTest.kt
      WorkerSessionTest.kt
    orchestrator/
      AgentOrchestratorTest.kt
    security/
      InputSanitizerTest.kt
      OutputValidatorTest.kt
```

### Modified Files

```
settings.gradle.kts                      — Add `:agent` include
src/main/resources/META-INF/plugin.xml   — Register agent tab, settings, notification group, services
core/src/.../settings/PluginSettings.kt  — Add agent settings fields (sourcegraphChatModel, agentEnabled, etc.)
core/src/.../events/EventBus.kt          — Add AgentTaskStarted, AgentTaskCompleted, AgentTaskFailed events
```

---

## Task 1: Module Scaffolding and Build Configuration

**Files:**
- Create: `agent/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add `:agent` to settings.gradle.kts**

In `settings.gradle.kts`, add `:agent` to the `include()` block:

```kotlin
include(
    ":core",
    ":jira",
    ":git-integration",
    ":bamboo",
    ":sonar",
    ":cody",
    ":automation",
    ":handover",
    ":agent",
    ":mock-server",
)
```

- [ ] **Step 2: Create agent/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        bundledPlugins(listOf("Git4Idea", "com.intellij.java"))
    }

    implementation(project(":core"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.coroutines.jdk8)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Create package directories**

Create the directory structure under `agent/src/main/kotlin/com/workflow/orchestrator/agent/` with subdirectories: `api/dto/`, `brain/`, `context/`, `tools/builtin/`, `tools/psi/`, `tools/integration/`, `runtime/`, `orchestrator/`, `security/`, `ui/`, `settings/`.

Create test mirrors under `agent/src/test/kotlin/com/workflow/orchestrator/agent/`.

- [ ] **Step 4: Verify Gradle sync**

Run: `./gradlew :agent:dependencies`
Expected: Resolves dependencies successfully, shows :core as project dependency.

- [ ] **Step 5: Commit**

```bash
git add agent/ settings.gradle.kts
git commit -m "feat(agent): scaffold :agent module with build configuration"
```

---

## Task 2: OpenAI-Compatible Chat API Client

**Files:**
- Create: `agent/src/.../api/dto/ChatCompletionModels.kt`
- Create: `agent/src/.../api/dto/ToolCallModels.kt`
- Create: `agent/src/.../api/SourcegraphChatClient.kt`
- Test: `agent/src/test/.../api/SourcegraphChatClientTest.kt`

- [ ] **Step 1: Write ChatCompletionModels DTOs**

These model the OpenAI-compatible `/chat/completions` request/response format. Use `@Serializable` from kotlinx.serialization.

```kotlin
// ChatCompletionModels.kt
package com.workflow.orchestrator.agent.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null, // "auto", "none", or specific
    val temperature: Double = 0.0,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: UsageInfo? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)
```

- [ ] **Step 2: Write ToolCallModels DTOs**

```kotlin
// ToolCallModels.kt
package com.workflow.orchestrator.agent.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, ParameterProperty>,
    val required: List<String> = emptyList()
)

@Serializable
data class ParameterProperty(
    val type: String,
    val description: String,
    @SerialName("enum") val enumValues: List<String>? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // JSON string of arguments
)
```

- [ ] **Step 3: Write failing test for SourcegraphChatClient**

```kotlin
// SourcegraphChatClientTest.kt
class SourcegraphChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SourcegraphChatClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SourcegraphChatClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "sgp_test-token" },
            model = "anthropic/claude-sonnet-4"
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `sendMessage sends correct request format`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result.isSuccess)
        val response = (result as ApiResult.Success).data
        assertEquals("Hello", response.choices.first().message.content)
        assertEquals(15, response.usage?.totalTokens)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/chat/completions"))
        assertEquals("token sgp_test-token", request.getHeader("Authorization"))
    }

    @Test
    fun `sendMessage with tools includes tool definitions`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"chatcmpl-2","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"/src/Main.kt\"}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()))

        val tools = listOf(ToolDefinition(function = FunctionDefinition(
            name = "read_file",
            description = "Read a file",
            parameters = FunctionParameters(
                properties = mapOf("path" to ParameterProperty(type = "string", description = "File path")),
                required = listOf("path")
            )
        )))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Read Main.kt")),
            tools = tools
        )

        assertTrue(result.isSuccess)
        val response = (result as ApiResult.Success).data
        val toolCall = response.choices.first().message.toolCalls?.first()
        assertNotNull(toolCall)
        assertEquals("read_file", toolCall!!.function.name)
    }

    @Test
    fun `sendMessage handles 429 rate limit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.RATE_LIMITED, (result as ApiResult.Error).type)
    }

    @Test
    fun `sendMessage handles 401 unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client.sendMessage(
            messages = listOf(ChatMessage(role = "user", content = "Hi")),
            tools = null
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.SourcegraphChatClientTest" -v`
Expected: FAIL — `SourcegraphChatClient` class not found.

- [ ] **Step 5: Implement SourcegraphChatClient**

```kotlin
// SourcegraphChatClient.kt
package com.workflow.orchestrator.agent.api

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SourcegraphChatClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120
) {
    private val log = Logger.getInstance(SourcegraphChatClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Uses longer read timeout (120s) than default (30s) because LLM calls are slow.
    // RetryInterceptor handles 429/5xx with exponential backoff (1s, 2s, 4s).
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .addInterceptor(RetryInterceptor())
            .build()
    }

    suspend fun sendMessage(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int? = null,
        temperature: Double = 0.0
    ): ApiResult<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                tools = tools?.takeIf { it.isNotEmpty() },
                toolChoice = if (tools?.isNotEmpty() == true) "auto" else null,
                temperature = temperature,
                maxTokens = maxTokens
            )

            val jsonBody = json.encodeToString(request)
            log.debug("[Agent:API] POST /chat/completions (${jsonBody.length} chars)")

            val httpRequest = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val body = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                    log.debug("[Agent:API] Response: ${parsed.usage?.totalTokens} tokens")
                    ApiResult.Success(parsed)
                }
                response.code == 401 || response.code == 403 -> {
                    ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed (${response.code})")
                }
                response.code == 429 -> {
                    ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limited. Retry after delay.")
                }
                response.code in 500..599 -> {
                    ApiResult.Error(ErrorType.SERVER_ERROR, "Server error (${response.code}): $body")
                }
                else -> {
                    ApiResult.Error(ErrorType.VALIDATION_ERROR, "Unexpected response (${response.code}): $body")
                }
            }
        } catch (e: IOException) {
            log.warn("[Agent:API] Network error: ${e.message}", e)
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Network error: ${e.message}", e)
        } catch (e: Exception) {
            log.error("[Agent:API] Unexpected error: ${e.message}", e)
            ApiResult.Error(ErrorType.PARSE_ERROR, "Unexpected error: ${e.message}", e)
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*.SourcegraphChatClientTest" -v`
Expected: All 4 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add Sourcegraph chat completions API client with tool calling support"
```

---

## Task 3: LlmBrain Interface and OpenAiCompatBrain

**Files:**
- Create: `agent/src/.../brain/LlmBrain.kt`
- Create: `agent/src/.../brain/OpenAiCompatBrain.kt`
- Create: `agent/src/.../context/TokenEstimator.kt`
- Test: `agent/src/test/.../brain/OpenAiCompatBrainTest.kt`
- Test: `agent/src/test/.../context/TokenEstimatorTest.kt`

- [ ] **Step 1: Write LlmBrain interface**

```kotlin
// LlmBrain.kt
package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Abstraction over LLM providers. Allows swapping between
 * Sourcegraph OpenAI-compatible API, future Cody CLI tool support,
 * or other providers without changing agent logic.
 */
interface LlmBrain {
    /** Send a multi-turn conversation with optional tool definitions. */
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        maxTokens: Int? = null
    ): ApiResult<ChatCompletionResponse>

    /** Estimate token count for a string (for budget tracking). */
    fun estimateTokens(text: String): Int

    /** The model identifier being used. */
    val modelId: String
}
```

- [ ] **Step 2: Write TokenEstimator**

```kotlin
// TokenEstimator.kt
package com.workflow.orchestrator.agent.context

/**
 * Estimates token count using character-based heuristic.
 * OpenAI/Anthropic models average ~4 characters per token for English text,
 * ~3.5 for code. We use 3.5 as a conservative estimate for code-heavy context.
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.5

    fun estimate(text: String): Int = (text.length / CHARS_PER_TOKEN).toInt() + 1

    fun estimate(messages: List<com.workflow.orchestrator.agent.api.dto.ChatMessage>): Int {
        return messages.sumOf { msg ->
            val contentTokens = estimate(msg.content ?: "")
            val toolCallTokens = msg.toolCalls?.sumOf { tc ->
                estimate(tc.function.name) + estimate(tc.function.arguments)
            } ?: 0
            contentTokens + toolCallTokens + 4 // 4 tokens overhead per message (role, separators)
        }
    }
}
```

- [ ] **Step 3: Write failing test for TokenEstimator**

```kotlin
// TokenEstimatorTest.kt
class TokenEstimatorTest {
    @Test
    fun `estimate returns reasonable count for English text`() {
        // "Hello world" = 11 chars, ~3.14 tokens at 3.5 chars/token
        val count = TokenEstimator.estimate("Hello world")
        assertTrue(count in 3..5, "Expected 3-5 tokens, got $count")
    }

    @Test
    fun `estimate returns reasonable count for code`() {
        val code = "fun main() {\n    println(\"Hello\")\n}"
        val count = TokenEstimator.estimate(code)
        assertTrue(count in 8..14, "Expected 8-14 tokens for code snippet, got $count")
    }

    @Test
    fun `estimate handles empty string`() {
        assertEquals(1, TokenEstimator.estimate("")) // +1 minimum
    }

    @Test
    fun `estimate messages includes overhead`() {
        val messages = listOf(
            ChatMessage(role = "user", content = "Hello")
        )
        val count = TokenEstimator.estimate(messages)
        assertTrue(count > TokenEstimator.estimate("Hello"), "Message estimate should include overhead")
    }
}
```

- [ ] **Step 4: Run TokenEstimator test to verify it fails, then passes after implementation**

Run: `./gradlew :agent:test --tests "*.TokenEstimatorTest" -v`

- [ ] **Step 5: Write failing test for OpenAiCompatBrain**

```kotlin
// OpenAiCompatBrainTest.kt
class OpenAiCompatBrainTest {
    private lateinit var server: MockWebServer
    private lateinit var brain: OpenAiCompatBrain

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        brain = OpenAiCompatBrain(
            sourcegraphUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "sgp_test" },
            model = "anthropic/claude-sonnet-4"
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    @Test
    fun `chat delegates to SourcegraphChatClient`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}
        """.trimIndent()))

        val result = brain.chat(
            messages = listOf(ChatMessage(role = "user", content = "Plan this task"))
        )

        assertTrue(result.isSuccess)
        assertEquals("Done", (result as ApiResult.Success).data.choices.first().message.content)
    }

    @Test
    fun `estimateTokens returns consistent estimates`() {
        val tokens = brain.estimateTokens("Hello world")
        assertTrue(tokens > 0)
        assertTrue(tokens < 10)
    }

    @Test
    fun `modelId returns configured model`() {
        assertEquals("anthropic/claude-sonnet-4", brain.modelId)
    }
}
```

- [ ] **Step 6: Implement OpenAiCompatBrain**

```kotlin
// OpenAiCompatBrain.kt
package com.workflow.orchestrator.agent.brain

import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.core.model.ApiResult

class OpenAiCompatBrain(
    sourcegraphUrl: String,
    tokenProvider: () -> String?,
    private val model: String,
    connectTimeoutSeconds: Long = 30,
    readTimeoutSeconds: Long = 120
) : LlmBrain {

    private val client = SourcegraphChatClient(
        baseUrl = sourcegraphUrl,
        tokenProvider = tokenProvider,
        model = model,
        connectTimeoutSeconds = connectTimeoutSeconds,
        readTimeoutSeconds = readTimeoutSeconds
    )

    override val modelId: String = model

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?
    ): ApiResult<ChatCompletionResponse> {
        return client.sendMessage(
            messages = messages,
            tools = tools,
            maxTokens = maxTokens
        )
    }

    override fun estimateTokens(text: String): Int = TokenEstimator.estimate(text)
}
```

- [ ] **Step 7: Run tests**

Run: `./gradlew :agent:test --tests "*.OpenAiCompatBrainTest" --tests "*.TokenEstimatorTest" -v`
Expected: All PASS.

- [ ] **Step 8: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add LlmBrain interface, OpenAiCompatBrain, and TokenEstimator"
```

---

## Task 4: Agent Tool System — Interface, Registry, and Built-in Tools

**Files:**
- Create: `agent/src/.../tools/AgentTool.kt`
- Create: `agent/src/.../tools/ToolRegistry.kt`
- Create: `agent/src/.../tools/builtin/ReadFileTool.kt`
- Create: `agent/src/.../tools/builtin/EditFileTool.kt`
- Create: `agent/src/.../tools/builtin/SearchCodeTool.kt`
- Create: `agent/src/.../tools/builtin/RunCommandTool.kt`
- Create: `agent/src/.../tools/builtin/DiagnosticsTool.kt`
- Test: `agent/src/test/.../tools/ToolRegistryTest.kt`
- Test: `agent/src/test/.../tools/builtin/ReadFileToolTest.kt`
- Test: `agent/src/test/.../tools/builtin/EditFileToolTest.kt`
- Test: `agent/src/test/.../tools/builtin/SearchCodeToolTest.kt`

- [ ] **Step 1: Write AgentTool interface**

```kotlin
// AgentTool.kt
package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
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
    val artifacts: List<String> = emptyList(), // file paths created/modified
    val isError: Boolean = false
)
```

- [ ] **Step 2: Write WorkerType enum**

```kotlin
// WorkerType.kt
package com.workflow.orchestrator.agent.runtime

enum class WorkerType {
    ORCHESTRATOR,
    ANALYZER,
    CODER,
    REVIEWER,
    TOOLER
}
```

- [ ] **Step 3: Write ToolRegistry**

```kotlin
// ToolRegistry.kt
package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import com.workflow.orchestrator.agent.runtime.WorkerType

class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): AgentTool? = tools[name]

    fun getToolsForWorker(workerType: WorkerType): List<AgentTool> {
        return tools.values.filter { workerType in it.allowedWorkers }
    }

    fun getToolDefinitionsForWorker(workerType: WorkerType): List<ToolDefinition> {
        return getToolsForWorker(workerType).map { it.toToolDefinition() }
    }

    fun allTools(): Collection<AgentTool> = tools.values.toList()
}
```

- [ ] **Step 4: Write ToolRegistryTest**

```kotlin
// ToolRegistryTest.kt
class ToolRegistryTest {
    @Test
    fun `register and retrieve tool by name`() {
        val registry = ToolRegistry()
        val tool = FakeAgentTool(name = "read_file", allowedWorkers = setOf(WorkerType.CODER))
        registry.register(tool)
        assertEquals(tool, registry.getTool("read_file"))
    }

    @Test
    fun `getToolsForWorker filters by worker type`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER, WorkerType.REVIEWER)))
        registry.register(FakeAgentTool("edit_file", setOf(WorkerType.CODER)))
        registry.register(FakeAgentTool("jira_get", setOf(WorkerType.TOOLER)))

        val coderTools = registry.getToolsForWorker(WorkerType.CODER)
        assertEquals(2, coderTools.size)
        assertTrue(coderTools.any { it.name == "read_file" })
        assertTrue(coderTools.any { it.name == "edit_file" })

        val toolerTools = registry.getToolsForWorker(WorkerType.TOOLER)
        assertEquals(1, toolerTools.size)
        assertEquals("jira_get", toolerTools.first().name)
    }

    @Test
    fun `getToolDefinitionsForWorker returns OpenAI-compatible schemas`() {
        val registry = ToolRegistry()
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER)))

        val defs = registry.getToolDefinitionsForWorker(WorkerType.CODER)
        assertEquals(1, defs.size)
        assertEquals("function", defs.first().type)
        assertEquals("read_file", defs.first().function.name)
    }
}

// Test helper — place in a shared test fixtures location
class FakeAgentTool(
    override val name: String,
    override val allowedWorkers: Set<WorkerType>,
    override val description: String = "Fake tool for testing",
    override val parameters: FunctionParameters = FunctionParameters(properties = emptyMap())
) : AgentTool {
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult(content = "fake result", summary = "fake", tokenEstimate = 10)
    }
}
```

- [ ] **Step 5: Run ToolRegistry tests**

Run: `./gradlew :agent:test --tests "*.ToolRegistryTest" -v`
Expected: All PASS.

- [ ] **Step 6: Implement ReadFileTool**

```kotlin
// ReadFileTool.kt
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read the contents of a file. Use offset and limit for large files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path"),
            "offset" to ParameterProperty(type = "integer", description = "Starting line number (1-based). Optional."),
            "limit" to ParameterProperty(type = "integer", description = "Max lines to read. Optional, defaults to 200.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", 5, isError = true)

        val path = if (rawPath.startsWith("/")) rawPath
            else "${project.basePath}/$rawPath"

        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $path", "Error: file not found", 5, isError = true)
        }

        val lines = file.readText(Charsets.UTF_8).lines()
        val offset = (params["offset"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1) - 1
        val limit = params["limit"]?.jsonPrimitive?.int ?: 200

        val selectedLines = lines.drop(offset).take(limit)
        val content = selectedLines.mapIndexed { idx, line ->
            "${offset + idx + 1}\t$line"
        }.joinToString("\n")

        val truncated = if (offset + limit < lines.size) "\n... (${lines.size - offset - limit} more lines)" else ""
        val fullContent = content + truncated

        return ToolResult(
            content = fullContent,
            summary = "Read ${selectedLines.size} lines from $rawPath (${lines.size} total)",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }
}
```

- [ ] **Step 7: Write ReadFileTool test and verify**

```kotlin
// ReadFileToolTest.kt — uses a temp file, no IntelliJ platform needed
class ReadFileToolTest {
    @Test
    fun `execute reads file content with line numbers`() = runTest {
        val tmpFile = File.createTempFile("test", ".kt").apply {
            writeText("line1\nline2\nline3\nline4\nline5")
            deleteOnExit()
        }
        val tool = ReadFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
        }

        // Mock project with basePath
        val project = mockk<Project> { every { basePath } returns "/tmp" }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("1\tline1"))
        assertTrue(result.content.contains("5\tline5"))
        assertTrue(result.summary.contains("5 lines"))
    }

    @Test
    fun `execute with offset and limit`() = runTest {
        val tmpFile = File.createTempFile("test", ".kt").apply {
            writeText((1..100).joinToString("\n") { "line $it" })
            deleteOnExit()
        }
        val tool = ReadFileTool()
        val params = buildJsonObject {
            put("path", tmpFile.absolutePath)
            put("offset", 10)
            put("limit", 5)
        }
        val project = mockk<Project> { every { basePath } returns "/tmp" }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("10\tline 10"))
        assertTrue(result.content.contains("14\tline 14"))
        assertTrue(result.summary.contains("5 lines"))
    }

    @Test
    fun `execute returns error for missing file`() = runTest {
        val tool = ReadFileTool()
        val params = buildJsonObject { put("path", "/nonexistent/file.kt") }
        val project = mockk<Project> { every { basePath } returns "/tmp" }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("not found"))
    }
}
```

- [ ] **Step 8: Implement EditFileTool, SearchCodeTool similarly**

Follow the same pattern as ReadFileTool. EditFileTool performs string replacement. SearchCodeTool uses grep-like matching. Each tool:
- Has typed parameters with `FunctionParameters`
- Is assigned to specific `WorkerType`s
- Returns `ToolResult` with content, summary, and token estimate
- Has unit tests with temp files

- [ ] **Step 9: Implement DiagnosticsTool (PSI-based)**

This tool uses `ReadAction.nonBlocking().inSmartMode()` pattern. It accesses IntelliJ's `DaemonCodeAnalyzer` or inspection APIs to return errors/warnings for a file. Only available to CODER and REVIEWER workers.

Note: This tool requires IntelliJ platform testing (`BasePlatformTestCase`) — write a simple smoke test, full testing in Phase 3D.

- [ ] **Step 10: Implement RunCommandTool**

Executes shell commands with a 60-second timeout. Captures stdout and stderr. Limits output to 4000 characters. Only available to CODER worker. Includes a blocklist of dangerous commands (`rm -rf /`, `sudo`, etc.).

- [ ] **Step 11: Run all tool tests**

Run: `./gradlew :agent:test --tests "*.tools.*" -v`
Expected: All PASS.

- [ ] **Step 12: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add tool system — AgentTool interface, ToolRegistry, 5 built-in tools"
```

---

## Task 5: PSI-Powered Code Intelligence Tools

**Files:**
- Create: `agent/src/.../tools/psi/FindReferencesTool.kt`
- Create: `agent/src/.../tools/psi/FindDefinitionTool.kt`
- Create: `agent/src/.../tools/psi/TypeHierarchyTool.kt`
- Create: `agent/src/.../tools/psi/CallHierarchyTool.kt`
- Create: `agent/src/.../tools/psi/FileStructureTool.kt`
- Create: `agent/src/.../tools/psi/PsiToolUtils.kt` — shared PSI access utilities
- Test: `agent/src/test/.../tools/psi/FileStructureToolTest.kt`
- Test: `agent/src/test/.../tools/psi/FindReferencesToolTest.kt`
- Test: `agent/src/test/.../tools/psi/TypeHierarchyToolTest.kt`

- [ ] **Step 1: Create PsiToolUtils — shared PSI access helpers**

```kotlin
// PsiToolUtils.kt
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object PsiToolUtils {

    fun isDumb(project: Project): Boolean = DumbService.isDumb(project)

    fun dumbModeError(): ToolResult = ToolResult(
        content = "Error: IDE is still indexing. Try again in a few seconds.",
        summary = "Error: indexing in progress",
        tokenEstimate = 10,
        isError = true
    )

    /** Find a PsiClass by fully qualified name or simple name. */
    fun findClass(project: Project, className: String): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Try fully qualified first
        facade.findClass(className, scope)?.let { return it }

        // Fall back to short name search
        val shortName = className.substringAfterLast('.')
        val classes = facade.findClasses(shortName, scope)
        // Prefer exact simple name match, then first result
        return classes.firstOrNull { it.name == shortName }
    }

    /** Format a PsiMethod signature as a concise string. */
    fun formatMethodSignature(method: PsiMethod): String {
        val modifiers = method.modifierList.text.trim()
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        val annotations = method.annotations
            .filter { it.qualifiedName?.startsWith("org.springframework") == true || it.qualifiedName?.startsWith("jakarta") == true }
            .joinToString(" ") { "@${it.qualifiedName?.substringAfterLast('.') ?: ""}" }
        val prefix = if (annotations.isNotBlank()) "$annotations " else ""
        return "$prefix$modifiers $returnType ${method.name}($params)"
    }

    /** Format a PsiClass as a skeleton (signatures only, no bodies). */
    fun formatClassSkeleton(psiClass: PsiClass): String {
        val sb = StringBuilder()
        // Package
        (psiClass.containingFile as? PsiJavaFile)?.packageName?.let {
            if (it.isNotBlank()) sb.appendLine("package $it;")
        }
        // Class declaration
        val superTypes = psiClass.superTypes.map { it.presentableText }
        val extendsClause = if (superTypes.isNotEmpty()) " extends/implements ${superTypes.joinToString(", ")}" else ""
        sb.appendLine("${psiClass.modifierList?.text ?: ""} class ${psiClass.name}$extendsClause {")

        // Fields
        psiClass.fields.forEach { field ->
            sb.appendLine("    ${field.modifierList?.text ?: ""} ${field.type.presentableText} ${field.name};")
        }

        // Methods (signature only)
        psiClass.methods.forEach { method ->
            sb.appendLine("    ${formatMethodSignature(method)};")
        }

        sb.appendLine("}")
        return sb.toString()
    }
}
```

- [ ] **Step 2: Implement FileStructureTool**

Returns a skeleton of a file — class declarations, method signatures, field declarations — without method bodies. This is the key tool for token-efficient context.

```kotlin
// FileStructureTool.kt
package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class FileStructureTool : AgentTool {
    override val name = "file_structure"
    override val description = "Get the structure of a file: class declarations, method signatures, fields. No method bodies — use read_file for full content."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error: missing path", 5, isError = true)

        val path = if (rawPath.startsWith("/")) rawPath else "${project.basePath}/$rawPath"
        val vFile = LocalFileSystem.getInstance().findFileByPath(path)
            ?: return ToolResult("Error: File not found: $path", "Error: file not found", 5, isError = true)

        // Use nonBlocking read action per spec Finding 21 — avoids blocking EDT and write actions
        val content = ReadAction.nonBlocking<String> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
            if (psiFile is PsiJavaFile) {
                psiFile.classes.joinToString("\n\n") { PsiToolUtils.formatClassSkeleton(it) }
            } else {
                // Non-Java files: return first 50 lines as fallback
                val text = psiFile?.text ?: return@nonBlocking "Error: Cannot read file"
                text.lines().take(50).joinToString("\n")
            }
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Structure of $rawPath (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
```

- [ ] **Step 3: Implement FindReferencesTool, FindDefinitionTool, TypeHierarchyTool, CallHierarchyTool**

Each follows the same pattern:
- Check `DumbService.isDumb()` first
- Use `ReadAction.compute` for PSI access
- Accept a symbol name (class or method) and optional file path for disambiguation
- Return formatted results with `TokenEstimator.estimate()`
- Only available to ANALYZER worker type

- [ ] **Step 4: Write PSI tool tests**

PSI tools require IntelliJ platform test infrastructure. Write tests that:
- Use fixture Java files with known class/method structures
- Test skeleton extraction accuracy
- Test reference finding
- Test type hierarchy traversal
- Verify DumbService gating returns error

For unit-level testing without full platform: mock PSI elements and test `PsiToolUtils.formatClassSkeleton()` and `formatMethodSignature()`.

- [ ] **Step 5: Run PSI tool tests**

Run: `./gradlew :agent:test --tests "*.tools.psi.*" -v`
Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add PSI-powered code intelligence tools — structure, references, hierarchy"
```

---

## Task 6: Integration Tools — Wrapping Existing API Clients

**Files:**
- Create: `agent/src/.../tools/integration/JiraGetTicketTool.kt`
- Create: `agent/src/.../tools/integration/JiraTransitionTool.kt`
- Create: `agent/src/.../tools/integration/JiraCommentTool.kt`
- Create: `agent/src/.../tools/integration/BambooBuildTool.kt`
- Create: `agent/src/.../tools/integration/SonarIssuesTool.kt`
- Create: `agent/src/.../tools/integration/BitbucketPrTool.kt`
- Test: `agent/src/test/.../tools/integration/JiraGetTicketToolTest.kt`
- Test: `agent/src/test/.../tools/integration/BambooBuildToolTest.kt`

- [ ] **Step 1: Implement JiraGetTicketTool**

This tool wraps the existing `JiraApiClient.getIssue()`. Since `:agent` depends on `:core` but NOT on `:jira`, we need a different approach — create the JiraApiClient directly from settings (same pattern as tab providers do).

```kotlin
// JiraGetTicketTool.kt
package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.security.InputSanitizer
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class JiraGetTicketTool : AgentTool {
    override val name = "jira_get_ticket"
    override val description = "Get Jira ticket details: summary, description, status, assignee, comments, linked issues."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Jira issue key, e.g., PROJ-123")
        ),
        required = listOf("key")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' required", "Error: missing key", 5, isError = true)

        val settings = ConnectionSettings.getInstance()
        val baseUrl = settings.state.jiraUrl?.trimEnd('/')
            ?: return ToolResult("Error: Jira URL not configured", "Error: no Jira URL", 5, isError = true)

        val token = CredentialStore().getToken(ServiceType.JIRA)
            ?: return ToolResult("Error: Jira token not configured", "Error: no Jira token", 5, isError = true)

        return withContext(Dispatchers.IO) {
            try {
                // Reuse core's AuthInterceptor and RetryInterceptor for consistent auth/retry behavior
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .addInterceptor(com.workflow.orchestrator.core.http.AuthInterceptor({ token }, com.workflow.orchestrator.core.http.AuthScheme.BEARER))
                    .addInterceptor(com.workflow.orchestrator.core.http.RetryInterceptor())
                    .build()
                val request = Request.Builder()
                    .url("$baseUrl/rest/api/2/issue/$key?expand=renderedFields")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ToolResult(
                        "Error: Jira API returned ${response.code}", "Error: HTTP ${response.code}", 5, isError = true
                    )
                }

                // Sanitize before returning to LLM context
                val sanitized = InputSanitizer.sanitizeExternalData(body, "jira", key, maxTokens = 3000)

                ToolResult(
                    content = sanitized,
                    summary = "Jira ticket $key retrieved (${sanitized.length} chars)",
                    tokenEstimate = TokenEstimator.estimate(sanitized)
                )
            } catch (e: Exception) {
                ToolResult("Error: ${e.message}", "Error: ${e.message}", 5, isError = true)
            }
        }
    }
}
```

- [ ] **Step 2: Implement remaining integration tools**

Follow same pattern for JiraTransitionTool, JiraCommentTool, BambooBuildTool, SonarIssuesTool, BitbucketPrTool. Each wraps an HTTP call using credentials from CredentialStore and URLs from ConnectionSettings. All sanitize external data before returning.

- [ ] **Step 3: Write integration tool tests with MockWebServer**

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew :agent:test --tests "*.tools.integration.*" -v`

```bash
git add agent/src/
git commit -m "feat(agent): add integration tools — Jira, Bamboo, Sonar, Bitbucket wrappers"
```

---

## Task 7: Security — Input Sanitization and Output Validation

**Files:**
- Create: `agent/src/.../security/InputSanitizer.kt`
- Create: `agent/src/.../security/OutputValidator.kt`
- Test: `agent/src/test/.../security/InputSanitizerTest.kt`
- Test: `agent/src/test/.../security/OutputValidatorTest.kt`

- [ ] **Step 1: Write InputSanitizer tests first (TDD)**

```kotlin
// InputSanitizerTest.kt
class InputSanitizerTest {
    @Test
    fun `strips control characters`() {
        val input = "Normal text\u0000\u0001with control chars"
        val result = InputSanitizer.sanitizeExternalData(input, "jira", "KEY-1")
        assertFalse(result.contains("\u0000"))
        assertFalse(result.contains("\u0001"))
    }

    @Test
    fun `wraps in external_data tags`() {
        val result = InputSanitizer.sanitizeExternalData("ticket content", "jira", "KEY-1")
        assertTrue(result.startsWith("<external_data"))
        assertTrue(result.contains("source=\"jira\""))
        assertTrue(result.contains("key=\"KEY-1\""))
        assertTrue(result.endsWith("</external_data>"))
    }

    @Test
    fun `truncates content exceeding max tokens`() {
        val longContent = "a".repeat(50000)
        val result = InputSanitizer.sanitizeExternalData(longContent, "jira", "KEY-1", maxTokens = 100)
        assertTrue(TokenEstimator.estimate(result) < 200) // some overhead from tags
    }

    @Test
    fun `does not strip legitimate code content`() {
        val code = "if (x > 0) { return true; }"
        val result = InputSanitizer.sanitizeExternalData(code, "bitbucket", "PR-1")
        assertTrue(result.contains(code))
    }
}
```

- [ ] **Step 2: Implement InputSanitizer**

- [ ] **Step 3: Write OutputValidator tests**

```kotlin
// OutputValidatorTest.kt
class OutputValidatorTest {
    @Test
    fun `detects SSH key patterns`() {
        val output = "Here is the content: -----BEGIN RSA PRIVATE KEY-----"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.any { it.contains("private key") })
    }

    @Test
    fun `detects environment variable patterns`() {
        val output = "Set AWS_SECRET_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }

    @Test
    fun `passes clean code output`() {
        val output = "fun hello() = println(\"Hello world\")"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `detects sensitive file paths`() {
        val output = "Read the file at ~/.ssh/id_rsa"
        val issues = OutputValidator.validate(output)
        assertTrue(issues.isNotEmpty())
    }
}
```

- [ ] **Step 4: Implement OutputValidator**

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :agent:test --tests "*.security.*" -v`

```bash
git add agent/src/
git commit -m "feat(agent): add security layer — input sanitization and output validation"
```

---

## Task 8: Context Manager — Compression and Token Budget

**Files:**
- Create: `agent/src/.../context/ContextManager.kt`
- Create: `agent/src/.../context/ToolResultCompressor.kt`
- Test: `agent/src/test/.../context/ContextManagerTest.kt`
- Test: `agent/src/test/.../context/ToolResultCompressorTest.kt`

- [ ] **Step 1: Write ToolResultCompressor**

Summarizes tool results before storing in conversation history. Results under 500 tokens pass through. Results over 500 tokens get summarized to a fixed format.

- [ ] **Step 2: Write ContextManager with two-threshold compression**

Manages the conversation history for a worker session. Tracks token budget. When T_max (70% of input limit) is reached, compresses oldest messages to summaries. Keeps anchored summaries.

- [ ] **Step 3: Write comprehensive tests**

- [ ] **Step 4: Run tests and commit**

```bash
git add agent/src/
git commit -m "feat(agent): add context manager with two-threshold compression"
```

---

## Task 9: Agent Runtime — TaskGraph, FileGuard, CheckpointStore, WorkerSession

**Files:**
- Create: `agent/src/.../runtime/TaskGraph.kt`
- Create: `agent/src/.../runtime/FileGuard.kt`
- Create: `agent/src/.../runtime/CheckpointStore.kt`
- Create: `agent/src/.../runtime/WorkerSession.kt`
- Create: `agent/src/.../runtime/AgentRuntime.kt`
- Create: `agent/src/.../runtime/ComplexityRouter.kt`
- Test: `agent/src/test/.../runtime/TaskGraphTest.kt`
- Test: `agent/src/test/.../runtime/FileGuardTest.kt`
- Test: `agent/src/test/.../runtime/CheckpointStoreTest.kt`
- Test: `agent/src/test/.../runtime/WorkerSessionTest.kt`
- Test: `agent/src/test/.../runtime/ComplexityRouterTest.kt`

- [ ] **Step 1: Implement TaskGraph (DAG)**

Pure data structure — nodes are agent tasks with dependencies. Supports: add task, mark complete, get next executable tasks, check if complete. No LLM calls.

- [ ] **Step 2: Write TaskGraph tests (TDD)**

Test: add tasks with dependencies, topological ordering, cycle detection, next-executable identification.

- [ ] **Step 3: Implement FileGuard**

Manages file snapshots via `git stash create`, tracks which files are being edited by the agent, and provides rollback. Check for unsaved IDE editor changes before snapshotting.

- [ ] **Step 4: Implement CheckpointStore**

Serializes TaskGraph state + completed task summaries to `.workflow/agent/checkpoint.json`. Supports save, load, and delete.

- [ ] **Step 5: Implement WorkerSession**

Lifecycle for a single worker: create conversation with system prompt, execute ReAct loop (send message → process tool calls → send results → repeat until done or budget exceeded), return final result.

- [ ] **Step 6: Implement ComplexityRouter**

Single LLM call to classify a task as simple or complex. System prompt: "Classify this task. Respond with exactly 'SIMPLE' or 'COMPLEX'. SIMPLE = single file edit, clear instruction. COMPLEX = multi-file, analysis needed, ambiguous."

- [ ] **Step 7: Implement AgentRuntime**

The execution engine that ties everything together. Dispatches tasks to WorkerSessions, manages FileGuard, updates CheckpointStore, reports progress.

- [ ] **Step 8: Run all runtime tests**

Run: `./gradlew :agent:test --tests "*.runtime.*" -v`

- [ ] **Step 9: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add runtime engine — TaskGraph, FileGuard, CheckpointStore, WorkerSession, ComplexityRouter"
```

---

## Task 10: Agent Orchestrator — LLM-Brained Planning and Routing

**Files:**
- Create: `agent/src/.../orchestrator/AgentOrchestrator.kt`
- Create: `agent/src/.../orchestrator/OrchestratorPrompts.kt`
- Test: `agent/src/test/.../orchestrator/AgentOrchestratorTest.kt`

- [ ] **Step 1: Write OrchestratorPrompts**

System prompts for each role. Keep them concise (under 1500 tokens each). Use XML tags for structure.

- [ ] **Step 2: Implement AgentOrchestrator**

The main entry point. Takes a user task, routes through ComplexityRouter, then either fast-paths (single Coder WorkerSession) or plans via Orchestrator LLM session, gets user approval, and executes the plan.

- [ ] **Step 3: Write orchestrator tests with MockWebServer**

Script LLM responses for: task classification, plan generation, worker execution, re-planning.

- [ ] **Step 4: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add LLM-brained orchestrator with planning and routing"
```

---

## Task 11: Agent Settings — Configuration UI

**Files:**
- Create: `agent/src/.../settings/AgentSettings.kt`
- Create: `agent/src/.../settings/AgentSettingsConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create AgentSettings persistent state**

Fields: `agentEnabled` (Boolean), `sourcegraphChatModel` (String, default "anthropic/claude-sonnet-4"), `maxInputTokens` (Int, default 150000), `maxOutputTokens` (Int, default 64000), `enableFastPath` (Boolean, default true), `tokenBudgetWarningThreshold` (Int, default 80), `approvalRequiredForEdits` (Boolean, default true).

- [ ] **Step 2: Create AgentSettingsConfigurable UI**

IntelliJ UI DSL v2 panel with:
- Checkbox: Enable Agent features
- Text field: Chat model (with dropdown of common models)
- Number fields: Max input/output tokens
- Checkbox: Enable fast path for simple tasks
- Checkbox: Require approval before file edits
- Number field: Token budget warning threshold (%)

- [ ] **Step 3: Register in plugin.xml**

Add settings page, notification group, and service registrations.

- [ ] **Step 4: Commit**

```bash
git add agent/src/ src/main/resources/
git commit -m "feat(agent): add agent settings page with token budget and model configuration"
```

---

## Task 12: Agent Tab UI — Dashboard Panel

**Files:**
- Create: `agent/src/.../ui/AgentTabProvider.kt`
- Create: `agent/src/.../ui/AgentDashboardPanel.kt`
- Create: `agent/src/.../ui/AgentPlanTreeModel.kt`
- Create: `agent/src/.../ui/DiffPreviewPanel.kt`
- Create: `agent/src/.../ui/TokenBudgetWidget.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create AgentTabProvider**

Register as the 6th tab (order=5) in the Workflow tool window. Shows EmptyStatePanel if agent not enabled or Sourcegraph not configured.

- [ ] **Step 2: Create AgentDashboardPanel**

Main panel with:
- Top toolbar: "New Task" button, cancel button, settings shortcut
- Left: Task plan tree (AgentPlanTreeModel) showing steps with status icons
- Right: Task output / diff preview area
- Bottom: Token usage bar, elapsed time, status text

Uses JBSplitter, JBList, JBUI.Borders, JBColor — no standard Swing.

- [ ] **Step 3: Create AgentPlanTreeModel**

Tree model backed by TaskGraph. Each node shows: step name, status (pending/running/done/failed), elapsed time.

- [ ] **Step 4: Create DiffPreviewPanel**

Shows agent-proposed edits as a side-by-side diff. Accept/Reject buttons. Uses IntelliJ's `DiffManager.getInstance().showDiff()` API.

- [ ] **Step 5: Create TokenBudgetWidget**

A progress bar showing current token usage vs budget. Green (<60%), yellow (60-80%), red (>80%).

- [ ] **Step 6: Register in plugin.xml**

```xml
<tabProvider implementation="com.workflow.orchestrator.agent.ui.AgentTabProvider"/>
```

- [ ] **Step 7: Verify in runIde**

Run: `./gradlew runIde`
Expected: Agent tab appears in Workflow tool window. Empty state shown if not configured. Settings page accessible.

- [ ] **Step 8: Commit**

```bash
git add agent/src/ src/main/resources/
git commit -m "feat(agent): add Agent tab UI — dashboard panel, plan tree, diff preview, token budget"
```

---

## Task 13: Integration — Wire Everything Together and Event Bus

**Files:**
- Modify: `core/src/.../events/EventBus.kt` — add agent events
- Modify: `src/main/resources/META-INF/plugin.xml` — register all services
- Create: `agent/src/.../AgentService.kt` — project-level service, entry point

- [ ] **Step 1: Add agent events to EventBus**

```kotlin
// In WorkflowEvent sealed class:
data class AgentTaskStarted(val taskId: String, val description: String) : WorkflowEvent()
data class AgentTaskCompleted(val taskId: String, val summary: String) : WorkflowEvent()
data class AgentTaskFailed(val taskId: String, val error: String) : WorkflowEvent()
data class AgentWorkerProgress(val taskId: String, val step: String, val tokensUsed: Int) : WorkflowEvent()
```

- [ ] **Step 2: Create AgentService**

Project-level service that initializes the ToolRegistry (registers all tools), creates the LlmBrain from settings, and provides the AgentOrchestrator to the UI.

- [ ] **Step 3: Register everything in plugin.xml**

Services, notification group (`workflow.agent`), tab provider, settings configurable.

- [ ] **Step 4: Full test run**

Run: `./gradlew :agent:test -v`
Expected: All tests pass.

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues.

Run: `./gradlew runIde`
Expected: Plugin loads, Agent tab visible, settings configurable, no IDE errors.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(agent): wire agent module — AgentService, events, plugin.xml registration"
```

---

## Task 14: Cody Context Service — Supplementary Code Search

**Files:**
- Create: `agent/src/.../brain/CodyContextService.kt`
- Test: `agent/src/test/.../brain/CodyContextServiceTest.kt`

- [ ] **Step 1: Implement CodyContextService**

Uses the existing `CodyAgentManager` to send a code search query via `chatSubmitMessage`. Returns relevant file paths and summaries. This leverages Cody's built-in code graph without using it as the agent brain.

- [ ] **Step 2: Test with mocked CodyAgentServer**

- [ ] **Step 3: Commit**

```bash
git add agent/src/
git commit -m "feat(agent): add CodyContextService for supplementary code search via Cody CLI"
```

---

## Task 15: Final Verification and Review

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:test -v
./gradlew :core:test -v
./gradlew verifyPlugin
```

- [ ] **Step 2: Run in IDE**

```bash
./gradlew runIde
```

Verify:
- Agent tab appears as 6th tab
- Empty state shown when agent not enabled
- Settings page configurable
- Enabling agent with valid Sourcegraph URL shows active state
- Token budget widget displays

- [ ] **Step 3: Code review checkpoint**

Use `superpowers:requesting-code-review` to get the implementation reviewed against this plan and the architecture spec.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat(agent): Phase 3 — Agentic AI foundation complete

Adds the :agent module with:
- OpenAI-compatible Sourcegraph API client with tool calling
- LlmBrain interface with OpenAiCompatBrain implementation
- 5 built-in tools (read, edit, search, run, diagnostics)
- 5 PSI-powered code intelligence tools (references, definition, hierarchy, call, structure)
- 6 integration tools (Jira, Bamboo, Sonar, Bitbucket, Confluence)
- Context manager with two-threshold compression
- Agent runtime: TaskGraph, FileGuard, CheckpointStore, WorkerSession
- LLM-brained orchestrator with complexity router and re-planning
- Security: input sanitization, output validation
- Agent tab UI with plan tree, diff preview, token budget widget
- Configurable settings: model, token limits, approval gates
- CodyContextService for supplementary code search"
```
