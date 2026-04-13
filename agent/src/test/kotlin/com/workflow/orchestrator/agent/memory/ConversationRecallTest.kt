package com.workflow.orchestrator.agent.memory

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ConversationRecallTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var sessionsDir: File
    private lateinit var recall: ConversationRecall
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @BeforeEach
    fun setUp() {
        sessionsDir = tempDir.resolve("sessions").toFile()
        sessionsDir.mkdirs()
        recall = ConversationRecall(sessionsDir)
    }

    private fun createSession(sessionId: String, vararg messages: Pair<String, String>) {
        val dir = File(sessionsDir, sessionId)
        dir.mkdirs()
        val apiMessages = messages.map { (role, content) ->
            when (role) {
                "tool" -> ApiMessage(
                    role = ApiRole.USER,
                    content = listOf(ContentBlock.ToolResult(toolUseId = "call_1", content = content))
                )
                "assistant" -> ApiMessage(
                    role = ApiRole.ASSISTANT,
                    content = listOf(ContentBlock.Text(content))
                )
                else -> ApiMessage(
                    role = ApiRole.USER,
                    content = listOf(ContentBlock.Text(content))
                )
            }
        }
        val file = File(dir, "api_conversation_history.json")
        file.writeText(json.encodeToString(apiMessages))
    }

    @Test
    fun `search finds matching messages across sessions`() {
        createSession("s1", "user" to "Fix the CORS error in SecurityConfig")
        createSession("s2", "user" to "Add pagination to the API")

        val results = recall.search("CORS")
        assertEquals(1, results.size)
        assertEquals("s1", results[0].sessionId)
        assertTrue(results[0].content.contains("CORS"))
    }

    @Test
    fun `search filters by role`() {
        createSession("s1",
            "user" to "Fix the bug",
            "assistant" to "I found the bug in UserService"
        )

        val userOnly = recall.search("bug", roles = listOf("user"))
        assertEquals(1, userOnly.size)
        assertEquals("user", userOnly[0].role)

        val assistantOnly = recall.search("bug", roles = listOf("assistant"))
        assertEquals(1, assistantOnly.size)
        assertEquals("assistant", assistantOnly[0].role)
    }

    @Test
    fun `search skips tool messages`() {
        createSession("s1",
            "user" to "Search for tests",
            "tool" to "Found 5 test files"
        )

        val results = recall.search("test")
        assertEquals(1, results.size)
        assertEquals("user", results[0].role)
    }

    @Test
    fun `search respects limit`() {
        createSession("s1",
            "user" to "kotlin question 1",
            "user" to "kotlin question 2",
            "user" to "kotlin question 3"
        )

        val results = recall.search("kotlin", limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `search returns empty for no matches`() {
        createSession("s1", "user" to "hello world")
        val results = recall.search("nonexistent")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search handles missing sessions dir`() {
        val missing = ConversationRecall(File(tempDir.toFile(), "nope"))
        val results = missing.search("test")
        assertTrue(results.isEmpty())
    }
}
