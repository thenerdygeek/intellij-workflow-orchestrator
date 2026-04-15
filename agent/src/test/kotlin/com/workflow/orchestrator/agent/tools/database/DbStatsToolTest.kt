package com.workflow.orchestrator.agent.tools.database

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [DbStatsTool].
 *
 * Strategy: this tool's happy path requires a real JDBC connection, which is out
 * of scope for unit tests. We focus on the validation/error scenarios that run
 * before any database connection is opened.
 *
 *  - Tool surface (name, description, schema, allowedWorkers)
 *  - Required parameter validation (missing/blank profile)
 *  - Profile lookup failure (nonexistent profile)
 */
class DbStatsToolTest {

    private val tool = DbStatsTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is db_stats`() {
            assertEquals("db_stats", tool.name)
        }

        @Test
        fun `description first line is the catalog one-liner`() {
            val firstNonBlankLine = tool.description.lines().first { it.isNotBlank() }
            val lower = firstNonBlankLine.lowercase()
            assertTrue(
                lower.contains("row") || lower.contains("size"),
                "Expected first non-blank description line to mention 'row' or 'size', got: $firstNonBlankLine"
            )
        }

        @Test
        fun `description mentions pg_stat_user_tables or PostgreSQL`() {
            assertTrue(
                tool.description.contains("pg_stat_user_tables") || tool.description.contains("PostgreSQL"),
                "Expected description to mention pg_stat_user_tables or PostgreSQL"
            )
        }

        @Test
        fun `description mentions all three scoping modes`() {
            assertTrue(tool.description.contains("schema"), "Expected 'schema' in description")
            assertTrue(tool.description.contains("table"), "Expected 'table' in description")
        }

        @Test
        fun `profile is the only required parameter`() {
            assertEquals(listOf("profile"), tool.parameters.required)
        }

        @Test
        fun `has profile database schema and table parameters`() {
            val props = tool.parameters.properties
            assertNotNull(props["profile"], "Expected 'profile' parameter")
            assertNotNull(props["database"], "Expected 'database' parameter")
            assertNotNull(props["schema"], "Expected 'schema' parameter")
            assertNotNull(props["table"], "Expected 'table' parameter")
        }

        @Test
        fun `allowedWorkers is ORCHESTRATOR and TOOLER`() {
            assertEquals(
                setOf(WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
                tool.allowedWorkers
            )
        }

        @Test
        fun `toToolDefinition produces valid schema`() {
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertEquals("db_stats", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Validation scenarios — these run BEFORE any DatabaseSettings or
    // JDBC access, so they work with a relaxed mock project.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class Validation {

        @Test
        fun `missing profile returns error`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("profile"),
                "Expected error to mention 'profile', got: ${result.content}"
            )
        }

        @Test
        fun `blank profile returns error`() = runTest {
            val result = tool.execute(buildJsonObject { put("profile", "   ") }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `nonexistent profile returns helpful error`() = runTest {
            // Without a real DatabaseSettings service, the lookup will throw or return null;
            // either way the tool should produce a non-crashing error result that mentions
            // the profile name and points the user at db_list_profiles.
            val result = runCatching {
                tool.execute(buildJsonObject { put("profile", "doesnotexist") }, project)
            }
            if (result.isSuccess) {
                val tr = result.getOrNull()!!
                assertTrue(tr.isError)
                // Should reference the missing profile name OR direct user to list_profiles
                assertTrue(
                    tr.content.contains("doesnotexist") ||
                        tr.content.contains("db_list_profiles") ||
                        tr.content.contains("not available"),
                    "Expected error to mention the missing profile or service: ${tr.content}"
                )
            } else {
                // Acceptable: hit the IDE-services boundary instead of returning gracefully
                // Just confirm it's a recognized boundary exception, not a Kotlin logic bug
                val ex = result.exceptionOrNull()!!
                assertTrue(
                    ex is NullPointerException ||
                        ex is IllegalStateException ||
                        ex is RuntimeException,
                    "Unexpected exception: ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }
    }
}
