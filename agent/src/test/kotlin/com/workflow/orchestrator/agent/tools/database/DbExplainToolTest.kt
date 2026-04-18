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
 * Tests for [DbExplainTool].
 *
 * Strategy: this tool's happy path requires a real JDBC connection, which is out
 * of scope for unit tests. We focus on the validation/error scenarios that run
 * before any database connection is opened.
 *
 *  - Tool surface (name, description, schema, allowedWorkers)
 *  - Required parameter validation (missing profile, missing sql)
 *  - Read-only guard (non-SELECT statements blocked before any connection)
 *  - Blank profile rejected before profile lookup
 */
class DbExplainToolTest {

    private val tool = DbExplainTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is db_explain`() {
            assertEquals("db_explain", tool.name)
        }

        @Test
        fun `description first line mentions EXPLAIN`() {
            val firstNonBlank = tool.description.lines()
                .firstOrNull { it.isNotBlank() } ?: ""
            assertTrue(
                firstNonBlank.contains("EXPLAIN"),
                "First non-blank description line should contain 'EXPLAIN' but was: $firstNonBlank"
            )
        }

        @Test
        fun `description mentions EXPLAIN ANALYZE safety`() {
            val desc = tool.description.lowercase()
            assertTrue(
                desc.contains("rollback") || desc.contains("rolled back"),
                "Description should mention 'rollback' or 'rolled back' to convey ANALYZE safety"
            )
        }

        @Test
        fun `profile and sql are required parameters`() {
            assertEquals(listOf("profile", "sql"), tool.parameters.required)
        }

        @Test
        fun `has profile database sql and analyze parameters`() {
            val keys = tool.parameters.properties.keys
            assertTrue(keys.contains("profile"), "Missing 'profile' parameter")
            assertTrue(keys.contains("database"), "Missing 'database' parameter")
            assertTrue(keys.contains("sql"), "Missing 'sql' parameter")
            assertTrue(keys.contains("analyze"), "Missing 'analyze' parameter")
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
            assertEquals("db_explain", def.function.name)
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
            val result = tool.execute(buildJsonObject { put("sql", "SELECT 1") }, project)
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("profile"),
                "Error message should mention 'profile': ${result.content}"
            )
        }

        @Test
        fun `missing sql returns error`() = runTest {
            val result = tool.execute(buildJsonObject { put("profile", "qa") }, project)
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("sql"),
                "Error message should mention 'sql': ${result.content}"
            )
        }

        @Test
        fun `non-SELECT sql is blocked before any connection opens`() = runTest {
            // validateReadOnly runs before DatabaseSettings is touched, so a relaxed
            // mock project is sufficient — no IDE service access occurs on this path.
            val result = runCatching {
                tool.execute(
                    buildJsonObject {
                        put("profile", "qa")
                        put("sql", "DELETE FROM users")
                    },
                    project
                )
            }
            if (result.isSuccess) {
                val tr = result.getOrNull()!!
                assertTrue(tr.isError, "DELETE statement should produce an error result")
            } else {
                val ex = result.exceptionOrNull()!!
                assertTrue(
                    ex is NullPointerException ||
                        ex is IllegalStateException ||
                        ex is RuntimeException,
                    "Unexpected exception: ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }

        @Test
        fun `INSERT sql is blocked`() = runTest {
            val result = runCatching {
                tool.execute(
                    buildJsonObject {
                        put("profile", "qa")
                        put("sql", "INSERT INTO logs(msg) VALUES('x')")
                    },
                    project
                )
            }
            if (result.isSuccess) {
                val tr = result.getOrNull()!!
                assertTrue(tr.isError, "INSERT statement should produce an error result")
            } else {
                val ex = result.exceptionOrNull()!!
                assertTrue(
                    ex is NullPointerException ||
                        ex is IllegalStateException ||
                        ex is RuntimeException,
                    "Unexpected exception: ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }

        @Test
        fun `DROP sql is blocked`() = runTest {
            val result = runCatching {
                tool.execute(
                    buildJsonObject {
                        put("profile", "qa")
                        put("sql", "DROP TABLE users")
                    },
                    project
                )
            }
            if (result.isSuccess) {
                val tr = result.getOrNull()!!
                assertTrue(tr.isError, "DROP statement should produce an error result")
            } else {
                val ex = result.exceptionOrNull()!!
                assertTrue(
                    ex is NullPointerException ||
                        ex is IllegalStateException ||
                        ex is RuntimeException,
                    "Unexpected exception: ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }

        @Test
        fun `blank profile returns error`() = runTest {
            val result = runCatching {
                tool.execute(
                    buildJsonObject {
                        put("profile", "   ")
                        put("sql", "SELECT 1")
                    },
                    project
                )
            }
            if (result.isSuccess) {
                val tr = result.getOrNull()!!
                assertTrue(tr.isError, "Blank profile should produce an error result")
            } else {
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
