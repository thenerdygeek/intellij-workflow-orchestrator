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
    fun `parameters declares offset as optional integer for pagination`() {
        val params = DocumentTool().parameters
        assertTrue(
            params.properties.containsKey("offset"),
            "must declare 'offset' parameter so LLM can paginate past max_chars cap"
        )
        assertFalse(params.required.contains("offset"), "'offset' must be optional")
        val offsetProp = params.properties["offset"]!!
        assertEquals("integer", offsetProp.type, "'offset' must be integer-typed")
        assertTrue(
            offsetProp.description.contains("offset", ignoreCase = true) ||
                offsetProp.description.contains("skip", ignoreCase = true),
            "'offset' description must explain its skip-N-chars semantics; got: ${offsetProp.description}",
        )
    }

    @Test
    fun `description warns LLM that max_chars is total cap not page cursor`() {
        val desc = DocumentTool().description.lowercase()
        assertTrue(
            desc.contains("offset"),
            "description must reference the 'offset' parameter so the LLM knows pagination is supported; " +
                "this is the LLM-facing schema text — it is the only place the runtime model reads pagination guidance",
        )
        assertTrue(
            desc.contains("not a page") || desc.contains("page cursor") || desc.contains("total"),
            "description must call out that max_chars is a total cap, not a page cursor (anti-footgun framing); " +
                "got: ${DocumentTool().description}",
        )
    }

    @Test
    fun `max_chars param description disambiguates from page cursor`() {
        val maxCharsProp = DocumentTool().parameters.properties["max_chars"]!!
        val text = maxCharsProp.description.lowercase()
        assertTrue(
            text.contains("offset") || text.contains("not a page") || text.contains("page cursor"),
            "max_chars param description must steer the LLM toward 'offset' for pagination; " +
                "got: ${maxCharsProp.description}",
        )
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

    @Test
    fun `execute with negative offset returns error without throwing`() = runTest {
        val params = buildJsonObject {
            put("path", "/tmp/anything.csv")
            put("offset", -1)
        }
        val result = DocumentTool().execute(params, project)
        assertTrue(result.isError, "negative offset must produce isError=true (validation, not silent)")
        assertTrue(
            result.content.contains("offset", ignoreCase = true),
            "error must mention 'offset' so LLM knows which param to fix; got: ${result.content}",
        )
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
    fun `execute with offset=0 matches no-offset behavior on CSV fixture`() = runBlocking {
        val csvPath = fixtureDir.resolve("data.csv")
        org.junit.jupiter.api.Assumptions.assumeTrue(csvPath.toFile().exists())

        val baseline = DocumentTool(TikaDocumentExtractor()).execute(
            buildJsonObject { put("path", csvPath.toString()) },
            project,
        )
        val withZeroOffset = DocumentTool(TikaDocumentExtractor()).execute(
            buildJsonObject {
                put("path", csvPath.toString())
                put("offset", 0)
            },
            project,
        )

        assertFalse(baseline.isError); assertFalse(withZeroOffset.isError)
        assertEquals(
            baseline.content,
            withZeroOffset.content,
            "offset=0 must be indistinguishable from omitting offset — preserves backward compatibility",
        )
    }

    @Test
    fun `execute with offset slices past first N chars on CSV fixture`() = runBlocking {
        val csvPath = fixtureDir.resolve("data.csv")
        org.junit.jupiter.api.Assumptions.assumeTrue(csvPath.toFile().exists())

        val baseline = DocumentTool(TikaDocumentExtractor()).execute(
            buildJsonObject { put("path", csvPath.toString()) },
            project,
        )
        assertFalse(baseline.isError)
        val fullLength = baseline.content.length
        org.junit.jupiter.api.Assumptions.assumeTrue(
            fullLength >= 10,
            "Skipping — extracted CSV too short ($fullLength) to test slicing",
        )

        // Skip the first 5 chars; the remaining content should not start with the same prefix.
        val sliced = DocumentTool(TikaDocumentExtractor()).execute(
            buildJsonObject {
                put("path", csvPath.toString())
                put("offset", 5)
            },
            project,
        )

        assertFalse(sliced.isError, "offset>0 must succeed; summary=${sliced.summary}")
        assertTrue(sliced.content.isNotBlank(), "sliced content must not be empty for a CSV with offset within bounds")
        assertNotEquals(
            baseline.content.take(5),
            sliced.content.take(5),
            "with offset=5, the sliced output must NOT start with the same 5 chars as the full extraction",
        )
        // The first 5 chars of baseline must appear nowhere in the sliced content's leading region.
        // (We allow them to appear later if they happen to repeat in the doc body — unlikely for our CSV.)
        assertFalse(
            sliced.content.startsWith(baseline.content.substring(0, 5)),
            "sliced content must not begin with the dropped prefix",
        )
    }

    @Test
    fun `execute with offset beyond document length returns graceful end-of-doc marker`() = runBlocking {
        val csvPath = fixtureDir.resolve("data.csv")
        org.junit.jupiter.api.Assumptions.assumeTrue(csvPath.toFile().exists())

        // 100K offset is far beyond a 48-byte CSV's extracted markdown.
        val result = DocumentTool(TikaDocumentExtractor()).execute(
            buildJsonObject {
                put("path", csvPath.toString())
                put("offset", 100_000)
            },
            project,
        )

        // Graceful: NOT isError=true (the tool worked, the doc just ended).
        // But the LLM must know it's at end-of-document, not staring at a silently empty result.
        assertFalse(
            result.isError,
            "offset past end is end-of-document, not a tool failure; got isError=true with summary=${result.summary}",
        )
        assertTrue(
            result.content.contains("offset", ignoreCase = true) ||
                result.content.contains("end", ignoreCase = true) ||
                result.content.contains("beyond", ignoreCase = true),
            "end-of-document content must say so explicitly so the LLM doesn't loop; got: ${result.content}",
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
