package com.workflow.orchestrator.agent.tools.ide

import com.intellij.lang.LanguageImportStatements
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class OptimizeImportsTool : AgentTool {
    override val name = "optimize_imports"
    override val description = "Add missing imports and remove unused imports in a file. Use after editing to fix 'unresolved reference' errors caused by missing imports."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to optimize imports for")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path!!)
            ?: return ToolResult("File not found: $path", "Not found", 5, isError = true)

        return try {
            var result: ToolResult? = null
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Optimize Imports", null, {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        val optimizers = LanguageImportStatements.INSTANCE.forFile(psiFile)
                        var optimized = false
                        for (optimizer in optimizers) {
                            if (optimizer.supports(psiFile)) {
                                optimizer.processFile(psiFile).run()
                                optimized = true
                            }
                        }
                        result = if (optimized) {
                            ToolResult("Optimized imports in ${vf.name}.", "Imports optimized", 5, artifacts = listOf(path))
                        } else {
                            ToolResult("No import optimizer available for ${vf.name}.", "No optimizer", 5)
                        }
                    } else {
                        result = ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                    }
                })
            }
            result ?: ToolResult("Import optimization failed", "Error", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Import error", 5, isError = true)
        }
    }
}
