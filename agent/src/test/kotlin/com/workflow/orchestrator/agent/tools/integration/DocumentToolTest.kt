package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Unit + integration tests for [DocumentTool].
 *
 * Integration tests use a real [TikaDocumentExtractor] against the fixture files already
 * present in the :document module's test resources, so no new fixtures are added.
 * The :agent module's test classpath sees :document as a compile + runtime dependency via
 * the `implementation(project(":document"))` entry added in Phase 7.
 *
 * Integration tests use [runBlocking] (not runTest) because [TikaDocumentExtractor.extract]
 * uses real [kotlinx.coroutines.withTimeoutOrNull] which requires wall-clock time, not the
 * virtual test dispatcher used by runTest.
 *
 * Run with: ./gradlew :agent:test --tests "*DocumentToolTest*"
 */
class DocumentToolTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    // ── Metadata tests ────────────────────────────────────────────────────────

    @Test
    fun `name is read_document`() {
        assertEquals("read_document", DocumentTool().name)
    }

    @Test
    fun `description contains required tool_search keywords`() {
        val desc = DocumentTool().description.lowercase()
        assertTrue(desc.contains("pdf"), "description must mention 'pdf' for tool_search discoverability")
        assertTrue(desc.contains("docx"), "description must mention 'docx'")
        assertTrue(desc.contains("xlsx"), "description must mention 'xlsx'")
        assertTrue(desc.contains("spec"), "description must mention 'spec'")
        assertTrue(desc.contains("document"), "description must mention 'document'")
    }

    @Test
    fun `parameters includes path as required and max_chars as optional`() {
        val params = DocumentTool().parameters
        assertTrue(params.properties.containsKey("path"), "must declare 'path' parameter")
        assertTrue(params.properties.containsKey("max_chars"), "must declare 'max_chars' parameter")
        assertTrue(params.required.contains("path"), "'path' must be required")
        assertFalse(params.required.contains("max_chars"), "'max_chars' must be optional")
    }

    @Test
    fun `allowedWorkers includes standard roles`() {
        val workers = DocumentTool().allowedWorkers
        assertTrue(workers.contains(WorkerType.ORCHESTRATOR))
        assertTrue(workers.contains(WorkerType.CODER))
        assertTrue(workers.contains(WorkerType.ANALYZER))
    }

    @Test
    fun `toToolDefinition produces valid definition`() {
        val def = DocumentTool().toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("read_document", def.function.name)
        assertTrue(def.function.name.matches(Regex("^[a-zA-Z0-9_-]+$")))
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
    }

    // ── Error-handling tests (no IntelliJ runtime required) ──────────────────

    @Test
    fun `execute with no path returns error ToolResult without throwing`() = runTest {
        val result = DocumentTool().execute(buildJsonObject { }, project)
        assertTrue(result.isError, "missing path must produce isError=true")
        assertTrue(result.content.contains("path", ignoreCase = true), "error message should mention 'path'")
    }

    @Test
    fun `execute with non-existent file returns error without throwing`() = runBlocking {
        val params = buildJsonObject { put("path", "/tmp/nonexistent-9f8e7d6c.pdf") }
        val result = DocumentTool().execute(params, project)
        assertTrue(result.isError, "missing file must produce isError=true")
    }

    // ── Integration tests — real TikaDocumentExtractor + fixture files ────────

    /**
     * Resolve the :document fixture directory from the repo root.
     * Gradle sets the working directory to the repo root when running :agent:test.
     */
    private val fixtureDir = run {
        val candidates = listOf(
            "document/src/test/resources/fixtures",
            "../document/src/test/resources/fixtures",
        )
        candidates.map { Paths.get(it).toAbsolutePath() }.first { it.toFile().isDirectory }
    }

    @Test
    fun `execute with CSV fixture succeeds and markdown contains Alice`() = runBlocking {
        val csvPath = fixtureDir.resolve("data.csv")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            csvPath.toFile().exists(),
            "Skipping — fixture not found at $csvPath",
        )

        val params = buildJsonObject { put("path", csvPath.toString()) }
        val result = DocumentTool(TikaDocumentExtractor()).execute(params, project)

        assertFalse(result.isError, "CSV extraction must succeed; summary=${result.summary}")
        assertTrue(result.content.isNotBlank(), "extracted markdown must not be empty")
        assertTrue(
            result.content.contains("Alice", ignoreCase = true),
            "CSV content must contain 'Alice'; got: ${result.content.take(500)}",
        )
    }

    @Test
    fun `execute with spec-with-tables PDF fixture succeeds and contains FR-001 and Approved`() = runBlocking {
        val pdfPath = fixtureDir.resolve("spec-with-tables.pdf")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            pdfPath.toFile().exists(),
            "Skipping — fixture not found at $pdfPath",
        )

        val params = buildJsonObject { put("path", pdfPath.toString()) }
        val result = DocumentTool(TikaDocumentExtractor()).execute(params, project)

        assertFalse(result.isError, "PDF extraction must succeed; summary=${result.summary}")
        assertTrue(result.content.isNotBlank(), "extracted markdown must not be empty")
        assertTrue(
            result.content.contains("FR-001", ignoreCase = false),
            "PDF content must contain 'FR-001'; got: ${result.content.take(1000)}",
        )
        assertTrue(
            result.content.contains("Approved", ignoreCase = true),
            "PDF content must contain 'Approved'; got: ${result.content.take(1000)}",
        )
    }
}
