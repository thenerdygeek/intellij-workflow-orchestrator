package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SemanticDiagnosticsTool : AgentTool {
    override val name = "semantic_diagnostics"
    override val description = "Get semantic errors for a file: unresolved references, type mismatches, missing imports, and syntax errors. More thorough than basic diagnostics — catches compilation errors that syntax checking misses."
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

        val extension = path!!.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("kt", "java")) {
            return ToolResult("Semantic diagnostics only available for .kt and .java files.", "Unsupported type", 5)
        }

        return try {
            ReadAction.compute<ToolResult, Exception> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                    ?: return@compute ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@compute ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)

                val problems = mutableListOf<String>()

                // 1. WolfTheProblemSolver check
                val wolf = WolfTheProblemSolver.getInstance(project)
                if (wolf.isProblemFile(vf)) {
                    problems.add("IDE flags this file as problematic")
                }

                // 2. Syntax errors (PsiErrorElement)
                PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java).forEach { error ->
                    val line = psiFile.viewProvider.document?.getLineNumber(error.textOffset)?.plus(1) ?: 0
                    problems.add("Line $line: Syntax error — ${error.errorDescription}")
                }

                // 3. Unresolved references (semantic errors — missing imports, unknown types)
                val unresolvedSeen = mutableSetOf<String>()
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        for (ref in element.references) {
                            if (ref.resolve() == null) {
                                val text = ref.canonicalText.take(60)
                                if (text.isNotBlank() && text !in unresolvedSeen && !text.startsWith("kotlin.") && !text.startsWith("java.lang.")) {
                                    unresolvedSeen.add(text)
                                    val line = psiFile.viewProvider.document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                                    problems.add("Line $line: Unresolved reference '$text'")
                                }
                            }
                        }
                    }
                })

                if (problems.isEmpty()) {
                    ToolResult("No semantic errors in ${vf.name}.", "No errors", 5)
                } else {
                    // Cap at 20 problems to avoid token bloat
                    val shown = problems.take(20)
                    val more = if (problems.size > 20) "\n... and ${problems.size - 20} more" else ""
                    val content = "${problems.size} issue(s) in ${vf.name}:\n${shown.joinToString("\n") { "  $it" }}$more"
                    ToolResult(content, "${problems.size} issues", TokenEstimator.estimate(content), isError = true)
                }
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", 5, isError = true)
        }
    }
}
