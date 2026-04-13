package com.workflow.orchestrator.agent.memory

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun writeApiHistory(sessionDir: File, vararg messages: Pair<String, String>) {
    sessionDir.mkdirs()
    val apiMessages = messages.map { (role, content) ->
        ApiMessage(
            role = if (role == "assistant") ApiRole.ASSISTANT else ApiRole.USER,
            content = listOf(ContentBlock.Text(content))
        )
    }
    File(sessionDir, "api_conversation_history.json").writeText(testJson.encodeToString(apiMessages))
}

/**
 * End-to-end integration tests for the 3-tier memory system.
 *
 * Uses real file-backed stores (no mocks) with @TempDir isolation.
 * Verifies round-trip persistence, cross-tier workflows, and search behavior.
 */
class MemoryIntegrationTest {

    @TempDir lateinit var tempDir: Path

    // ---- Tier 1: Core Memory ----

    @Nested
    inner class CoreMemoryRoundTrip {

        @Test
        fun `append, read, replace, compile, and reload from disk`() {
            val storageFile = tempDir.resolve("core-memory.json").toFile()

            // Create and populate
            val memory = CoreMemory(storageFile)
            memory.append("project", "Uses Gradle")
            memory.append("user", "Prefers Kotlin")

            // Read back
            assertEquals("Uses Gradle", memory.read("project"))
            assertEquals("Prefers Kotlin", memory.read("user"))

            // Replace within project block
            memory.replace("project", "Gradle", "Maven")
            assertEquals("Uses Maven", memory.read("project"))

            // Compile to XML — verify both blocks present
            val xml = memory.compile()
            assertNotNull(xml)
            assertTrue(xml!!.contains("<core_memory>"))
            assertTrue(xml.contains("<project>"))
            assertTrue(xml.contains("Uses Maven"))
            assertTrue(xml.contains("<user>"))
            assertTrue(xml.contains("Prefers Kotlin"))

            // Reload from same file — verify persistence
            val reloaded = CoreMemory(storageFile)
            assertEquals("Uses Maven", reloaded.read("project"))
            assertEquals("Prefers Kotlin", reloaded.read("user"))

            // Reloaded compile should match
            val reloadedXml = reloaded.compile()
            assertNotNull(reloadedXml)
            assertTrue(reloadedXml!!.contains("Uses Maven"))
            assertTrue(reloadedXml.contains("Prefers Kotlin"))
        }

        @Test
        fun `multi-line append within a single block persists correctly`() {
            val storageFile = tempDir.resolve("core-memory.json").toFile()
            val memory = CoreMemory(storageFile)

            memory.append("project", "Line 1")
            memory.append("project", "Line 2")
            memory.append("project", "Line 3")

            assertEquals("Line 1\nLine 2\nLine 3", memory.read("project"))

            // Reload and verify
            val reloaded = CoreMemory(storageFile)
            assertEquals("Line 1\nLine 2\nLine 3", reloaded.read("project"))
        }
    }

    // ---- Tier 2: Archival Memory ----

