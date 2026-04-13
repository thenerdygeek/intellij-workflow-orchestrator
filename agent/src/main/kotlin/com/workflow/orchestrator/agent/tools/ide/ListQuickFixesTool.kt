package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ListQuickFixesTool : AgentTool {
    override val name = "list_quickfixes"
    override val description = "List available quick fixes (Alt+Enter actions) for a specific line in a file. Shows what IntelliJ can auto-fix. Useful for understanding how to resolve errors."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path"),
            "line" to ParameterProperty(type = "integer", description = "Line number (1-based)")
        ),
        required = listOf("path", "line")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val line = params["line"]?.jsonPrimitive?.int
            ?: return ToolResult("Error: 'line' required (1-based)", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (line < 1) {
            return ToolResult("Error: 'line' must be >= 1", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        if (DumbService.isDumb(project)) {
            return ToolResult("IDE is still indexing. Try again shortly.", "Indexing", 5, isError = true)
        }

        return try {
            val result = ReadAction.nonBlocking<ToolResult?> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                    ?: return@nonBlocking ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@nonBlocking ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@nonBlocking null

                val document = psiFile.viewProvider.document
                    ?: return@nonBlocking ToolResult("No document for: $path", "No document", 5, isError = true)

                val targetLine = line - 1 // Convert to 0-based
                if (targetLine >= document.lineCount) {
                    return@nonBlocking ToolResult(
                        "Line $line exceeds file length (${document.lineCount} lines).",
                        "Invalid line",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val lineStartOffset = document.getLineStartOffset(targetLine)
                val lineEndOffset = document.getLineEndOffset(targetLine)

                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                val inspectionManager = InspectionManager.getInstance(project)
                val quickFixes = mutableListOf<QuickFixInfo>()

                for (toolWrapper in profile.getInspectionTools(psiFile)) {
                    if (toolWrapper !is LocalInspectionToolWrapper) continue
                    val key = HighlightDisplayKey.find(toolWrapper.shortName)
                    if (key == null || !profile.isToolEnabled(key, psiFile)) continue
                    val tool = toolWrapper.tool

                    try {
                        val holder = ProblemsHolder(inspectionManager, psiFile, false)
                        val visitor = tool.buildVisitor(holder, false)
                        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                            override fun visitElement(element: PsiElement) {
                                element.accept(visitor)
                                super.visitElement(element)
                            }
                        })

                        for (problem in holder.results) {
                            val problemLine = problem.lineNumber // 0-based
                            if (problemLine == targetLine) {
                                val fixes = problem.fixes
                                if (fixes != null && fixes.isNotEmpty()) {
                                    for (fix in fixes) {
                                        quickFixes.add(QuickFixInfo(
                                            fixName = fix.familyName,
                                            problem = problem.descriptionTemplate,
                                            inspection = toolWrapper.shortName
                                        ))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip inspections that fail
                    }
                }

                if (quickFixes.isEmpty()) {
                    ToolResult(
                        "No quick fixes available at line $line in ${vf.name}.",
                        "No fixes",
                        5
                    )
                } else {
                    // Deduplicate by fix name
                    val unique = quickFixes.distinctBy { it.fixName }
                    val lines = unique.take(MAX_FIXES).map { qf ->
                        "  - ${qf.fixName}\n    Problem: ${qf.problem} (${qf.inspection})"
                    }
                    val more = if (unique.size > MAX_FIXES) "\n... and ${unique.size - MAX_FIXES} more" else ""
                    val content = "${unique.size} quick fix(es) at line $line in ${vf.name}:\n${lines.joinToString("\n")}$more"
                    ToolResult(content, "${unique.size} fixes at line $line", TokenEstimator.estimate(content))
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error listing quick fixes: ${e.message}", "Error", 5, isError = true)
        }
    }

    private data class QuickFixInfo(
        val fixName: String,
        val problem: String,
        val inspection: String
    )

    companion object {
        private const val MAX_FIXES = 20
    }
}
