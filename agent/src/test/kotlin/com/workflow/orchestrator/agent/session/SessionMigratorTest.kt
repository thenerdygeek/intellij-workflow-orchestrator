package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SessionMigratorTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Test
    fun `migrates old JSONL session to new two-file format`() = runTest {
        // Set up old format: {sessionId}.json + {sessionId}/messages.jsonl
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val oldMeta = """{"id":"old-1","title":"Fix bug","createdAt":1712000000000,"messageCount":2,"status":"ACTIVE","totalTokens":5000,"inputTokens":3000,"outputTokens":2000}"""
        File(sessionsDir, "old-1.json").writeText(oldMeta)

        val msgDir = File(sessionsDir, "old-1").also { it.mkdirs() }
        val msg1 = """{"role":"user","content":"Fix the login bug"}"""
        val msg2 = """{"role":"assistant","content":"I'll look at the code."}"""
        File(msgDir, "messages.jsonl").writeText("$msg1\n$msg2\n")

        // Run migration
        SessionMigrator.migrate(tempDir.toFile())

        // Verify new format exists
        val apiFile = File(msgDir, "api_conversation_history.json")
        assertTrue(apiFile.exists())
        val apiHistory = Json { ignoreUnknownKeys = true }.decodeFromString<List<ApiMessage>>(apiFile.readText())
        assertEquals(2, apiHistory.size)
        assertEquals(ApiRole.USER, apiHistory[0].role)

        val uiFile = File(msgDir, "ui_messages.json")
        assertTrue(uiFile.exists())

        // Verify global index created
        val indexFile = File(tempDir.toFile(), "sessions.json")
        assertTrue(indexFile.exists())
    }

    @Test
    fun `skips sessions already in new format`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val msgDir = File(sessionsDir, "new-1").also { it.mkdirs() }
        File(msgDir, "api_conversation_history.json").writeText("[]")
        File(msgDir, "ui_messages.json").writeText("[]")

        SessionMigrator.migrate(tempDir.toFile())
        // Should not crash, should leave files unchanged
        assertEquals("[]", File(msgDir, "api_conversation_history.json").readText())
    }

    @Test
    fun `handles empty sessions directory`() = runTest {
        // No sessions directory at all
        SessionMigrator.migrate(tempDir.toFile())
        // Should not crash
        assertFalse(File(tempDir.toFile(), "sessions.json").exists())
    }

    @Test
    fun `handles session with empty messages_jsonl`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val msgDir = File(sessionsDir, "empty-1").also { it.mkdirs() }
        File(msgDir, "messages.jsonl").writeText("\n\n")

        SessionMigrator.migrate(tempDir.toFile())
        // Should not create new format files for empty sessions
        assertFalse(File(msgDir, "api_conversation_history.json").exists())
    }

    @Test
    fun `handles session with malformed JSONL lines`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val msgDir = File(sessionsDir, "bad-1").also { it.mkdirs() }
        val validMsg = """{"role":"user","content":"Hello"}"""
        val invalidMsg = """not valid json"""
        File(msgDir, "messages.jsonl").writeText("$validMsg\n$invalidMsg\n")

        SessionMigrator.migrate(tempDir.toFile())

        // Should still migrate the valid message
        val apiFile = File(msgDir, "api_conversation_history.json")
        assertTrue(apiFile.exists())
        val apiHistory = Json { ignoreUnknownKeys = true }.decodeFromString<List<ApiMessage>>(apiFile.readText())
        assertEquals(1, apiHistory.size)
    }

    @Test
    fun `is idempotent — second run does not change files`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val oldMeta = """{"id":"idem-1","title":"Test","createdAt":1712000000000,"lastMessageAt":1712000001000,"inputTokens":100,"outputTokens":200}"""
        File(sessionsDir, "idem-1.json").writeText(oldMeta)

        val msgDir = File(sessionsDir, "idem-1").also { it.mkdirs() }
        File(msgDir, "messages.jsonl").writeText("""{"role":"user","content":"test"}""" + "\n")

        // First run
        SessionMigrator.migrate(tempDir.toFile())
        val apiContent1 = File(msgDir, "api_conversation_history.json").readText()

        // Second run — should not change anything
        SessionMigrator.migrate(tempDir.toFile())
        val apiContent2 = File(msgDir, "api_conversation_history.json").readText()

        assertEquals(apiContent1, apiContent2)
    }

    @Test
    fun `migrates session with tool calls`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        val msgDir = File(sessionsDir, "tools-1").also { it.mkdirs() }
        val userMsg = """{"role":"user","content":"Read the file"}"""
        val assistantMsg = """{"role":"assistant","content":"Let me read it.","tool_calls":[{"id":"tc1","function":{"name":"read_file","arguments":"{\"path\":\"test.kt\"}"}}]}"""
        val toolMsg = """{"role":"tool","content":"file contents here","tool_call_id":"tc1"}"""
        File(msgDir, "messages.jsonl").writeText("$userMsg\n$assistantMsg\n$toolMsg\n")

        SessionMigrator.migrate(tempDir.toFile())

        val apiFile = File(msgDir, "api_conversation_history.json")
        assertTrue(apiFile.exists())
        val apiHistory = Json { ignoreUnknownKeys = true }.decodeFromString<List<ApiMessage>>(apiFile.readText())
        assertEquals(3, apiHistory.size)

        // Assistant message should have ToolUse content block
        val assistantApiMsg = apiHistory[1]
        assertEquals(ApiRole.ASSISTANT, assistantApiMsg.role)
        assertTrue(assistantApiMsg.content.any { it is ContentBlock.ToolUse })

        // Tool result should be converted to USER role with ToolResult content block
        val toolApiMsg = apiHistory[2]
        assertEquals(ApiRole.USER, toolApiMsg.role)
        assertTrue(toolApiMsg.content.any { it is ContentBlock.ToolResult })
    }

    @Test
    fun `merges with existing global index without duplicates`() = runTest {
        val baseDir = tempDir.toFile()
        val sessionsDir = File(baseDir, "sessions").also { it.mkdirs() }

        // Pre-existing global index
        val existingItem = HistoryItem(id = "existing-1", ts = 1712000000000, task = "Existing")
        val indexFile = File(baseDir, "sessions.json")
        indexFile.writeText(Json { encodeDefaults = true }.encodeToString(listOf(existingItem)))

        // Old session to migrate
        val msgDir = File(sessionsDir, "new-migrate").also { it.mkdirs() }
        File(msgDir, "messages.jsonl").writeText("""{"role":"user","content":"migrate me"}""" + "\n")

        SessionMigrator.migrate(baseDir)

        // Index should contain both entries
        val items = Json { ignoreUnknownKeys = true }.decodeFromString<List<HistoryItem>>(indexFile.readText())
        assertTrue(items.any { it.id == "existing-1" })
    }

    @Test
    fun `session without old metadata file still migrates`() = runTest {
        val sessionsDir = File(tempDir.toFile(), "sessions").also { it.mkdirs() }
        // No {sessionId}.json metadata file
        val msgDir = File(sessionsDir, "no-meta-1").also { it.mkdirs() }
        File(msgDir, "messages.jsonl").writeText("""{"role":"user","content":"hello"}""" + "\n")

        SessionMigrator.migrate(tempDir.toFile())

        // API file should be created
        assertTrue(File(msgDir, "api_conversation_history.json").exists())
        assertTrue(File(msgDir, "ui_messages.json").exists())
    }
}
