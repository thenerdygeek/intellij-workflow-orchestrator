package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.ConversationRecall
import com.workflow.orchestrator.agent.memory.CoreMemory
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MemoryToolsTest {

    @TempDir
    lateinit var tempDir: File

    private val project = mockk<Project>(relaxed = true)

    // ---- CoreMemoryReadTool ----

    @Nested
    inner class CoreMemoryReadTests {
        private lateinit var coreMemory: CoreMemory
        private lateinit var tool: CoreMemoryReadTool

        @BeforeEach
        fun setup() {
            coreMemory = CoreMemory(File(tempDir, "core-memory.json"))
            tool = CoreMemoryReadTool(coreMemory)
        }

        @Test
        fun `read empty returns info message`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("empty"))
        }

        @Test
        fun `read after append returns content`() = runTest {
            coreMemory.append("notes", "Project uses Spring Boot")
            val result = tool.execute(buildJsonObject { }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Project uses Spring Boot"))
            assertTrue(result.content.contains("[notes]"))
        }

        @Test
        fun `read specific label returns block`() = runTest {
            coreMemory.append("prefs", "Dark theme preferred")
            coreMemory.append("notes", "Uses Kotlin")
            val result = tool.execute(buildJsonObject { put("label", "prefs") }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Dark theme preferred"))
            assertFalse(result.content.contains("Uses Kotlin"))
        }

        @Test
        fun `read nonexistent label returns not found`() = runTest {
            val result = tool.execute(buildJsonObject { put("label", "missing") }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("No core memory block"))
        }

        @Test
        fun `allowed for all worker types`() {
            assertEquals(5, tool.allowedWorkers.size)
        }
    }

    // ---- CoreMemoryAppendTool ----

    @Nested
    inner class CoreMemoryAppendTests {
        private lateinit var coreMemory: CoreMemory
        private lateinit var tool: CoreMemoryAppendTool

        @BeforeEach
        fun setup() {
            coreMemory = CoreMemory(File(tempDir, "core-memory.json"))
            tool = CoreMemoryAppendTool(coreMemory)
        }

        @Test
        fun `append succeeds`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("label", "notes")
                put("content", "User prefers verbose output")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("User prefers verbose output"))
            assertEquals("User prefers verbose output", coreMemory.read("notes"))
        }

        @Test
        fun `missing label returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("content", "some content")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("label"))
        }

        @Test
        fun `missing content returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("label", "notes")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("content"))
        }

        @Test
        fun `append to read-only block returns error`() = runTest {
            coreMemory.setBlock("system", "read only data", readOnly = true)
            val result = tool.execute(buildJsonObject {
                put("label", "system")
                put("content", "try to modify")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("read-only"))
        }
    }

    // ---- CoreMemoryReplaceTool ----

    @Nested
    inner class CoreMemoryReplaceTests {
        private lateinit var coreMemory: CoreMemory
        private lateinit var tool: CoreMemoryReplaceTool

        @BeforeEach
        fun setup() {
            coreMemory = CoreMemory(File(tempDir, "core-memory.json"))
            tool = CoreMemoryReplaceTool(coreMemory)
        }

        @Test
        fun `replace succeeds`() = runTest {
            coreMemory.append("notes", "User likes Java")
            val result = tool.execute(buildJsonObject {
                put("label", "notes")
                put("old_content", "Java")
                put("new_content", "Kotlin")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Kotlin"))
            assertEquals("User likes Kotlin", coreMemory.read("notes"))
        }

        @Test
        fun `no match returns error`() = runTest {
            coreMemory.append("notes", "User likes Java")
            val result = tool.execute(buildJsonObject {
                put("label", "notes")
                put("old_content", "Python")
                put("new_content", "Kotlin")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No match"))
        }

        @Test
        fun `nonexistent block returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("label", "missing")
                put("old_content", "foo")
                put("new_content", "bar")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("not found"))
        }
    }

    // ---- ArchivalMemoryInsertTool ----

    @Nested
    inner class ArchivalMemoryInsertTests {
        private lateinit var archivalMemory: ArchivalMemory
        private lateinit var tool: ArchivalMemoryInsertTool

        @BeforeEach
        fun setup() {
            archivalMemory = ArchivalMemory(File(tempDir, "archival/store.json"))
            tool = ArchivalMemoryInsertTool(archivalMemory)
        }

        @Test
        fun `insert returns confirmation with ID`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("content", "CORS errors fixed by adding @CrossOrigin annotation")
                put("tags", "error_resolution, spring, cors")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("ID: mem_"))
            assertTrue(result.content.contains("Total entries: 1"))
            assertEquals(1, archivalMemory.size())
        }

        @Test
        fun `missing content returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("tags", "test")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("content"))
        }

        @Test
        fun `missing tags returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("content", "some content")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("tags"))
        }
    }

    // ---- ArchivalMemorySearchTool ----

    @Nested
    inner class ArchivalMemorySearchTests {
        private lateinit var archivalMemory: ArchivalMemory
        private lateinit var tool: ArchivalMemorySearchTool

        @BeforeEach
        fun setup() {
            archivalMemory = ArchivalMemory(File(tempDir, "archival/store.json"))
            tool = ArchivalMemorySearchTool(archivalMemory)
        }

        @Test
        fun `search returns formatted results`() = runTest {
            archivalMemory.insert("CORS errors fixed by adding @CrossOrigin", listOf("spring", "cors"))
            archivalMemory.insert("NullPointerException in service layer", listOf("java", "error"))

            val result = tool.execute(buildJsonObject {
                put("query", "CORS")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("CORS"))
            assertTrue(result.content.contains("Found"))
        }

        @Test
        fun `no results returns info message`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("query", "nonexistent topic")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("No archival memory entries found"))
        }

        @Test
        fun `search with tag filter`() = runTest {
            archivalMemory.insert("CORS fix", listOf("spring", "cors"))
            archivalMemory.insert("NPE fix", listOf("java", "error"))

            val result = tool.execute(buildJsonObject {
                put("query", "fix")
                put("tags", "spring")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("CORS"))
            assertFalse(result.content.contains("NPE"))
        }
    }

    // ---- ConversationSearchTool ----

    @Nested
    inner class ConversationSearchTests {
        @TempDir
        lateinit var convTempDir: File

        private lateinit var conversationRecall: ConversationRecall
        private lateinit var tool: ConversationSearchTool

        @BeforeEach
        fun setup() {
            val sessionsDir = File(convTempDir, "sessions")
            // Create a test session with messages.jsonl
            val sessionDir = File(sessionsDir, "session-001")
            sessionDir.mkdirs()
            val messagesFile = File(sessionDir, "messages.jsonl")
            messagesFile.writeText(buildString {
                appendLine("""{"role":"user","content":"How do I fix the CORS error in Spring Boot?"}""")
                appendLine("""{"role":"assistant","content":"Add @CrossOrigin annotation to your controller."}""")
                appendLine("""{"role":"user","content":"That worked, thanks!"}""")
            })

            conversationRecall = ConversationRecall(sessionsDir)
            tool = ConversationSearchTool(conversationRecall)
        }

        @Test
        fun `search returns formatted results`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("query", "CORS")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("CORS"))
            assertTrue(result.content.contains("session-001"))
            assertTrue(result.content.contains("Found"))
        }

        @Test
        fun `search with role filter`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("query", "CORS")
                put("roles", "assistant")
            }, project)
            assertFalse(result.isError, "Result was error: ${result.content}")
            assertTrue(result.content.contains("CrossOrigin"), "Expected CrossOrigin in: ${result.content}")
            // Should not contain user messages about CORS
            assertFalse(result.content.contains("How do I fix"), "Should not contain user msg: ${result.content}")
        }

        @Test
        fun `no results returns info message`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("query", "kubernetes deployment")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("No conversation history found"))
        }
    }

    // ---- SaveMemoryTool ----

    @Nested
    inner class SaveMemoryTests {
        private lateinit var tool: SaveMemoryTool

        @BeforeEach
        fun setup() {
            tool = SaveMemoryTool(tempDir)
        }

        @Test
        fun `saves file to disk`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("content", "# Important Notes\n\nThis is a test memory file.")
                put("filename", "test-notes")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("test-notes.md"))

            val savedFile = File(tempDir, "memory/test-notes.md")
            assertTrue(savedFile.exists())
            assertEquals("# Important Notes\n\nThis is a test memory file.", savedFile.readText())
        }

        @Test
        fun `uses timestamp filename when not specified`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("content", "Auto-named memory")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("memory-"))
            assertTrue(result.content.contains(".md"))

            val memoryDir = File(tempDir, "memory")
            assertTrue(memoryDir.exists())
            val files = memoryDir.listFiles()
            assertNotNull(files)
            assertEquals(1, files!!.size)
            assertTrue(files[0].name.startsWith("memory-"))
        }

        @Test
        fun `missing content returns error`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("content"))
        }
    }
}
