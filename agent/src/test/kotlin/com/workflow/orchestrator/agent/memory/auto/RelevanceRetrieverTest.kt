package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.agent.memory.ArchivalMemory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RelevanceRetrieverTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var archival: ArchivalMemory
    private lateinit var retriever: RelevanceRetriever

    @BeforeEach
    fun setUp() {
        val storageFile = tempDir.resolve("archival/store.json").toFile()
        archival = ArchivalMemory(storageFile)
        retriever = RelevanceRetriever(archival)
    }

    @Nested
    inner class KeywordExtraction {
        @Test
        fun `extracts meaningful keywords from message`() {
            val keywords = RelevanceRetriever.extractKeywords("Fix the CORS error in SecurityConfig")
            assertTrue(keywords.contains("cors"))
            assertTrue(keywords.contains("securityconfig"))
            assertTrue(keywords.contains("error"))
            // Stop words excluded
            assertFalse(keywords.contains("the"))
            assertFalse(keywords.contains("in"))
        }

        @Test
        fun `returns empty for very short messages`() {
            val keywords = RelevanceRetriever.extractKeywords("a")
            assertTrue(keywords.isEmpty())
        }

        @Test
        fun `deduplicates keywords`() {
            val keywords = RelevanceRetriever.extractKeywords("error error error in config config")
            assertEquals(keywords.size, keywords.toSet().size)
        }

        @Test
        fun `allows meaningful short identifiers like ci and pr`() {
            val keywords = RelevanceRetriever.extractKeywords("Fix the CI pipeline for PR merges")
            assertTrue(keywords.contains("ci"), "ci should be preserved")
            assertTrue(keywords.contains("pr"), "pr should be preserved")
            assertTrue(keywords.contains("pipeline"))
            assertFalse(keywords.contains("to"), "to is a stop word")
            assertFalse(keywords.contains("is"), "is is a stop word")
        }
    }

    @Nested
    inner class Retrieval {
        @Test
        fun `retrieves relevant archival entries`() {
            archival.insert("CORS error: add allowedOrigins to SecurityConfig", listOf("error", "cors", "spring"))
            archival.insert("Bamboo deploy pipeline uses docker-tag-key pattern", listOf("bamboo", "deploy"))
            archival.insert("Sonar quality gate requires 80% coverage", listOf("sonar", "quality"))

            val result = retriever.retrieveForMessage("Fix the CORS error in SecurityConfig")

            assertNotNull(result)
            assertTrue(result!!.contains("CORS"))
            assertTrue(result.contains("allowedOrigins"))
            // Unrelated entries should not appear
            assertFalse(result.contains("Bamboo"))
        }

        @Test
        fun `returns null when no relevant entries found`() {
            archival.insert("Docker tag pattern for staging", listOf("docker", "staging"))

            val result = retriever.retrieveForMessage("Fix the CORS error in SecurityConfig")
            assertNull(result)
        }

        @Test
        fun `returns null for empty archival memory`() {
            val result = retriever.retrieveForMessage("Fix the bug")
            assertNull(result)
        }

        @Test
        fun `limits result size`() {
            repeat(20) { i ->
                archival.insert("Error resolution $i: fix the spring boot error by updating config-$i", listOf("error", "spring"))
            }

            val result = retriever.retrieveForMessage("spring boot error")
            assertNotNull(result)
            assertTrue(result!!.length < 5000, "Result should be bounded, got ${result.length}")
        }
    }

    @Nested
    inner class XmlFormatting {
        @Test
        fun `formats as recalled_memory XML`() {
            archival.insert("Always use ToolResult for service methods", listOf("pattern", "convention"))

            val result = retriever.retrieveForMessage("How should I return from service methods?")

            assertNotNull(result)
            assertTrue(result!!.startsWith("<recalled_memory>"))
            assertTrue(result.endsWith("</recalled_memory>"))
        }
    }

    @Nested
    inner class StalenessFilter {

        @Test
        fun `suppresses entries mentioning non-existent files`() {
            archival.insert(
                "Fix: update src/main/kotlin/com/example/OldService.kt to use new API",
                listOf("fix", "api")
            )

            // All paths in the entry don't exist
            val pathExists: (String) -> Boolean = { _ -> false }
            val retriever = RelevanceRetriever(archival, pathExists)

            val result = retriever.retrieveForMessage("Use new API")

            // Should not include the entry because its referenced path is gone
            assertNull(result)
        }

        @Test
        fun `preserves entries with no file path mentions`() {
            archival.insert(
                "Deploy via Bamboo PROJ-DEPLOY-PROD, not the CI plan",
                listOf("deploy", "bamboo")
            )

            val pathExists: (String) -> Boolean = { _ -> false }  // Doesn't matter — no paths
            val retriever = RelevanceRetriever(archival, pathExists)

            val result = retriever.retrieveForMessage("deploy bamboo")

            assertNotNull(result)
            assertTrue(result!!.contains("Bamboo"))
        }

        @Test
        fun `preserves entries when at least one mentioned path exists`() {
            archival.insert(
                "Both src/main/kotlin/UserService.kt and src/main/kotlin/OldService.kt have null check issues",
                listOf("fix", "npe")
            )

            val pathExists: (String) -> Boolean = { path ->
                path.contains("UserService")  // One of the two paths still exists
            }
            val retriever = RelevanceRetriever(archival, pathExists)

            val result = retriever.retrieveForMessage("null check")

            assertNotNull(result)
        }

        @Test
        fun `default path predicate allows all entries (backward compat)`() {
            archival.insert(
                "Fix: update src/main/kotlin/NonExistent.kt",
                listOf("fix")
            )

            // Use single-arg constructor (default predicate = always true)
            val retriever = RelevanceRetriever(archival)

            val result = retriever.retrieveForMessage("Fix update")

            // Without a path predicate, all entries pass through
            assertNotNull(result)
        }
    }
}