    @Nested
    inner class ArchivalMemoryRoundTrip {

        @Test
        fun `insert, search by keyword, filter by tag, and reload from disk`() {
            val storageFile = tempDir.resolve("archival").resolve("store.json").toFile()

            val archival = ArchivalMemory(storageFile)

            // Insert 3 entries with different tags
            archival.insert("CORS error when calling REST API from frontend", listOf("error_resolution", "cors"))
            archival.insert("Spring Boot auto-configuration for DataSource", listOf("spring", "config"))
            archival.insert("Gradle build cache speeds up incremental builds", listOf("gradle", "performance"))

            assertEquals(3, archival.size())

            // Search by keyword — "CORS" should match the first entry
            val corsResults = archival.search("cors")
            assertTrue(corsResults.isNotEmpty(), "Should find CORS entry")
            assertTrue(corsResults.first().entry.content.contains("CORS"))
            // Tag boost: "cors" appears in both content and tags, so score should be > 1
            assertTrue(corsResults.first().score > 1, "Tag match should boost score")

            // Search with tag filter
            val springResults = archival.search("configuration", tags = listOf("spring"))
            assertTrue(springResults.isNotEmpty(), "Should find Spring entry with tag filter")
            assertTrue(springResults.first().entry.content.contains("Spring Boot"))

            // Tag-only filter (no keyword match in non-spring entries)
            val gradleTagResults = archival.search("build", tags = listOf("gradle"))
            assertTrue(gradleTagResults.isNotEmpty(), "Should find Gradle entry by tag + keyword")
            assertTrue(gradleTagResults.first().entry.tags.contains("gradle"))

            // Verify usage tracking — searched entries should have usageCount > 0
            val allEntries = archival.all()
            val corsEntry = allEntries.find { it.content.contains("CORS") }!!
            assertTrue(corsEntry.usageCount > 0, "Searched entry should have usageCount > 0")
            assertNotNull(corsEntry.lastUsage, "Searched entry should have lastUsage set")

            // Reload from disk — verify persistence
            val reloaded = ArchivalMemory(storageFile)
            assertEquals(3, reloaded.size())

            val reloadedResults = reloaded.search("cors")
            assertTrue(reloadedResults.isNotEmpty(), "Reloaded archival should find CORS entry")
            // Usage count should have been persisted from prior search + incremented by this search
            assertTrue(reloadedResults.first().entry.usageCount > 1,
                "Reloaded entry should retain prior usageCount and increment on new search")
        }

        @Test
        fun `search with no matches returns empty list`() {
            val storageFile = tempDir.resolve("archival-empty").resolve("store.json").toFile()
            val archival = ArchivalMemory(storageFile)
            archival.insert("Some content", listOf("tag1"))

            val results = archival.search("nonexistent_keyword_xyz")
            assertTrue(results.isEmpty(), "Search for non-matching keyword should return empty")
        }
    }

    // ---- Tier 3: Conversation Recall ----

    @Nested
    inner class ConversationRecallRoundTrip {

        @Test
        fun `search across multiple sessions with role filtering`() {
            val sessionsDir = tempDir.resolve("sessions").toFile()

            // Create session 1 with known content
            val session1Dir = File(sessionsDir, "session-001")
            writeApiHistory(session1Dir,
                "user" to "How do I fix the NullPointerException in UserService?",
                "assistant" to "The NullPointerException occurs because the repository field is not injected. Add @Autowired annotation.",
                "user" to "That fixed it, thanks!"
            )

            // Create session 2 with different content
            val session2Dir = File(sessionsDir, "session-002")
            writeApiHistory(session2Dir,
                "user" to "How do I configure Gradle for multi-module builds?",
                "assistant" to "Use the settings.gradle.kts file to include subprojects and configure shared dependencies in the root build.gradle.kts."
            )

            val recall = ConversationRecall(sessionsDir)

            // Search for keyword only in session 1
            val nullResults = recall.search("NullPointerException")
            assertTrue(nullResults.isNotEmpty(), "Should find NullPointerException in session 1")
            assertTrue(nullResults.all { it.sessionId == "session-001" },
                "NullPointerException should only appear in session 1")

            // Search with role filter — only user messages
            val userResults = recall.search("NullPointerException", roles = listOf("user"))
            assertTrue(userResults.isNotEmpty(), "Should find user message about NullPointerException")
            assertTrue(userResults.all { it.role == "user" }, "All results should be user role")

            // Search with role filter — only assistant messages
            val assistantResults = recall.search("NullPointerException", roles = listOf("assistant"))
            assertTrue(assistantResults.isNotEmpty(), "Assistant also mentioned NullPointerException")
            assertTrue(assistantResults.all { it.role == "assistant" }, "All results should be assistant role")

            // Search keyword appearing in both sessions — "Gradle" is in session 2, but also mentioned? No.
            // Use a keyword that appears in both sessions: use "configure"/"fix" — let's use a broader one.
            // Actually session 1 doesn't mention Gradle. Let's search for something in session 2 only.
            val gradleResults = recall.search("Gradle")
            assertTrue(gradleResults.isNotEmpty(), "Should find Gradle in session 2")
            assertTrue(gradleResults.any { it.sessionId == "session-002" },
                "Gradle should be found in session 2")
        }

        @Test
        fun `search keyword appearing in both sessions`() {
            val sessionsDir = tempDir.resolve("sessions-multi").toFile()

            val session1Dir = File(sessionsDir, "session-aaa")
            writeApiHistory(session1Dir,
                "user" to "The build failed with an error",
                "assistant" to "Let me check the build logs"
            )

            val session2Dir = File(sessionsDir, "session-bbb")
            writeApiHistory(session2Dir,
                "user" to "Another build issue today",
                "assistant" to "The build system needs configuration"
            )

            val recall = ConversationRecall(sessionsDir)
            val results = recall.search("build")

            assertTrue(results.size >= 2, "Should find 'build' in multiple messages across sessions")
            val sessionIds = results.map { it.sessionId }.distinct()
            assertTrue(sessionIds.size == 2, "Results should span both sessions, got: $sessionIds")
        }

        @Test
        fun `search with no sessions directory returns empty`() {
            val nonExistentDir = tempDir.resolve("no-sessions").toFile()
            val recall = ConversationRecall(nonExistentDir)
            val results = recall.search("anything")
            assertTrue(results.isEmpty())
        }
    }

