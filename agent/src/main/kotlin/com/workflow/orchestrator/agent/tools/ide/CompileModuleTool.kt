package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Compiles the project or a specific module using IntelliJ's incremental compiler.
 *
 * Much faster than `mvn compile` (2-5 seconds vs 30-60 seconds) because it
 * leverages IntelliJ's incremental compilation which only recompiles changed files.
 * Returns structured error output with file names and error messages.
 *
 * Threading: `CompilerManager.make()` must be called from EDT. It schedules
 * background compilation and invokes the callback on EDT when done. We bridge
 * the async callback to a coroutine using `suspendCancellableCoroutine`.
 */
class CompileModuleTool : AgentTool {
    override val name = "compile_module"
    override val description = "Compile the project using IntelliJ's incremental compiler. Much faster than 'mvn compile' (2-5 seconds vs 30-60). Returns structured error output with file and line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(
                type = "string",
                description = "Optional: specific module name to compile. If not provided, compiles the entire project."
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)

    companion object {
        private const val MAX_ERROR_MESSAGES = 20
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val moduleName = params["module"]?.jsonPrimitive?.content

        return try {
            suspendCancellableCoroutine { cont ->
                ApplicationManager.getApplication().invokeLater {
                    val compiler = CompilerManager.getInstance(project)

                    val scope = if (moduleName != null) {
                        val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                        if (module != null) {
                            compiler.createModuleCompileScope(module, false)
                        } else {
                            val available = ModuleManager.getInstance(project).modules
                                .map { it.name }
                                .joinToString(", ")
                            if (!cont.isCompleted) {
                                cont.resume(
                                    ToolResult(
                                        "Module '$moduleName' not found. Available modules: $available",
                                        "Module not found",
                                        TokenEstimator.estimate(available),
                                        isError = true
                                    )
                                )
                            }
                            return@invokeLater
                        }
                    } else {
                        compiler.createProjectCompileScope(project)
                    }

                    val target = moduleName ?: "project"

                    compiler.make(scope) { aborted, errors, warnings, context ->
                        val result = when {
                            aborted -> ToolResult(
                                "Compilation of $target was aborted.",
                                "Compilation aborted",
                                ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )

                            errors > 0 -> {
                                val messages = context.getMessages(CompilerMessageCategory.ERROR)
                                    .take(MAX_ERROR_MESSAGES)
                                    .joinToString("\n") { msg ->
                                        val file = msg.virtualFile?.name ?: "<unknown>"
                                        "  $file: ${msg.message}"
                                    }
                                val content = "Compilation of $target failed: $errors error(s), $warnings warning(s).\n\nErrors:\n$messages"
                                ToolResult(
                                    content,
                                    "$errors errors, $warnings warnings",
                                    TokenEstimator.estimate(content),
                                    isError = true
                                )
                            }

                            else -> {
                                val warningNote = if (warnings > 0) " with $warnings warning(s)" else ""
                                ToolResult(
                                    "Compilation of $target successful$warningNote: 0 errors.",
                                    "Build OK",
                                    ToolResult.ERROR_TOKEN_ESTIMATE
                                )
                            }
                        }
                        if (!cont.isCompleted) cont.resume(result)
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Compilation error: ${e.message}",
                "Compilation error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
