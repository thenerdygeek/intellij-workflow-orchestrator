package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read the contents of a file at the specified path. Use this when you need to examine the contents of an existing file you do not know the contents of, for example to analyze code, review text files, or extract information from configuration files. Returned text lines are prefixed with line numbers (e.g. '1\t', '2\t'). These labels are metadata, not part of the file content. For large files, output is automatically limited to $DEFAULT_LIMIT lines — use offset and limit to read specific sections. May not be suitable for binary files (images, JARs, ZIPs), as it returns the raw content as a string. Do NOT use this tool to list the contents of a directory — use glob_files instead. Only use this tool on files, not directories."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "The path of the file to read (absolute or relative to the project root)."),
            "offset" to ParameterProperty(type = "integer", description = "The 1-based line number to start reading from (inclusive). Defaults to 1."),
            "limit" to ParameterProperty(type = "integer", description = "The number of lines to read. Defaults to $DEFAULT_LIMIT. Use with offset to read specific sections of large files.")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("read_file") {
        summary {
            technical("Read text content of a file with line-number prefixes; binary extensions and >10MB files are rejected; reads through IntelliJ's Document API so unsaved editor changes are visible.")
            plain("Like opening a file in your editor — the agent gets to see the lines, numbered, so it can refer to them later. Will refuse to open giant files or things that aren't text (zips, images, etc.).")
        }
        whatLLMSees(description)
        params {
            required("path", "string") {
                llmSeesIt("The path of the file to read (absolute or relative to the project root).")
                humanReadable("Where the file lives. Can be a path you'd type into your terminal: relative (`src/Main.kt`) or absolute (`/Users/me/proj/src/Main.kt`).")
                whenPresent("The path is canonicalised, validated against project boundaries via PathValidator, and the file is read.")
                constraint("must point inside the project root or another allow-listed root — `../etc/passwd`-style traversal is rejected before any file I/O")
                constraint("must point to a file, not a directory — use glob_files for directories")
                example("src/main/kotlin/Foo.kt")
                example("/Users/me/projects/myrepo/build.gradle")
                example(".workflow/skills/tdd/SKILL.md")
            }
            optional("offset", "integer") {
                llmSeesIt("The 1-based line number to start reading from (inclusive). Defaults to 1.")
                humanReadable("Skip past the first N lines — handy when you already know the section you want is further down (e.g. function at line 540 of a 1000-line file).")
                whenPresent("Reading begins at this 1-based line; output line numbers reflect the absolute file position so the LLM can edit by line later.")
                whenAbsent("Defaults to 1 — file is read from the top.")
                constraint("coerced to ≥ 1 (so `0` becomes `1`, negatives become `1`)")
                example("540")
            }
            optional("limit", "integer") {
                llmSeesIt("The number of lines to read. Defaults to 200. Use with offset to read specific sections of large files.")
                humanReadable("How many lines to grab — like the page size of an e-book reader. Lets the agent read a 10K-line file in chunks instead of choking on it all at once.")
                whenPresent("At most this many lines are returned, starting at `offset`. The output ends with `... (N more lines)` if there's more.")
                whenAbsent("Defaults to 200 lines — enough for most short files, less than 1K tokens.")
                example("50")
                example("500")
            }
        }
        verdict {
            keep(
                "Foundational. Almost every coding-agent task — reviewing a file, understanding a function, " +
                    "checking config — starts here. Removing this would force the LLM to use `run_command cat`, which " +
                    "loses line numbers, doesn't see unsaved editor changes, and doesn't enforce path validation.",
                VerdictSeverity.STRONG,
            )
        }
        related("search_code", Relationship.COMPLEMENT, "Use first when you know what you're looking for but not where it lives — then read_file the matches.")
        related("glob_files", Relationship.COMPLEMENT, "Use first when you know the file shape (e.g. `**/*Test.kt`) but not the exact path.")
        related("find_definition", Relationship.ALTERNATIVE, "Use instead when you want to jump to a symbol's definition rather than read an entire file.")
        related("edit_file", Relationship.COMPOSE_WITH, "Read first — edit_file requires you to have seen the exact text you're replacing.")
        downside("Binary extensions (jar, png, zip, etc.) return an error rather than the bytes — agent must use search_code or a binary-aware tool.")
        downside("Files over 10MB are rejected outright. No partial-read fallback — the agent has to pick a different approach (search_code, or shell-out to head).")
        downside("Line numbers are metadata, not file content — LLMs occasionally include the `123\\t` prefix when generating an edit, which then fails. Hardened by edit_file's exact-text matcher but worth flagging.")
        downside("No directory listing — explicitly errors if `path` is a directory. Surprising to new users, but glob_files covers that case.")
        flowchart("""
            flowchart TD
                A[LLM calls read_file] --> B{Path validates?}
                B -- no --> X1[Return path-traversal error]
                B -- yes --> C{File exists?}
                C -- no --> X2[Return file-not-found error]
                C -- yes --> D{Binary extension?}
                D -- yes --> X3[Return binary-file error]
                D -- no --> E{>10MB?}
                E -- yes --> X4[Return too-large error]
                E -- no --> F[Read via Document API or fallback]
                F --> G[Apply offset+limit]
                G --> H[Prefix each line with N\t]
                H --> I[Return content + truncation note]
        """)
        narrative("read_file")
    }

    companion object {
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LINE_CHARS = 2000
        private const val MAX_FILE_SIZE = 10_000_000L // 10MB
        private val BINARY_EXTENSIONS = setOf(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg",
            "zip", "tar", "gz", "war", "ear", "so", "dll", "exe",
            "pdf", "woff", "woff2", "ttf", "eot", "bin", "dat"
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidateForRead(rawPath, project.basePath)
        if (pathError != null) return pathError

        val resolvedPath = path!!
        val file = java.io.File(resolvedPath)
        if (!file.exists() || !file.isFile) {
            return ToolResult("Error: File not found: $resolvedPath", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Binary file detection
        if (file.extension.lowercase() in BINARY_EXTENSIONS) {
            return ToolResult(
                "Error: '${file.name}' is a binary file and cannot be read as text. Use search_code to find specific content.",
                "Binary file: ${file.name}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // File size check
        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult(
                "Error: '${file.name}' is ${file.length() / 1_000_000}MB — too large to read. Use search_code to find specific content, or use offset/limit to read a section.",
                "File too large: ${file.name}",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Try to read from Document (sees unsaved editor changes) or fall back to file I/O
        val allLines: List<String> = try {
            val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (vFile != null) {
                val text = com.intellij.openapi.application.readAction {
                    val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(vFile)
                    doc?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                }
                text.lines()
            } else {
                readLinesWithFallback(file)
            }
        } catch (_: Exception) {
            readLinesWithFallback(file)
        }

        val lines = allLines.ifEmpty { listOf("") }
        val offset = (params["offset"]?.jsonPrimitive?.int ?: 1).coerceAtLeast(1) - 1
        val limit = params["limit"]?.jsonPrimitive?.int ?: DEFAULT_LIMIT

        val selectedLines = lines.drop(offset).take(limit)
        val content = selectedLines.mapIndexed { idx, line ->
            val truncatedLine = if (line.length > MAX_LINE_CHARS) {
                line.take(MAX_LINE_CHARS) + " ... [line truncated at $MAX_LINE_CHARS chars]"
            } else line
            "${offset + idx + 1}\t$truncatedLine"
        }.joinToString("\n")

        val truncated = if (offset + limit < lines.size) "\n... (${lines.size - offset - limit} more lines)" else ""
        val fullContent = content + truncated

        return ToolResult(
            content = fullContent,
            summary = "Read ${selectedLines.size} lines from $rawPath (${lines.size} total)",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }

    /**
     * Read file with encoding fallback chain.
     * Priority: VirtualFile.charset (IDE-detected) > UTF-8 > ISO-8859-1 (Latin-1).
     *
     * COMPRESSION: Not directly related to compression, but encoding errors
     * produce garbled text that wastes context tokens. Correct encoding
     * ensures tool output is meaningful and token-efficient.
     */
    private fun readLinesWithFallback(file: java.io.File): List<String> {
        // Try UTF-8 first (most common modern encoding)
        try {
            val text = file.readText(Charsets.UTF_8)
            // Check for Unicode replacement character — indicates wrong encoding
            if (!text.contains('\uFFFD')) return text.lines()
        } catch (_: Exception) { }

        // Fallback: ISO-8859-1 (Latin-1) — lossless for all byte values,
        // handles Windows-1252 and legacy European encodings
        try {
            return file.readText(Charsets.ISO_8859_1).lines()
        } catch (_: Exception) { }

        // Final fallback: UTF-8 ignoring errors
        return file.readText(Charsets.UTF_8).lines()
    }
}
