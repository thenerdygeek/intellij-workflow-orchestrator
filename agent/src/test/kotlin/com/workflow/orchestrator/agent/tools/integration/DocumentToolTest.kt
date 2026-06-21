package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DocumentTool].
 *
 * Validates schema, metadata, and error-path behaviour against the service-delegation
 * architecture. Integration/extraction tests (CSV, PDF fixtures, offset slicing, pagination
 * flow) are covered by DocumentArtifactStoreSliceTest, DocumentArtifactStorePersistTest,
 * and DocumentToolDelegationTest.
 *
 * Run with: ./gradlew :agent:test --tests "*DocumentToolTest*"
 */
class DocumentToolTest {

    private val svc = mockk<DocumentArtifactService>(relaxed = true)
    private fun tool() = DocumentTool(artifactService = svc)

    // basePath is "/tmp" so the existing "/tmp/..." document paths resolve INSIDE the project
    // root once read_document enforces PathValidator (A2). Validation is path-policy only —
    // no file existence is required.
    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true) {
        every { basePath } returns "/tmp"
    }

    // ── Metadata tests ────────────────────────────────────────────────────────

    @Test
    fun `name is read_document`() {
        assertEquals("read_document", tool().name)
    }

    @Test
    fun `description contains required tool_search keywords`() {
        val desc = tool().description.lowercase()
        assertTrue(desc.contains("pdf"), "description must mention 'pdf' for tool_search discoverability")
        assertTrue(desc.contains("docx"), "description must mention 'docx'")
        assertTrue(desc.contains("xlsx"), "description must mention 'xlsx'")
        assertTrue(desc.contains("spec"), "description must mention 'spec'")
        assertTrue(desc.contains("document"), "description must mention 'document'")
    }

    @Test
    fun `parameters includes path as required and max_chars as optional`() {
        val params = tool().parameters
        assertTrue(params.properties.containsKey("path"), "must declare 'path' parameter")
        assertTrue(params.properties.containsKey("max_chars"), "must declare 'max_chars' parameter")
        assertTrue(params.required.contains("path"), "'path' must be required")
        assertFalse(params.required.contains("max_chars"), "'max_chars' must be optional")
    }

    @Test
    fun `parameters declares offset as optional integer for pagination`() {
        val params = tool().parameters
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
    fun `parameters declares page and section as optional`() {
        val params = tool().parameters
        assertTrue(params.properties.containsKey("page"), "must declare 'page' parameter for page-number anchoring")
        assertTrue(params.properties.containsKey("section"), "must declare 'section' parameter for heading anchoring")
        assertFalse(params.required.contains("page"), "'page' must be optional")
        assertFalse(params.required.contains("section"), "'section' must be optional")
    }

    @Test
    fun `description warns LLM that max_chars is total cap not page cursor`() {
        val desc = tool().description.lowercase()
        assertTrue(
            desc.contains("offset"),
            "description must reference the 'offset' parameter so the LLM knows pagination is supported; " +
                "this is the LLM-facing schema text — it is the only place the runtime model reads pagination guidance",
        )
        assertTrue(
            desc.contains("not a page") || desc.contains("page cursor") || desc.contains("total"),
            "description must call out that max_chars is a total cap, not a page cursor (anti-footgun framing); " +
                "got: ${tool().description}",
        )
    }

    @Test
    fun `max_chars param description disambiguates from page cursor`() {
        val maxCharsProp = tool().parameters.properties["max_chars"]!!
        val text = maxCharsProp.description.lowercase()
        assertTrue(
            text.contains("offset") || text.contains("not a page") || text.contains("page cursor"),
            "max_chars param description must steer the LLM toward 'offset' for pagination; " +
                "got: ${maxCharsProp.description}",
        )
    }

    @Test
    fun `allowedWorkers includes standard roles`() {
        val workers = tool().allowedWorkers
        assertTrue(workers.contains(WorkerType.ORCHESTRATOR))
        assertTrue(workers.contains(WorkerType.CODER))
        assertTrue(workers.contains(WorkerType.ANALYZER))
    }

    @Test
    fun `toToolDefinition produces valid definition`() {
        val def = tool().toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("read_document", def.function.name)
        assertTrue(def.function.name.matches(Regex("^[a-zA-Z0-9_-]+$")))
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
    }

    // ── Error-handling tests (service not reached) ─────────────────────────────

    @Test
    fun `execute with no path returns error ToolResult without throwing`() = runTest {
        val result = tool().execute(buildJsonObject { }, project)
        assertTrue(result.isError, "missing path must produce isError=true")
        assertTrue(result.content.contains("path", ignoreCase = true), "error message should mention 'path'")
    }

    @Test
    fun `execute with negative offset returns error without throwing`() = runTest {
        val params = buildJsonObject {
            put("path", "/tmp/anything.csv")
            put("offset", -1)
        }
        val result = tool().execute(params, project)
        assertTrue(result.isError, "negative offset must produce isError=true (validation, not silent)")
        assertTrue(
            result.content.contains("offset", ignoreCase = true),
            "error must mention 'offset' so LLM knows which param to fix; got: ${result.content}",
        )
    }

    // ── A2: read_document must enforce the read allow-list (PathValidator) ──────
    //
    // Without this, read_document is an arbitrary-file-read sink (~/.ssh/id_rsa,
    // ~/.aws/credentials, /etc/passwd) whose extracted text flows back into chat — the exact
    // protection read_file already enforces. The service must NEVER be reached for an
    // out-of-tree path.

    @Test
    fun `execute rejects a path outside the project and agent allow-list`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        // Stubbed so that, WITHOUT the guard, execute() would happily reach + return this.
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(matched = null, available = emptyList(), content = "SENSITIVE-BYTES"),
            summary = "should never be reached for an out-of-tree path",
        )
        val outsideProject = mockk<com.intellij.openapi.project.Project>(relaxed = true) {
            every { basePath } returns "/tmp/some-project"
        }

        val result = DocumentTool(artifactService = svc).execute(
            buildJsonObject { put("path", "/etc/passwd") },   // classic arbitrary-read target
            outsideProject,
        )

        assertTrue(result.isError, "a path outside the project + agent data dir must be rejected; got: ${result.content}")
        assertTrue(
            listOf("outside", "project", "symlink", "restricted", "allow").any {
                result.content.contains(it, ignoreCase = true)
            },
            "rejection should explain the path is not allowed; got: ${result.content}",
        )
        // The security invariant: the artifact service must never read the file.
        coVerify(exactly = 0) { svc.read(any(), any(), any()) }
    }

    // ── Requirement C: explicit section miss + discoverability ─────────────────

    private fun sectionSlice(
        matched: Boolean?,
        available: List<String>,
        content: String = "BODY",
        availableTables: List<String> = emptyList(),
    ) =
        DocumentSlice(
            content = content,
            startOffset = 0,
            endOffset = content.length,
            remaining = 0,
            pageOfStart = 1,
            totalPages = 3,
            availableSections = available,
            sectionMatched = matched,
            availableTables = availableTables,
        )

    @Test
    fun `section miss surfaces a clear not-found message and lists available sections`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(matched = false, available = listOf("Introduction", "Results", "Conclusion")),
            summary = "Read 4 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("section", "nonexistent-section")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertTrue(
            result.content.contains("nonexistent-section"),
            "miss message must echo the requested section so the LLM knows what failed; got: ${result.content}",
        )
        assertTrue(
            result.content.contains("not found", ignoreCase = true) ||
                result.content.contains("no section", ignoreCase = true) ||
                result.content.contains("did not match", ignoreCase = true),
            "must explicitly say the section was not found; got: ${result.content}",
        )
        assertTrue(result.content.contains("Introduction"), "must list a valid section; got: ${result.content}")
        assertTrue(result.content.contains("Results"), "must list valid sections; got: ${result.content}")
    }

    @Test
    fun `section miss with no anchors at all tells the LLM to navigate by page`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(matched = false, available = emptyList()),
            summary = "Read 4 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("section", "Whatever")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertTrue(
            result.content.contains("page=", ignoreCase = true) ||
                result.content.contains("no reliable section", ignoreCase = true),
            "with zero anchors, must steer the LLM to page navigation; got: ${result.content}",
        )
        assertTrue(result.content.contains("3"), "should mention the page count; got: ${result.content}")
    }

    @Test
    fun `section hit does NOT emit a miss warning`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(matched = true, available = listOf("Introduction"), content = "Intro body"),
            summary = "Read 10 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("section", "Introduction")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        assertFalse(
            result.content.contains("not found", ignoreCase = true),
            "a successful section hit must not warn about a miss; got: ${result.content}",
        )
        assertTrue(result.content.contains("Intro body"), "must serve the section content; got: ${result.content}")
    }

    // ── Table navigation (section="Table N") + table discoverability ───────────

    @Test
    fun `section equals a table number renders the table region`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(
                matched = true,
                available = listOf("Pin Diagrams"),
                content = "PIN | DESCRIPTION rows here",
                availableTables = listOf("TABLE 1-2: PINOUT I/O DESCRIPTIONS"),
            ),
            summary = "Read 27 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/datasheet.pdf")
            put("section", "Table 1-2")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("PIN | DESCRIPTION"), "must render the resolved table region; got: ${result.content}")
        assertFalse(result.content.contains("not found", ignoreCase = true), "a table hit must not warn; got: ${result.content}")
    }

    @Test
    fun `section miss lists available TABLES separately from sections`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = sectionSlice(
                matched = false,
                available = listOf("Pin Diagrams", "Overview"),
                availableTables = listOf("Table 1-2. Pinout", "Table 1-3. Features"),
            ),
            summary = "Read 4 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/datasheet.pdf")
            put("section", "Table 99")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertTrue(result.content.contains("Table 99"), "must echo the requested section; got: ${result.content}")
        assertTrue(
            result.content.contains("Available tables", ignoreCase = true),
            "miss banner must surface available tables; got: ${result.content}",
        )
        assertTrue(result.content.contains("Table 1-2"), "must list a real table; got: ${result.content}")
        assertTrue(result.content.contains("Table 1-3"), "must list real tables; got: ${result.content}")
    }

    @Test
    fun `normal read surfaces available tables hint when content remains`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = DocumentSlice(
                content = "PAGE ONE",
                startOffset = 0,
                endOffset = 8,
                remaining = 5000,
                pageOfStart = 1,
                totalPages = 10,
                availableSections = listOf("Overview"),
                sectionMatched = null,
                availableTables = listOf("Table 1-2. Pinout", "Table 1-3. Features"),
            ),
            summary = "Read 8 chars (offset=0, remaining=5000).",
        )
        val params = buildJsonObject { put("path", "/tmp/spec.pdf") }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertTrue(
            result.content.contains("Tables:", ignoreCase = true),
            "a normal read should surface available table captions for discoverability; got: ${result.content}",
        )
        assertTrue(result.content.contains("Table 1-2"), "got: ${result.content}")
    }

    @Test
    fun `section param description mentions table and figure captions`() {
        val sectionProp = tool().parameters.properties["section"]!!
        val text = sectionProp.description.lowercase()
        assertTrue(
            text.contains("table"),
            "section param description must state it also matches table captions; got: ${sectionProp.description}",
        )
    }

    @Test
    fun `normal read appends a compact Sections hint when content remains`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = DocumentSlice(
                content = "PAGE ONE CONTENT",
                startOffset = 0,
                endOffset = 16,
                remaining = 5000,
                pageOfStart = 1,
                totalPages = 10,
                availableSections = listOf("Overview", "Design", "Testing"),
                sectionMatched = null,
            ),
            summary = "Read 16 chars (offset=0, remaining=5000).",
        )
        val params = buildJsonObject { put("path", "/tmp/spec.pdf") }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertTrue(
            result.content.contains("Sections:", ignoreCase = true),
            "a normal read should surface valid section names for discoverability; got: ${result.content}",
        )
        assertTrue(result.content.contains("Overview"), "got: ${result.content}")
        assertTrue(result.content.contains("Design"), "got: ${result.content}")
    }
}
