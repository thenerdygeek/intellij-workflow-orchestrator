package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.services.SessionDownloadDir
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * Deferred agent tool that loads an image surfaced by [read_document] into the LLM's
 * vision context via the existing [AttachmentStore] + [ToolResult.imageRefs] +
 * [BrainRouter] multimodal pipeline.
 *
 * The path is strictly confined to `{sessionDir}/downloads/` — the same directory
 * that `ImageExtractionService` writes extracted images into. Paths outside this
 * subtree, symlink escapes, and unsupported MIME types are all rejected before any
 * bytes are read.
 *
 * Registration: deferred tier ("File" category) in
 * [com.workflow.orchestrator.agent.AgentService.registerAllTools]. The LLM discovers
 * this tool via `tool_search` after seeing `[image: <path>]` markers from
 * `read_document`.
 *
 * Pipeline: on success, `ToolResult.imageRefs` carries one [ImageRefData] entry.
 * [AgentLoop] writes it as a `ContentBlock.ImageRef` alongside the tool result so
 * [BrainRouter] routes the next call through Sourcegraph's `/.api/completions/stream`
 * — the LLM literally sees the image on its next turn.
 *
 * Toggle: [PluginSettings.enableToolImageAutoload] — when false the path is still
 * validated and the file existence is confirmed, but imageRefs is left empty and a
 * friendly "disabled" message is returned so the LLM knows why it did not see an image.
 */
class ViewImageTool : AgentTool {

    override val name = "view_image"

    override val description = """
        Load an image file into your vision context. Use this when read_document
        surfaces an `[image: <path>]` reference and you need to see the image
        content (figures, screenshots, diagrams).

        Path must be under the current session's downloads/ directory — paths from
        outside the session are rejected for safety. Supported MIME types: png, jpeg,
        webp, gif.

        After this tool returns, your NEXT message includes the image content in your
        vision context automatically (no further action needed).
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(
                type = "string",
                description = "Absolute path of the image to load. Must be under the current session's downloads/ directory.",
            ),
        ),
        required = listOf("path"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER,
    )

    override val timeoutMs = 30_000L

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // Master kill switch — defence in depth for hot-reload races where the tool
        // was activated in a prior session before the flag was toggled off. Registration
        // is the primary gate (AgentService skips safeRegisterDeferred when OFF); this
        // body guard ensures the tool is always a no-op when master is disabled.
        val masterEnabled = try {
            com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.enableImageInput
        } catch (_: Exception) {
            false
        }
        if (!masterEnabled) {
            return ToolResult.error(
                "Visual support is disabled in settings (Tools > Workflow Orchestrator > AI Agent > Multimodal).",
                "Visual support disabled"
            )
        }

        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required 'path' argument", "Missing required 'path' argument")

        // Resolve the session downloads dir from the coroutine context.
        val downloadsDir = SessionDownloadDir.current()
            ?: return ToolResult.error(
                "view_image requires an active session (no downloads dir available in coroutine context)",
                "view_image: no active session"
            )
        // downloadsDir IS the {sessionDir}/downloads/ directory — its parent is sessionDir.
        val sessionDir = downloadsDir.parent

        val validated = try {
            PathValidator.resolveAndValidateForSessionDownloads(rawPath, sessionDir)
        } catch (e: SecurityException) {
            return ToolResult.error(e.message ?: "Path rejected by security check", "Path rejected: $rawPath")
        } catch (e: java.nio.file.NoSuchFileException) {
            return ToolResult.error("File not found: $rawPath", "File not found: $rawPath")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "Invalid path argument", "Invalid path: $rawPath")
        }

        val mime = mimeFromExtension(validated.fileName.toString())
        if (mime !in ALLOWED_MIMES) {
            return ToolResult.error(
                "Unsupported image MIME '$mime' for file '${validated.fileName}'. " +
                    "Allowed: ${ALLOWED_MIMES.joinToString()}",
                "Unsupported MIME: $mime"
            )
        }

        val bytes = try {
            Files.readAllBytes(validated)
        } catch (e: Exception) {
            return ToolResult.error("Failed to read file '${validated.fileName}': ${e.message}", "Read failed: ${validated.fileName}")
        }

        if (bytes.isEmpty()) {
            return ToolResult.error("Image file '${validated.fileName}' is empty.", "Empty image file")
        }

        // Check PluginSettings.enableToolImageAutoload. Best-effort — if settings
        // lookup fails (e.g., outside IntelliJ runtime in tests) we treat it as disabled.
        val autoLoadEnabled = try {
            com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.enableToolImageAutoload
        } catch (_: Exception) {
            false
        }

        if (!autoLoadEnabled) {
            val kb = bytes.size / 1024
            val content = "Image file '${validated.fileName}' ($kb KB, $mime) found on disk. " +
                "Tool image autoload is disabled in settings (Tools > Workflow Orchestrator > AI Agent > Multimodal). " +
                "Enable it to load images into your vision context."
            return ToolResult(
                content = content,
                summary = "Image autoload disabled — ${validated.fileName} ($kb KB)",
                tokenEstimate = TokenEstimator.estimate(content),
            )
        }

        // Save to the session's AttachmentStore via coroutine context element.
        val store = SessionAttachmentAccess.current()
            ?: return ToolResult.error(
                "AttachmentStore unavailable — view_image must be called inside an active agent session",
                "AttachmentStore unavailable"
            )

        val ref = try {
            store.store(bytes, mime, validated.fileName.toString())
        } catch (e: Exception) {
            return ToolResult.error(
                "Failed to store image '${validated.fileName}' in AttachmentStore: ${e.message}",
                "AttachmentStore write failed: ${validated.fileName}"
            )
        }

        val kb = bytes.size / 1024
        val content = "Image attached: ${validated.fileName} ($kb KB, $mime)"
        return ToolResult(
            content = content,
            summary = "Image ${validated.fileName} ($kb KB, $mime) attached for vision",
            tokenEstimate = TokenEstimator.estimate(content),
            imageRefs = listOf(
                com.workflow.orchestrator.core.services.ToolResult.ImageRefData(
                    sha256 = ref.sha256,
                    mime = ref.mime,
                    size = ref.size,
                    originalFilename = ref.originalFilename,
                )
            ),
        )
    }

    private fun mimeFromExtension(filename: String): String {
        val dot = filename.lastIndexOf('.')
        if (dot < 0) return "application/octet-stream"
        return when (filename.substring(dot + 1).lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        val ALLOWED_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
