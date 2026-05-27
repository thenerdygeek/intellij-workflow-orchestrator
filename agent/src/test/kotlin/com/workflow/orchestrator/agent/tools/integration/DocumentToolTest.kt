package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.services.DocumentArtifactService
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

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

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
}
