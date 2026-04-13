package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SemanticDiagnosticsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    companion object {
        /** Lines of buffer around the edited range to include in diagnostics. */
        private const val EDIT_LINE_BUFFER = 5
    }

    override val name = "diagnostics"
    override val description = "Check a file for compilation errors using the IDE's semantic analysis engine — syntax errors, unresolved references, type mismatches, missing imports. Faster and more precise than running mvn compile or gradle build. Use this instead of shell build commands to verify code correctness. When run after edit_file, automatically scopes to only NEW issues near the edited lines."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to check (e.g., 'src/main/kotlin/UserService.kt')")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (DumbService.isDumb(project)) {
            return ToolResult("IDE is still indexing. Try again shortly.", "Indexing", 5, isError = true)
        }

        // Check if there's a recent edit range for this file (set by EditFileTool)
        val canonicalPath = try { java.io.File(path!!).canonicalPath } catch (_: Exception) { path!! }
        val editRange = com.workflow.orchestrator.agent.tools.builtin.EditFileTool.lastEditLineRanges.remove(canonicalPath)

        return try {
            val result = ReadAction.nonBlocking<ToolResult?> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                    ?: return@nonBlocking ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@nonBlocking ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@nonBlocking null

                // Resolve the provider for this file's language
                val provider = registry.forFile(psiFile)
                    ?: return@nonBlocking ToolResult(
                        "Code intelligence not available for ${psiFile.language.displayName}",
                        "Unsupported type",
                        5
                    )

                // Compute the line filter: if we have an edit range, only report issues near it
                val filterRange = editRange?.let {
                    val start = maxOf(1, it.first - EDIT_LINE_BUFFER)
                    val end = it.last + EDIT_LINE_BUFFER
                    start..end
                }

                // Delegate diagnostics to the language provider
                val allProblems = provider.getDiagnostics(psiFile, null)

                // WolfTheProblemSolver check (file-level, always included — platform API)
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)

                // Filter to only issues near the edited lines (if edit range is known)
                val relevantProblems = if (filterRange != null) {
                    allProblems.filter { it.line in filterRange }
                } else {
                    allProblems
                }

                val skippedCount = allProblems.size - relevantProblems.size

                if (relevantProblems.isEmpty() && !hasProblemFlag) {
                    val suffix = if (filterRange != null && skippedCount > 0) {
                        " ($skippedCount pre-existing issue(s) outside your edit range were excluded)"
                    } else ""
                    ToolResult("No errors in ${vf.name} near your changes.$suffix", "No errors", 5)
                } else {
                    val shown = relevantProblems.take(20)
                    val lines = shown.map { diag -> "  Line ${diag.line}: ${diag.message}" }
                    val more = if (relevantProblems.size > 20) "\n... and ${relevantProblems.size - 20} more" else ""
                    val scopeNote = if (filterRange != null) " (lines ${filterRange.first}-${filterRange.last})" else ""
                    val skippedNote = if (skippedCount > 0) "\n  ($skippedCount pre-existing issue(s) outside edit range excluded)" else ""
                    val flagNote = if (hasProblemFlag && filterRange != null) "\n  Note: IDE flags this file as problematic (may have issues outside your edit)" else ""
                    val content = "${relevantProblems.size} issue(s) in ${vf.name}$scopeNote:\n${lines.joinToString("\n")}$more$skippedNote$flagNote"
                    ToolResult(content, "${relevantProblems.size} issues", TokenEstimator.estimate(content), isError = false)
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", 5, isError = true)
        }
    }
}
