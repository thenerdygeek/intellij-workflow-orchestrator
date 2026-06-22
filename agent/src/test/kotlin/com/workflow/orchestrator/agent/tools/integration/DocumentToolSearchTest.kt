package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.DocumentSearchMatch
import com.workflow.orchestrator.core.model.DocumentSearchResult
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the `search` mode of [DocumentTool] (G-10). When `search="query"` is supplied, the
 * tool delegates to [DocumentArtifactService.search] and renders ranked snippets with
 * page/section/offset breadcrumbs instead of serving a content slice.
 *
 * Run: ./gradlew :agent:test --tests "*DocumentToolSearchTest*"
 */
class DocumentToolSearchTest {

    // basePath "/tmp" keeps the "/tmp/*.pdf" fixtures inside the project root once read_document
    // enforces PathValidator (A2).
    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true) {
        every { basePath } returns "/tmp"
    }

    private fun searchResult(
        query: String,
        matches: List<DocumentSearchMatch>,
        total: Int = matches.size,
        cap: Int = 15,
        sections: List<String> = emptyList(),
    ) = DocumentSearchResult(query, matches, total, cap, sections)

    // ── Schema ──────────────────────────────────────────────────────────────

    @Test
    fun `parameters declares search as optional string`() {
        val params = DocumentTool(artifactService = mockk(relaxed = true)).parameters
        assertTrue(params.properties.containsKey("search"), "must declare 'search' parameter")
        assertFalse(params.required.contains("search"), "'search' must be optional")
        assertEquals("string", params.properties["search"]!!.type)
    }

    @Test
    fun `description documents the search mode`() {
        val desc = DocumentTool(artifactService = mockk(relaxed = true)).description.lowercase()
        assertTrue(desc.contains("search"), "description must mention search; got: $desc")
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Test
    fun `search renders ranked match list with page, section and offset`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.search(any(), any(), any(), any()) } returns ToolResult.success(
            data = searchResult(
                "AAL2 reauthentication",
                listOf(
                    DocumentSearchMatch(41574, 23, "4.2.3 Reauthentication", "…«AAL2 reauthentication» SHALL occur…"),
                    DocumentSearchMatch(95796, 31, "6.2 Selecting AAL", "…choose the right «AAL2»…"),
                ),
                total = 2,
            ),
            summary = "2 matches for \"AAL2 reauthentication\".",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("search", "AAL2 reauthentication")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        val body = result.content
        assertTrue(body.contains("2 matches"), "must report the match count; got: $body")
        assertTrue(body.contains("AAL2 reauthentication"), "must echo the query; got: $body")
        assertTrue(body.contains("page 23"), "must render page; got: $body")
        assertTrue(body.contains("4.2.3 Reauthentication"), "must render section; got: $body")
        assertTrue(body.contains("41574"), "must render offset for navigation; got: $body")
        assertTrue(body.contains("AAL2 reauthentication» SHALL".replace("«", "")) || body.contains("SHALL occur"),
            "must render the snippet; got: $body")
    }

    @Test
    fun `search delegates to the service and ignores offset, page and section`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        val querySlot = slot<String>()
        coEvery { svc.search(any(), capture(querySlot), any(), any()) } returns ToolResult.success(
            data = searchResult("widget", listOf(DocumentSearchMatch(10, 1, "Intro", "…«widget»…"))),
            summary = "1 match for \"widget\".",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("search", "widget")
            put("offset", 5000)   // should be ignored — search wins
            put("page", 9)
            put("section", "Glossary")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        assertEquals("widget", querySlot.captured)
        coVerify(exactly = 1) { svc.search(any(), any(), any(), any()) }
        // The slice path must NOT be taken.
        coVerify(exactly = 0) { svc.read(any(), any(), any()) }
    }

    @Test
    fun `no-match search reports zero matches and surfaces available sections`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.search(any(), any(), any(), any()) } returns ToolResult.success(
            data = searchResult("zebra", emptyList(), total = 0, sections = listOf("Introduction", "Results")),
            summary = "No matches for \"zebra\".",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("search", "zebra")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError, "a no-match search is a valid (non-error) result")
        val body = result.content
        assertTrue(body.contains("No match", ignoreCase = true) || body.contains("0 match"),
            "must clearly say no matches; got: $body")
        assertTrue(body.contains("Introduction"), "must list sections for navigation; got: $body")
    }

    @Test
    fun `capped search states the cap and total so truncation is never silent`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        val matches = (1..5).map { DocumentSearchMatch(it * 100, 1, "S$it", "…«hit$it»…") }
        coEvery { svc.search(any(), any(), any(), any()) } returns ToolResult.success(
            data = searchResult("hit", matches, total = 42, cap = 5),
            summary = "42 matches for \"hit\" (showing first 5).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("search", "hit")
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        val body = result.content
        assertTrue(body.contains("42"), "must report the true total; got: $body")
        assertTrue(body.contains("5"), "must indicate the shown/cap count; got: $body")
    }

    // ── Absent search preserves slice behaviour ───────────────────────────────

    @Test
    fun `absent search still takes the slice path`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = DocumentSlice(
                content = "PLAIN SLICE BODY",
                startOffset = 0,
                endOffset = 16,
                remaining = 0,
                pageOfStart = 1,
                totalPages = 1,
                availableSections = emptyList(),
                sectionMatched = null,
            ),
            summary = "Read 16 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject { put("path", "/tmp/spec.pdf") }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        assertTrue(result.content.contains("PLAIN SLICE BODY"))
        coVerify(exactly = 1) { svc.read(any(), any(), any()) }
        coVerify(exactly = 0) { svc.search(any(), any(), any(), any()) }
    }

    @Test
    fun `blank search falls through to slice path`() = runTest {
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any(), any(), any()) } returns ToolResult.success(
            data = DocumentSlice("BODY", 0, 4, 0, 1, 1, emptyList(), sectionMatched = null),
            summary = "Read 4 chars (offset=0, remaining=0).",
        )
        val params = buildJsonObject {
            put("path", "/tmp/spec.pdf")
            put("search", "   ")   // blank → not a search
        }
        val result = DocumentTool(artifactService = svc).execute(params, project)
        assertFalse(result.isError)
        coVerify(exactly = 1) { svc.read(any(), any(), any()) }
        coVerify(exactly = 0) { svc.search(any(), any(), any(), any()) }
    }
}
