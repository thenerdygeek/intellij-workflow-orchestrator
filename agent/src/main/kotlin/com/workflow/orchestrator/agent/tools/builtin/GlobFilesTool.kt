package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class GlobFilesTool : AgentTool {
    override val name = "glob_files"
    override val description = "List files and directories matching a glob pattern within a specified directory. Returns file paths sorted by modification time (newest first). If a recursive pattern (e.g., '**/*.kt') is used, it will list all matching files recursively. Use this for file discovery — finding what files exist matching a name pattern. The path may also point under the agent's data directory (~/.workflow-orchestrator/) to discover spilled tool-output files; matches outside the project are emitted as absolute canonical paths. Do not use this tool to confirm the existence of files you may have created, as the tool result will let you know if the files were created successfully. For searching file CONTENTS, use search_code instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "Glob pattern to match files against (e.g., '**/*.kt' for all Kotlin files, 'src/**/*.java' for Java files under src, '*.xml' for top-level XML files, 'build.gradle*' for Gradle build files)."),
            "path" to ParameterProperty(type = "string", description = "The path of the directory to list contents for (absolute or relative to the project root). May also point under ~/.workflow-orchestrator/ to find spilled tool output. Defaults to project root."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum files to return. Default: 50.")
        ),
        required = listOf("pattern")
    )

    private val log = Logger.getInstance(GlobFilesTool::class.java)
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    companion object {
        private const val DEFAULT_MAX_RESULTS = 50
        private val SKIP_DIRS = setOf(
            ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees", ".workflow",
            "out", "dist", ".svn", ".hg", "__pycache__", ".tox", ".mypy_cache",
            "api-debug", "checkpoints"
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'pattern' parameter required", "Error: missing pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val path = params["path"]?.jsonPrimitive?.content
        val maxResults = try { params["max_results"]?.jsonPrimitive?.int } catch (_: Exception) { null } ?: DEFAULT_MAX_RESULTS

        val basePath = project.basePath
            ?: return ToolResult("Error: Project base path not available", "Error: no project path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val searchRoot = if (path != null) {
            val (validatedPath, pathError) = PathValidator.resolveAndValidateForRead(path, basePath)
            if (pathError != null) return pathError
            Paths.get(validatedPath!!)
        } else {
            canonicalize(basePath)
        }

        if (!Files.isDirectory(searchRoot)) {
            return ToolResult("Error: directory not found: $path", "Error: directory not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return ToolResult("Error: invalid glob pattern '$pattern': ${e.message}", "Error: invalid pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val matches = mutableListOf<Pair<String, Long>>() // (relativePath, lastModified)
        val projectRoot = canonicalize(basePath)

        Files.walkFileTree(searchRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir.fileName?.toString() in SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Early termination: collect enough candidates for sorting, then stop
                if (matches.size >= maxResults * 2) return FileVisitResult.TERMINATE
                val relativeToSearch = searchRoot.relativize(file)
                val insideProject = file.startsWith(projectRoot)
                val relativeToProject = if (insideProject) projectRoot.relativize(file) else null
                if (matcher.matches(relativeToSearch) || (relativeToProject != null && matcher.matches(relativeToProject)) || matcher.matches(file.fileName)) {
                    val emitted = (relativeToProject ?: file).toString().replace("\\", "/")
                    matches.add(emitted to attrs.lastModifiedTime().toMillis())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })

        if (matches.isEmpty()) {
            return ToolResult("No files found matching: $pattern", "No matches", ToolResult.ERROR_TOKEN_ESTIMATE)
        }

        // Sort by modification time (newest first)
        matches.sortByDescending { it.second }

        // Apply max_results limit after sorting
        val limited = matches.take(maxResults)
        val content = limited.joinToString("\n") { it.first }
        val truncatedNote = if (matches.size > maxResults) "\n... (limited to $maxResults of ${matches.size} results)" else ""
        val fullContent = content + truncatedNote

        return ToolResult(
            content = fullContent,
            summary = "Found ${limited.size} files matching '$pattern'",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }

    private fun canonicalize(path: String): Path = try {
        Paths.get(path).toRealPath()
    } catch (e: Exception) {
        log.warn("toRealPath failed for $path; falling back to non-canonical (inside-project relative paths may degrade)", e)
        Paths.get(path)
    }
}