    // ---- Cross-Tier Workflow ----

    @Nested
    inner class CrossTierWorkflow {

        @Test
        fun `realistic agent workflow across all three memory tiers`() {
            val agentDir = tempDir.resolve("agent-data").toFile()
            agentDir.mkdirs()

            // --- Step 1: Core memory — store project info ---
            val coreFile = File(agentDir, "core-memory.json")
            val core = CoreMemory(coreFile)
            core.append("project", "IntelliJ plugin using Kotlin and Gradle")
            core.append("project", "Target: IntelliJ IDEA 2025.1+")
            core.append("user", "Prefers TDD approach")

            // --- Step 2: Archival — store an error resolution ---
            val archivalFile = File(agentDir, "archival/store.json")
            val archival = ArchivalMemory(archivalFile)
            archival.insert(
                "Resolved ClassNotFoundException for kotlinx.serialization by adding " +
                    "kotlinx-serialization-json dependency to build.gradle.kts",
                listOf("error_resolution", "gradle", "serialization")
            )
            archival.insert(
                "EDT threading violation fixed by wrapping UI update in invokeLater",
                listOf("error_resolution", "threading", "intellij")
            )

            // --- Step 3: Search archival for the serialization error ---
            val searchResults = archival.search("ClassNotFoundException serialization")
            assertTrue(searchResults.isNotEmpty(), "Should find the serialization error resolution")
            assertTrue(searchResults.first().entry.content.contains("kotlinx.serialization"),
                "Top result should be about kotlinx.serialization")

            // --- Step 4: Core memory compile includes project info ---
            val compiled = core.compile()
            assertNotNull(compiled)
            assertTrue(compiled!!.contains("IntelliJ plugin using Kotlin and Gradle"))
            assertTrue(compiled.contains("Target: IntelliJ IDEA 2025.1+"))
            assertTrue(compiled.contains("Prefers TDD approach"))
            assertTrue(compiled.contains("<project>"))
            assertTrue(compiled.contains("<user>"))

            // --- Step 5: Conversation recall across sessions ---
            val sessionsDir = File(agentDir, "sessions")
            val session1 = File(sessionsDir, "sess-100")
            writeApiHistory(session1,
                "user" to "Add kotlinx-serialization to the project",
                "assistant" to "I added kotlinx-serialization-json 1.6.0 to build.gradle.kts dependencies"
            )

            val session2 = File(sessionsDir, "sess-200")
            writeApiHistory(session2,
                "user" to "Run the tests to verify the build",
                "assistant" to "All 42 tests passed. The serialization module is working correctly."
            )

            val recall = ConversationRecall(sessionsDir)
            val recallResults = recall.search("serialization")
            assertTrue(recallResults.isNotEmpty(), "Should find serialization mentions in past sessions")
            // Should find in both sessions
            val recallSessions = recallResults.map { it.sessionId }.distinct()
            assertTrue(recallSessions.size >= 2,
                "serialization keyword should appear in both sessions, found in: $recallSessions")

            // --- Verify all tiers are independently persisted ---
            // Reload each tier from disk
            val reloadedCore = CoreMemory(coreFile)
            assertEquals("IntelliJ plugin using Kotlin and Gradle\nTarget: IntelliJ IDEA 2025.1+",
                reloadedCore.read("project"))

            val reloadedArchival = ArchivalMemory(archivalFile)
            assertEquals(2, reloadedArchival.size())
            val reloadedSearch = reloadedArchival.search("ClassNotFoundException")
            assertTrue(reloadedSearch.isNotEmpty())
        }
    }
}
