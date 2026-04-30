package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.AttachmentContentData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the [JiraTool] `download_attachment` action, specifically the
 * `read_document` hint appended when the downloaded file is a document-class
 * format that `read_document` can handle.
 *
 * Exercises [JiraTool.buildReadDocumentHint] directly (internal visibility) and
 * via the full [JiraTool.executeDownloadAttachmentForTest] path to verify the
 * hint appears in the final ToolResult content.
 *
 * Run with: ./gradlew :agent:test --tests "*JiraToolDownloadAttachment*"
 */
class JiraToolDownloadAttachmentTest {

    private val tool = JiraTool()

    // ── buildReadDocumentHint unit tests ─────────────────────────────────────

    @Test
    fun `pdf mime triggers read_document hint containing file path`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/pdf",
            filename = "spec.pdf",
            filePath = "/tmp/downloads/spec.pdf"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("read_document"), "hint must mention read_document")
        assertTrue(hint.contains("/tmp/downloads/spec.pdf"), "hint must contain the absolute file path")
        assertTrue(hint.contains("PDF"), "hint must name the document class 'PDF'")
    }

    @Test
    fun `xlsx mime triggers read_document hint mentioning spreadsheet`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            filename = "budget.xlsx",
            filePath = "/home/user/budget.xlsx"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("read_document"))
        assertTrue(hint.contains("spreadsheet"), "hint must use the word 'spreadsheet' for xlsx")
        assertTrue(hint.contains("/home/user/budget.xlsx"))
    }

    @Test
    fun `csv mime does NOT trigger read_document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "text/csv",
            filename = "data.csv",
            filePath = "/tmp/data.csv"
        )
        assertNull(hint, "CSV works with read_file; no read_document hint expected")
    }

    @Test
    fun `null mime with pdf extension triggers hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = null,
            filename = "report.pdf",
            filePath = "/var/tmp/report.pdf"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("read_document"))
        assertTrue(hint.contains("/var/tmp/report.pdf"))
    }

    @Test
    fun `image mime does NOT trigger read_document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "image/png",
            filename = "screenshot.png",
            filePath = "/tmp/screenshot.png"
        )
        assertNull(hint, "images are not supported by read_document v1; no hint expected")
    }

    // ── Additional MIME coverage ─────────────────────────────────────────────

    @Test
    fun `docx mime triggers document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            filename = "proposal.docx",
            filePath = "/tmp/proposal.docx"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("document"))
        assertTrue(hint.contains("read_document"))
    }

    @Test
    fun `pptx mime triggers presentation hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            filename = "slides.pptx",
            filePath = "/tmp/slides.pptx"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("presentation"))
        assertTrue(hint.contains("read_document"))
    }

    @Test
    fun `xls mime triggers spreadsheet hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/vnd.ms-excel",
            filename = "old.xls",
            filePath = "/tmp/old.xls"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("spreadsheet"))
    }

    @Test
    fun `doc mime triggers document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/msword",
            filename = "letter.doc",
            filePath = "/tmp/letter.doc"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("document"))
    }

    @Test
    fun `epub mime triggers document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/epub+zip",
            filename = "book.epub",
            filePath = "/tmp/book.epub"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("document"))
    }

    @Test
    fun `odt mime triggers document hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/vnd.oasis.opendocument.text",
            filename = "notes.odt",
            filePath = "/tmp/notes.odt"
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("document"))
    }

    @Test
    fun `text plain mime does NOT trigger hint`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "text/plain",
            filename = "readme.txt",
            filePath = "/tmp/readme.txt"
        )
        assertNull(hint, "plain text works with read_file; no read_document hint expected")
    }

    @Test
    fun `unknown mime with unknown extension returns null`() {
        val hint = tool.buildReadDocumentHint(
            mimeType = "application/octet-stream",
            filename = "archive.bin",
            filePath = "/tmp/archive.bin"
        )
        assertNull(hint)
    }

    // ── Integration: full execute path via mocked JiraService ────────────────

    @Test
    fun `execute download_attachment with pdf result includes read_document in content`() = runTest {
        val service = mockk<JiraService>()
        val attachmentData = AttachmentContentData(
            filename = "design-spec.pdf",
            mimeType = "application/pdf",
            sizeBytes = 204_800L,
            content = null,
            filePath = "/home/user/.workflow-orchestrator/jira/design-spec.pdf",
            attachmentId = "att-001"
        )
        coEvery { service.downloadAttachment("PROJ-1", "att-001") } returns ToolResult(
            data = attachmentData,
            summary = "Downloaded design-spec.pdf (204800 bytes) to /home/user/.workflow-orchestrator/jira/design-spec.pdf",
            isError = false
        )

        val result = tool.executeDownloadAttachmentForTest(
            key = "PROJ-1",
            attachmentId = "att-001",
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("read_document"), "content must mention read_document tool")
        assertTrue(
            result.content.contains("/home/user/.workflow-orchestrator/jira/design-spec.pdf"),
            "content must include the absolute file path"
        )
    }

    @Test
    fun `execute download_attachment with csv result does NOT include read_document`() = runTest {
        val service = mockk<JiraService>()
        val attachmentData = AttachmentContentData(
            filename = "export.csv",
            mimeType = "text/csv",
            sizeBytes = 1_024L,
            content = "col1,col2\nval1,val2",
            filePath = "/tmp/export.csv",
            attachmentId = "att-002"
        )
        coEvery { service.downloadAttachment("PROJ-2", "att-002") } returns ToolResult(
            data = attachmentData,
            summary = "Downloaded export.csv (1024 bytes) to /tmp/export.csv",
            isError = false
        )

        val result = tool.executeDownloadAttachmentForTest(
            key = "PROJ-2",
            attachmentId = "att-002",
            service = service
        )

        assertFalse(result.isError)
        assertFalse(
            result.content.contains("read_document"),
            "CSV should not get a read_document hint"
        )
    }

    @Test
    fun `execute download_attachment error result does NOT include read_document hint`() = runTest {
        val service = mockk<JiraService>()
        val emptyData = AttachmentContentData(
            filename = "",
            mimeType = null,
            sizeBytes = 0,
            content = null,
            filePath = "",
            attachmentId = "att-missing"
        )
        coEvery { service.downloadAttachment("PROJ-3", "att-missing") } returns ToolResult(
            data = emptyData,
            summary = "Attachment att-missing not found on PROJ-3.",
            isError = true
        )

        val result = tool.executeDownloadAttachmentForTest(
            key = "PROJ-3",
            attachmentId = "att-missing",
            service = service
        )

        assertTrue(result.isError)
        assertFalse(
            result.content.contains("read_document"),
            "error results must never carry a read_document hint"
        )
    }
}
