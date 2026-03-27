package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProblemViewTool : AgentTool {
    override val name = "problem_view"
    override val description = "Get current problems (errors, warnings) from the IDE's analysis. " +
        "Shows compilation errors, inspection warnings, and unresolved references. " +
        "Note: only shows problems for files that have been opened in the editor — " +
        "for unopened files, use the diagnostics tool instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "Specific file to check (e.g., 'src/main/kotlin/MyService.kt'). Lists all problem files if omitted."
            ),
            "severity" to ParameterProperty(
                type = "string",
                description = "Filter by severity: 'error', 'warning', or 'all' (default: 'all')",
                enumValues = listOf("error", "warning", "all")
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
        val severity = params["severity"]?.jsonPrimitive?.content ?: "all"

        if (severity !in setOf("error", "warning", "all")) {
            return ToolResult(
                "Error: severity must be 'error', 'warning', or 'all'",
                "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            if (filePath != null) {
                getProblemsForFile(filePath, severity, project)
            } else {
                getProblemsForAllOpenFiles(severity, project)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun getProblemsForFile(filePath: String, severity: String, project: Project): ToolResult {
        val (path, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return ReadAction.nonBlocking<ToolResult> {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                ?: return@nonBlocking ToolResult(
                    "File not found: $filePath",
                    "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val problems = collectHighlightProblems(vf, severity, project)
            val relativePath = project.basePath?.let {
                vf.path.removePrefix(it).removePrefix("/")
            } ?: vf.name

            if (problems.isEmpty()) {
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)
                if (hasProblemFlag) {
                    ToolResult(
                        "No detailed problems available for $relativePath (file flagged as problematic by IDE but no HighlightInfo — file may not be open in editor).",
                        "Flagged but no details", 10
                    )
                } else {
                    ToolResult("No problems in $relativePath.", "No problems", 5)
                }
            } else {
                val content = formatFileProblems(relativePath, problems)
                ToolResult(content, "${problems.size} problems in ${vf.name}", TokenEstimator.estimate(content))
            }
        }.executeSynchronously()
    }

    private suspend fun getProblemsForAllOpenFiles(severity: String, project: Project): ToolResult {
        return ReadAction.nonBlocking<ToolResult> {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            if (openFiles.isEmpty()) {
                return@nonBlocking ToolResult("No files are open in the editor.", "No open files", 5)
            }

            val wolf = WolfTheProblemSolver.getInstance(project)
            val filesWithProblems = mutableListOf<Pair<String, List<ProblemEntry>>>()

            for (vf in openFiles) {
                val problems = collectHighlightProblems(vf, severity, project)
                if (problems.isNotEmpty()) {
                    val relativePath = project.basePath?.let {
                        vf.path.removePrefix(it).removePrefix("/")
                    } ?: vf.name
                    filesWithProblems.add(relativePath to problems)
                } else if (wolf.isProblemFile(vf)) {
                    val relativePath = project.basePath?.let {
                        vf.path.removePrefix(it).removePrefix("/")
                    } ?: vf.name
                    filesWithProblems.add(relativePath to listOf(
                        ProblemEntry("WARNING", 0, "File flagged as problematic (no detailed info available)")
                    ))
                }
            }

            if (filesWithProblems.isEmpty()) {
                ToolResult("No problems found in ${openFiles.size} open file(s).", "No problems", 5)
            } else {
                val totalProblems = filesWithProblems.sumOf { it.second.size }
                val sb = StringBuilder()
                sb.appendLine("Problems in project (${filesWithProblems.size} file(s)):")
                for ((path, problems) in filesWithProblems) {
                    sb.appendLine()
                    sb.append(formatFileProblems(path, problems))
                }
                val content = sb.toString().trimEnd()
                ToolResult(
                    content,
                    "$totalProblems problems in ${filesWithProblems.size} files",
                    TokenEstimator.estimate(content)
                )
            }
        }.executeSynchronously()
    }

    private fun collectHighlightProblems(
        vf: com.intellij.openapi.vfs.VirtualFile,
        severity: String,
        project: Project
    ): List<ProblemEntry> {
        val document = PsiDocumentManager.getInstance(project).run {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        } ?: return emptyList()

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            ?: return emptyList()

        val problems = mutableListOf<ProblemEntry>()

        for (highlighter in markupModel.allHighlighters) {
            val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
            val infoSeverity = info.severity
            val description = info.description ?: continue

            val severityLabel = when {
                infoSeverity >= HighlightSeverity.ERROR -> "ERROR"
                infoSeverity >= HighlightSeverity.WARNING -> "WARNING"
                else -> continue // skip INFO and below
            }

            // Apply severity filter
            when (severity) {
                "error" -> if (severityLabel != "ERROR") continue
                "warning" -> if (severityLabel != "WARNING") continue
                // "all" — no filter
            }

            val line = document.getLineNumber(info.startOffset) + 1
            problems.add(ProblemEntry(severityLabel, line, description))
        }

        // Sort: errors first, then by line number
        problems.sortWith(compareBy({ if (it.severity == "ERROR") 0 else 1 }, { it.line }))

        // Cap at 30 problems per file
        return problems.take(30)
    }

    private fun formatFileProblems(relativePath: String, problems: List<ProblemEntry>): String {
        val sb = StringBuilder()
        sb.appendLine("$relativePath — ${problems.size} problem(s):")
        for (p in problems) {
            if (p.line > 0) {
                sb.appendLine("  ${p.severity} line ${p.line}: ${p.description}")
            } else {
                sb.appendLine("  ${p.severity}: ${p.description}")
            }
        }
        return sb.toString()
    }

    private data class ProblemEntry(
        val severity: String,
        val line: Int,
        val description: String
    )
}
