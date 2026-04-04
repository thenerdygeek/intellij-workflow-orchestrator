package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
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
import kotlinx.serialization.json.jsonPrimitive

class RunInspectionsTool : AgentTool {
    override val name = "run_inspections"
    override val description = "Run IntelliJ code inspections on a file: unused code, null safety, performance, Spring misconfig. Returns problems with severity. Use to find issues beyond syntax/compilation errors."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to inspect"),
            "severity" to ParameterProperty(
                type = "string",
                description = "Minimum severity filter: 'ERROR', 'WARNING', or 'INFO'. Optional, defaults to WARNING.",
                enumValues = listOf("ERROR", "WARNING", "INFO")
            )
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val minSeverity = parseSeverity(params["severity"]?.jsonPrimitive?.content ?: "WARNING")

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

                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                val inspectionManager = InspectionManager.getInstance(project)
                val allProblems = mutableListOf<ProblemInfo>()

                for (toolWrapper in profile.getInspectionTools(psiFile)) {
                    if (toolWrapper !is LocalInspectionToolWrapper) continue
                    if (!toolWrapper.isEnabledByDefault) continue
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
                            val severity = mapHighlightType(problem.highlightType)
                            if (severity.ordinal <= minSeverity.ordinal) {
                                val line = problem.lineNumber + 1 // ProblemDescriptor uses 0-based
                                val fixes = problem.fixes?.map { it.familyName } ?: emptyList()
                                allProblems.add(ProblemInfo(
                                    line = line,
                                    severity = severity,
                                    message = problem.descriptionTemplate,
                                    inspection = toolWrapper.shortName,
                                    fixes = fixes
                                ))
                            }
                        }
                    } catch (_: Exception) {
                        // Some inspections may fail on certain file types — skip silently
                    }
                }

                if (allProblems.isEmpty()) {
                    ToolResult("No inspection problems found in ${vf.name} (severity >= $minSeverity).", "No problems", 5)
                } else {
                    allProblems.sortBy { it.line }
                    val shown = allProblems.take(MAX_PROBLEMS)
                    val lines = shown.map { p ->
                        val fixHint = if (p.fixes.isNotEmpty()) " [fixes: ${p.fixes.joinToString(", ")}]" else ""
                        "  Line ${p.line} [${p.severity}] ${p.message} (${p.inspection})$fixHint"
                    }
                    val more = if (allProblems.size > MAX_PROBLEMS) "\n... and ${allProblems.size - MAX_PROBLEMS} more" else ""
                    val content = "${allProblems.size} problem(s) in ${vf.name}:\n${lines.joinToString("\n")}$more"
                    ToolResult(content, "${allProblems.size} problems", TokenEstimator.estimate(content))
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error running inspections: ${e.message}", "Error", 5, isError = true)
        }
    }

    private fun parseSeverity(value: String): Severity {
        return when (value.uppercase()) {
            "ERROR" -> Severity.ERROR
            "INFO" -> Severity.INFO
            else -> Severity.WARNING
        }
    }

    private fun mapHighlightType(type: ProblemHighlightType): Severity {
        return when (type) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> Severity.ERROR
            ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> Severity.WARNING
            else -> Severity.INFO
        }
    }

    private enum class Severity { ERROR, WARNING, INFO }

    private data class ProblemInfo(
        val line: Int,
        val severity: Severity,
        val message: String,
        val inspection: String,
        val fixes: List<String>
    )

    companion object {
        private const val MAX_PROBLEMS = 30
    }
}
