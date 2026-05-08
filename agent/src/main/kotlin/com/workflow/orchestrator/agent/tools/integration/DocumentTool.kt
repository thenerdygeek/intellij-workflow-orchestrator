package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.api.DocumentExtractor
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.model.ExtractOptions
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * Agent tool that wraps [DocumentExtractor] / [TikaDocumentExtractor] in the deferred tier.
 *
 * Registered as a deferred tool so it is discoverable via `tool_search` but does not burn
 * prompt tokens on every iteration. The LLM uses `read_file` for plain text; this tool
 * handles the binary/structured formats that `read_file` rejects.
 *
 * Registered in [com.workflow.orchestrator.agent.AgentService.registerAllTools] under the
 * "File" category.
 */
class DocumentTool(
    private val extractor: DocumentExtractor = TikaDocumentExtractor(),
    private val timeoutMsProvider: () -> Long = { 30_000L },
) : AgentTool {

    override val name = "read_document"

    override val description = """
        Read text content from a non-plaintext document file. Supports PDF, Word (DOC/DOCX),
        Excel (XLS/XLSX), PowerPoint (PPT/PPTX), RTF, ODT, EPUB, HTML, CSV.

        Use this when read_file rejects the file by extension. Common cases: a Jira PDF
        attachment, a downloaded build artifact, a requirements spec, a design doc, an
        engineering report, a bug-tracker spreadsheet.

        Returns Markdown with tables preserved as Markdown pipe tables (so you can reason
        about row/column relationships accurately). Multi-column PDFs are read in correct
        order. PDF tables are extracted via Tabula's lattice algorithm and merged across
        page boundaries. Office documents are read directly via Apache POI for cell-perfect
        accuracy.

        For scanned image PDFs without an embedded text layer, this tool returns an error.
        OCR is not available in v1.

        Default cap is 200 K characters; oversized documents are truncated with a marker.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(
                type = "string",
                description = "Absolute path to the document file. Must be readable.",
            ),
            "max_chars" to ParameterProperty(
                type = "integer",
                description = "Override max extracted characters (default 200000).",
            ),
        ),
        required = listOf("path"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER,
        WorkerType.TOOLER,
    )

    /** 30 s — generous enough for large PDFs but under the 120 s default tool timeout. */
    override val timeoutMs = 30_000L

    /** Spill to disk when output exceeds 30K chars (matches expected size for big docs). */
    override val outputConfig = ToolOutputConfig(maxChars = ToolOutputConfig.COMMAND_MAX_CHARS)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val pathArg = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'path' parameter is required.",
                summary = "Error: missing path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        val path = try {
            Paths.get(pathArg)
        } catch (e: InvalidPathException) {
            return ToolResult(
                content = "Error: Invalid path: $pathArg — ${e.reason}",
                summary = "Error: invalid path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        val maxChars: Int? = try {
            params["max_chars"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        // Read timeout per-call from settings (mirrors HttpClientFactory.timeoutsFromSettings).
        val options = ExtractOptions(maxChars = maxChars, timeoutMs = timeoutMsProvider())
        val result = extractor.extract(path, options)

        return if (result.isError) {
            ToolResult(
                content = "Error: ${result.summary}",
                summary = result.summary,
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        } else {
            val markdown = result.data!!.markdown
            ToolResult(
                content = markdown,
                summary = result.summary,
                tokenEstimate = TokenEstimator.estimate(markdown),
                isError = false,
            )
        }
    }
}
