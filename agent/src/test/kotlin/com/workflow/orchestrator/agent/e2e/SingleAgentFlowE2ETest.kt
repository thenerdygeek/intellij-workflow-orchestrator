package com.workflow.orchestrator.agent.e2e

import com.workflow.orchestrator.agent.TestModels
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.brain.OpenAiCompatBrain
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.AgentOrchestrator
import com.workflow.orchestrator.agent.orchestrator.AgentProgress
import com.workflow.orchestrator.agent.orchestrator.AgentResult
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end integration tests for the single agent flow.
 *
 * These tests use MockWebServer to simulate a multi-turn LLM conversation.
 * No mocking of internal components — the full stack runs:
 * AgentOrchestrator → SingleAgentSession → SourcegraphChatClient → HTTP → MockWebServer
 * → response parsing → tool execution → context management → next call
 *
 * The only mock is Project (IntelliJ platform object).
 */
class SingleAgentFlowE2ETest {

    private lateinit var server: MockWebServer
    private lateinit var brain: LlmBrain
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var project: Project
    private lateinit var orchestrator: AgentOrchestrator
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.absolutePath

        // Create a real brain pointing at MockWebServer.
        // Wrap it to disable streaming — MockWebServer returns normal JSON, not SSE,
        // so chatStream() would hang waiting for SSE "data:" lines that never come.
        // Note: MockWebServer URL is the base — SourcegraphChatClient appends /.api/llm/chat/completions
        val baseBrain = OpenAiCompatBrain(
            sourcegraphUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "sgp_test-token" },
            model = TestModels.MOCK_MODEL,
            httpClientOverride = OkHttpClient()
        )
        brain = NonStreamingBrainWrapper(baseBrain)

        // Register real tools (not mocks) — specifically read_file and edit_file
        // which operate on the temp directory
        toolRegistry = ToolRegistry()
        toolRegistry.register(RealReadFileTool())
        toolRegistry.register(RealEditFileTool(tempDir))
        toolRegistry.register(RealSearchCodeTool(tempDir))

        orchestrator = AgentOrchestrator(brain, toolRegistry, project)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ===== TEST 1: Full 3-turn flow (read → edit → confirm) =====

    @Test
    fun `full flow - agent reads file then edits it`() = runTest {
        // Setup: create a real file in temp dir
        val testFile = File(tempDir, "UserService.kt")
        testFile.writeText("""
            class UserService {
                fun getUser(id: String): User? {
                    return null // TODO: implement
                }
            }
        """.trimIndent())

        // Script the LLM conversation:
        // Turn 1: LLM decides to read the file
        // Turn 2: After seeing file content, LLM decides to edit it
        // Turn 3: LLM confirms the edit is done
        val responses = mutableListOf(
            // Turn 1: LLM calls read_file
            buildToolCallResponse("call_1", "read_file", """{"path":"${testFile.absolutePath}"}"""),
            // Turn 2: After seeing file content, LLM calls edit_file
            buildToolCallResponse("call_2", "edit_file", """{"path":"${testFile.absolutePath}","old_string":"return null // TODO: implement","new_string":"return userRepository.findById(id)"}"""),
            // Turn 3: LLM produces final response (no tool calls) — LoopGuard may inject verification
            buildTextResponse("I've updated UserService.getUser() to use the repository instead of returning null."),
            // Turn 4 (verification pass): LoopGuard asks to verify, LLM confirms
            buildTextResponse("Verified: the edit looks correct, no compilation errors.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        // Execute
        val progressUpdates = mutableListOf<AgentProgress>()
        val streamedChunks = mutableListOf<String>()

        val result = orchestrator.executeTask(
            "Fix the TODO in UserService.getUser to use the repository",
            onProgress = { progressUpdates.add(it) },
            onStreamChunk = { streamedChunks.add(it) }
        )

        // Verify result
        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")
        val completed = result as AgentResult.Completed
        // Summary may be from the edit confirmation or the verification pass
        assertFalse(completed.summary.isBlank(), "Summary should not be blank")

        // Verify the file was actually modified
        val updatedContent = testFile.readText()
        assertTrue(updatedContent.contains("userRepository.findById(id)"),
            "File should be modified. Content: $updatedContent")
        assertFalse(updatedContent.contains("TODO: implement"),
            "TODO should be removed. Content: $updatedContent")

        // Verify progress was reported
        assertTrue(progressUpdates.any { it.step.contains("Starting") })
        assertTrue(progressUpdates.any { it.step.contains("Used tool: read_file") })
        assertTrue(progressUpdates.any { it.step.contains("Used tool: edit_file") })

        // Verify HTTP requests (3 core + optional LoopGuard verification pass)
        assertTrue(server.requestCount in 3..4, "Expected 3-4 HTTP requests, got ${server.requestCount}")
    }

    // ===== TEST 2: Error recovery — tool fails, agent adapts =====

    @Test
    fun `error recovery - agent adapts when edit fails`() = runTest {
        val testFile = File(tempDir, "Config.kt")
        testFile.writeText("val timeout = 30")

        val responses = mutableListOf(
            // Turn 1: LLM tries to edit with wrong old_string
            buildToolCallResponse("call_1", "edit_file",
                """{"path":"${testFile.absolutePath}","old_string":"val timeout = 60","new_string":"val timeout = 120"}"""),
            // Turn 2: After getting error, LLM reads the file first
            buildToolCallResponse("call_2", "read_file", """{"path":"${testFile.absolutePath}"}"""),
            // Turn 3: Now LLM edits with correct old_string
            buildToolCallResponse("call_3", "edit_file",
                """{"path":"${testFile.absolutePath}","old_string":"val timeout = 30","new_string":"val timeout = 120"}"""),
            // Turn 4: LLM confirms
            buildTextResponse("Fixed: changed timeout from 30 to 120.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        val result = orchestrator.executeTask("Change timeout to 120")

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")

        // The file should be modified despite the first edit failing
        assertEquals("val timeout = 120", testFile.readText())

        // 4 core LLM calls (failed edit → read → successful edit → confirm) + optional LoopGuard verification
        assertTrue(server.requestCount in 4..5, "Expected 4-5 HTTP requests, got ${server.requestCount}")
    }

    // ===== TEST 3: Approval rejection — agent handles rejected tool =====

    @Test
    fun `approval gate - rejected edit causes agent to report inability`() = runTest {
        val testFile = File(tempDir, "Main.kt")
        testFile.writeText("fun main() {}")

        val responses = mutableListOf(
            // Turn 1: LLM wants to edit
            buildToolCallResponse("call_1", "edit_file",
                """{"path":"${testFile.absolutePath}","old_string":"fun main() {}","new_string":"fun main() { println(\"Hello\") }"}"""),
            // Turn 2: After rejection message, LLM explains it can't proceed
            buildTextResponse("I was unable to modify Main.kt because the edit was rejected. Please approve the edit to proceed.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        // Create an approval gate that rejects all edits
        val gate = ApprovalGate(
            approvalRequired = true,
            onApprovalNeeded = { _, _ -> ApprovalResult.Rejected() }
        )

        val result = orchestrator.executeTask(
            "Add a print statement to main",
            approvalGate = gate
        )

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")

        // File should NOT be modified (edit was rejected)
        assertEquals("fun main() {}", testFile.readText())
    }

    // ===== TEST 4: Multi-tool usage in one turn =====

    @Test
    fun `multi-tool - LLM calls multiple tools in one response`() = runTest {
        val file1 = File(tempDir, "A.kt").apply { writeText("class A") }
        val file2 = File(tempDir, "B.kt").apply { writeText("class B") }

        val responses = mutableListOf(
            // Turn 1: LLM reads both files in one turn
            buildMultiToolCallResponse(listOf(
                Triple("call_1", "read_file", """{"path":"${file1.absolutePath}"}"""),
                Triple("call_2", "read_file", """{"path":"${file2.absolutePath}"}""")
            )),
            // Turn 2: Final response
            buildTextResponse("Both files read. A.kt contains class A, B.kt contains class B.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        val result = orchestrator.executeTask("Read both A.kt and B.kt")

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")
        assertTrue((result as AgentResult.Completed).summary.contains("class A") ||
            result.summary.contains("Both files"),
            "Summary: ${result.summary}")
    }

    // ===== TEST 5: Context carries across turns =====

    @Test
    fun `context preservation - tool results visible in subsequent turns`() = runTest {
        val testFile = File(tempDir, "Data.kt")
        testFile.writeText("data class Data(val name: String, val age: Int)")

        val responses = mutableListOf(
            // Turn 1: Read the file
            buildToolCallResponse("call_1", "read_file", """{"path":"${testFile.absolutePath}"}"""),
            // Turn 2: LLM references what it read (the tool result is in context)
            buildTextResponse("The Data class has two fields: name (String) and age (Int). No changes needed.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        val result = orchestrator.executeTask("Analyze Data.kt")
        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")

        // Verify the second LLM call contains the tool result from the first call
        // by checking that the request body includes the file content
        val secondRequest = server.takeRequest() // first
        val thirdRequest = server.takeRequest()  // second — should contain tool result
        val secondBody = thirdRequest.body.readUtf8()
        assertTrue(secondBody.contains("data class Data") || secondBody.contains("Data.kt"),
            "Second LLM call should include tool result from first call in context")
    }

    // ===== TEST 6: Unknown tool handling =====

    @Test
    fun `unknown tool - agent recovers when LLM hallucinates tool name`() = runTest {
        val responses = mutableListOf(
            // Turn 1: LLM calls a tool that doesn't exist
            buildToolCallResponse("call_1", "deploy_to_production", """{"target":"prod"}"""),
            // Turn 2: After error message listing available tools, LLM adapts
            buildTextResponse("I apologize, I don't have a deployment tool available. The available tools are for reading, editing, and searching code.")
        )

        server.dispatcher = sequentialDispatcher(responses)

        val result = orchestrator.executeTask("Deploy the app")
        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")

        // Verify the error message was sent back to the LLM with available tools
        server.takeRequest() // first call
        val secondRequest = server.takeRequest()
        val body = secondRequest.body.readUtf8()
        assertTrue(body.contains("not found") || body.contains("not available"),
            "Error should be in context for second call")
    }

    // ===== Helpers =====

    private fun buildTextResponse(content: String): String {
        return json.encodeToString(ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(role = "assistant", content = content),
                finishReason = "stop"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        ))
    }

    private fun buildToolCallResponse(callId: String, toolName: String, args: String): String {
        return json.encodeToString(ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(
                        id = callId,
                        function = FunctionCall(name = toolName, arguments = args)
                    ))
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        ))
    }

    private fun buildMultiToolCallResponse(calls: List<Triple<String, String, String>>): String {
        return json.encodeToString(ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = calls.map { (id, name, args) ->
                        ToolCall(id = id, function = FunctionCall(name = name, arguments = args))
                    }
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        ))
    }

    /**
     * Creates a dispatcher that returns responses in order.
     * Each call to /chat/completions gets the next response from the list.
     */
    private fun sequentialDispatcher(responses: MutableList<String>): Dispatcher {
        val index = java.util.concurrent.atomic.AtomicInteger(0)
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val i = index.getAndIncrement()
                return if (i < responses.size) {
                    MockResponse()
                        .setBody(responses[i])
                        .setHeader("Content-Type", "application/json")
                } else {
                    // Safety: return a final response if we run out
                    MockResponse()
                        .setBody(buildTextResponse("Task completed."))
                        .setHeader("Content-Type", "application/json")
                }
            }
        }
    }

    // ===== Real tool implementations for e2e (no mocks) =====

    /** Real read_file that reads from the filesystem */
    class RealReadFileTool : AgentTool {
        override val name = "read_file"
        override val description = "Read a file"
        override val parameters = FunctionParameters(
            properties = mapOf(
                "path" to ParameterProperty(type = "string", description = "File path")
            ),
            required = listOf("path")
        )
        override val allowedWorkers = WorkerType.entries.toSet()

        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            val pathStr = params["path"]?.jsonPrimitive?.content
                ?: return ToolResult("Error: path required", "Error", 5, isError = true)
            val file = File(pathStr)
            if (!file.exists()) {
                return ToolResult("Error: File not found: $pathStr", "Error: not found", 5, isError = true)
            }
            val content = file.readText()
            return ToolResult(content, "Read ${file.name} (${content.lines().size} lines)", content.length / 4)
        }
    }

    /** Real edit_file that modifies the filesystem */
    class RealEditFileTool(private val baseDir: File) : AgentTool {
        override val name = "edit_file"
        override val description = "Edit a file"
        override val parameters = FunctionParameters(
            properties = mapOf(
                "path" to ParameterProperty(type = "string", description = "File path"),
                "old_string" to ParameterProperty(type = "string", description = "Text to replace"),
                "new_string" to ParameterProperty(type = "string", description = "Replacement text")
            ),
            required = listOf("path", "old_string", "new_string")
        )
        override val allowedWorkers = WorkerType.entries.toSet()

        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            val path = params["path"]?.jsonPrimitive?.content
                ?: return ToolResult("Error: path required", "Error", 5, isError = true)
            val oldStr = params["old_string"]?.jsonPrimitive?.content
                ?: return ToolResult("Error: old_string required", "Error", 5, isError = true)
            val newStr = params["new_string"]?.jsonPrimitive?.content
                ?: return ToolResult("Error: new_string required", "Error", 5, isError = true)

            val file = File(path)
            if (!file.exists()) {
                return ToolResult("Error: File not found: $path", "Error", 5, isError = true)
            }

            val content = file.readText()
            if (!content.contains(oldStr)) {
                return ToolResult(
                    "Error: old_string not found in file. File content:\n${content.take(500)}",
                    "Error: old_string not found", 5, isError = true
                )
            }

            val newContent = content.replace(oldStr, newStr)
            file.writeText(newContent)
            return ToolResult("Edited $path", "Replaced text in ${file.name}", 10, artifacts = listOf(path))
        }
    }

    /** Real search_code that searches files */
    class RealSearchCodeTool(private val baseDir: File) : AgentTool {
        override val name = "search_code"
        override val description = "Search code"
        override val parameters = FunctionParameters(
            properties = mapOf(
                "query" to ParameterProperty(type = "string", description = "Search query")
            ),
            required = listOf("query")
        )
        override val allowedWorkers = WorkerType.entries.toSet()

        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            val query = params["query"]?.jsonPrimitive?.content
                ?: return ToolResult("Error: query required", "Error", 5, isError = true)
            val results = baseDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { idx, line ->
                        if (line.contains(query)) "${file.name}:${idx + 1}: $line" else null
                    }
                }
                .take(20)
                .toList()

            val content = if (results.isEmpty()) "No results for: $query"
            else results.joinToString("\n")

            return ToolResult(content, "${results.size} results for '$query'", content.length / 4)
        }
    }

    /**
     * Wraps a real LlmBrain but throws NotImplementedError on chatStream().
     * This forces SingleAgentSession to fall back to chat() which works
     * with MockWebServer's normal JSON responses (not SSE).
     */
    class NonStreamingBrainWrapper(private val delegate: LlmBrain) : LlmBrain {
        override val modelId: String get() = delegate.modelId

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: kotlinx.serialization.json.JsonElement?
        ): ApiResult<ChatCompletionResponse> = delegate.chat(messages, tools, maxTokens, toolChoice)

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            throw NotImplementedError("Streaming disabled for e2e tests — MockWebServer returns JSON, not SSE")
        }

        override fun estimateTokens(text: String): Int = delegate.estimateTokens(text)
    }
}
