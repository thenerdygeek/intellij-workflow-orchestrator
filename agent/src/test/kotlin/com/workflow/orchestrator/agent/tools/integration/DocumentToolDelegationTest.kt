package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.DocumentCursor
import com.workflow.orchestrator.core.model.DocumentSlice
import com.workflow.orchestrator.core.services.DocumentArtifactService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DocumentToolDelegationTest {

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(
            "{" + pairs.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}"
        ) as JsonObject

    @Test
    fun `passes Offset cursor to the service and renders a continuation hint`() = runBlocking {
        val cursorSlot = slot<DocumentCursor>()
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any<Path>(), capture(cursorSlot), any()) } returns ToolResult.success(
            data = DocumentSlice(content = "page text", startOffset = 0, endOffset = 9, remaining = 5000, pageOfStart = 1, totalPages = 200),
            summary = "ok",
        )
        val tool = DocumentTool(artifactService = svc)
        val result = tool.execute(params("path" to "\"/tmp/x.pdf\"", "offset" to "0"), mockk<Project>())

        assertFalse(result.isError)
        assertEquals(DocumentCursor.Offset(0), cursorSlot.captured)
        assertTrue(result.content.contains("read_document(offset=9)"))
        assertTrue(result.content.contains("page 1 of 200"))
    }

    @Test
    fun `page param produces a Page cursor`() = runBlocking {
        val cursorSlot = slot<DocumentCursor>()
        val svc = mockk<DocumentArtifactService>()
        coEvery { svc.read(any<Path>(), capture(cursorSlot), any()) } returns ToolResult.success(
            data = DocumentSlice("p", 1000, 1001, 0, 47, 200), summary = "ok",
        )
        val tool = DocumentTool(artifactService = svc)
        tool.execute(params("path" to "\"/tmp/x.pdf\"", "page" to "47"), mockk<Project>())
        assertEquals(DocumentCursor.Page(47), cursorSlot.captured)
    }

    @Test
    fun `empty slice surfaces the service summary as the body so the LLM sees the instruction`() = runBlocking {
        val svc = io.mockk.mockk<com.workflow.orchestrator.core.services.DocumentArtifactService>()
        io.mockk.coEvery { svc.read(any<java.nio.file.Path>(), any(), any()) } returns
            com.workflow.orchestrator.core.services.ToolResult.success(
                data = com.workflow.orchestrator.core.model.DocumentSlice("", 0, 0, 0, null, null),
                summary = "Document extraction in progress — call read_document again shortly.",
            )
        val tool = DocumentTool(artifactService = svc)
        val result = tool.execute(params("path" to "\"/tmp/x.pdf\"", "offset" to "0"), io.mockk.mockk<com.intellij.openapi.project.Project>())
        assertFalse(result.isError)
        assertTrue(result.content.contains("in progress", ignoreCase = true),
            "in-progress instruction must be in content (LLM reads content, not summary); got: '${result.content}'")
    }

    @Test
    fun `non-empty slice still renders content plus continuation hint`() = runBlocking {
        val svc = io.mockk.mockk<com.workflow.orchestrator.core.services.DocumentArtifactService>()
        io.mockk.coEvery { svc.read(any<java.nio.file.Path>(), any(), any()) } returns
            com.workflow.orchestrator.core.services.ToolResult.success(
                data = com.workflow.orchestrator.core.model.DocumentSlice("real text", 0, 9, 100, 1, 5),
                summary = "ok",
            )
        val tool = DocumentTool(artifactService = svc)
        val result = tool.execute(params("path" to "\"/tmp/x.pdf\"", "offset" to "0"), io.mockk.mockk<com.intellij.openapi.project.Project>())
        assertTrue(result.content.startsWith("real text"))
        assertTrue(result.content.contains("read_document(offset=9)"))
    }
}
