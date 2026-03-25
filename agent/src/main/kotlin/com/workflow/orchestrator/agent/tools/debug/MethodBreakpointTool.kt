package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaMethodBreakpointType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.java.debugger.breakpoints.properties.JavaMethodBreakpointProperties

/**
 * Sets a breakpoint on method entry and/or exit. Works on interface methods
 * (triggers on all implementations).
 *
 * WARNING: Method breakpoints are significantly slower than line breakpoints
 * (5-10x) — use sparingly. Prefer line breakpoints when the exact location is known.
 *
 * Threading: Breakpoint creation via XBreakpointManager must run on EDT
 * inside a WriteAction. PSI lookups require ReadAction.
 */
class MethodBreakpointTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "method_breakpoint"
    override val description = "Set a breakpoint on method entry and/or exit. Works on interface methods (triggers on all implementations). WARNING: Method breakpoints are significantly slower than line breakpoints (5-10x) — use sparingly. Prefer line breakpoints when the exact location is known."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name (e.g., com.example.MyService)"
            ),
            "method_name" to ParameterProperty(
                type = "string",
                description = "Method name to set breakpoint on"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "Optional file path for resolution"
            ),
            "watch_entry" to ParameterProperty(
                type = "boolean",
                description = "Break on method entry (default: true)"
            ),
            "watch_exit" to ParameterProperty(
                type = "boolean",
                description = "Break on method exit (default: false)"
            )
        ),
        required = listOf("class_name", "method_name")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: class_name",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val methodName = params["method_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: method_name",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val watchEntry = params["watch_entry"]?.jsonPrimitive?.booleanOrNull ?: true
        val watchExit = params["watch_exit"]?.jsonPrimitive?.booleanOrNull ?: false

        // Warn if both entry and exit are disabled — breakpoint would never trigger
        if (!watchEntry && !watchExit) {
            return ToolResult(
                "Both watch_entry and watch_exit are false — the breakpoint would never trigger. Set at least one to true.",
                "Invalid config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            // Phase 1: ReadAction to find the class and method via PSI
            val psiResult = ReadAction.compute<PsiLookupResult, Exception> {
                val facade = JavaPsiFacade.getInstance(project)
                val psiClass = facade.findClass(className, GlobalSearchScope.allScope(project))
                    ?: return@compute PsiLookupResult.ClassNotFound

                val methods = psiClass.methods.filter { it.name == methodName }
                if (methods.isEmpty()) {
                    val availableMethods = psiClass.methods
                        .map { it.name }
                        .distinct()
                        .sorted()
                    return@compute PsiLookupResult.MethodNotFound(availableMethods)
                }

                val targetMethod = methods.first()
                val containingFile = targetMethod.containingFile?.virtualFile
                val lineNumber = if (containingFile != null) {
                    val document = com.intellij.psi.PsiDocumentManager.getInstance(project)
                        .getDocument(targetMethod.containingFile)
                    document?.getLineNumber(targetMethod.textOffset) ?: -1
                } else {
                    -1
                }

                val isOverloaded = methods.size > 1
                PsiLookupResult.Found(
                    fileUrl = containingFile?.url,
                    lineNumber = lineNumber,
                    isOverloaded = isOverloaded,
                    overloadCount = methods.size
                )
            }

            when (psiResult) {
                is PsiLookupResult.ClassNotFound -> ToolResult(
                    "Class not found: $className. Verify the fully qualified class name is correct.",
                    "Class not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                is PsiLookupResult.MethodNotFound -> {
                    val methodList = if (psiResult.availableMethods.isEmpty()) {
                        "No methods found in class."
                    } else {
                        "Available methods: ${psiResult.availableMethods.joinToString(", ")}"
                    }
                    ToolResult(
                        "Method '$methodName' not found in $className. $methodList",
                        "Method not found",
                        TokenEstimator.estimate(methodList),
                        isError = true
                    )
                }
                is PsiLookupResult.Found -> {
                    // Phase 2: EDT + WriteAction to create the breakpoint
                    withContext(Dispatchers.EDT) {
                        WriteAction.compute<ToolResult, Exception> {
                            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                            val bpType = XDebuggerUtil.getInstance()
                                .findBreakpointType(JavaMethodBreakpointType::class.java)

                            val props = JavaMethodBreakpointProperties()
                            props.myClassPattern = className
                            props.myMethodName = methodName
                            props.WATCH_ENTRY = watchEntry
                            props.WATCH_EXIT = watchExit

                            val fileUrl = psiResult.fileUrl ?: ""
                            val lineNumber = if (psiResult.lineNumber >= 0) psiResult.lineNumber else 0

                            val bp = bpManager.addLineBreakpoint(
                                bpType, fileUrl, lineNumber, props, false
                            ) ?: return@compute ToolResult(
                                "Failed to add method breakpoint on $className.$methodName — breakpoint manager rejected it.",
                                "Add failed",
                                ToolResult.ERROR_TOKEN_ESTIMATE,
                                isError = true
                            )

                            // Track for agent cleanup
                            controller.trackBreakpoint(bp)

                            // Build output
                            val sb = StringBuilder()
                            sb.appendLine("Method breakpoint set on $className.$methodName")
                            sb.appendLine("  Watch entry: $watchEntry")
                            sb.appendLine("  Watch exit: $watchExit")
                            if (psiResult.isOverloaded) {
                                sb.appendLine("  NOTE: Method is overloaded (${psiResult.overloadCount} variants). Breakpoint set on first match.")
                            }
                            sb.appendLine("  PERFORMANCE WARNING: Method breakpoints are 5-10x slower than line breakpoints. Use sparingly.")

                            val content = sb.toString().trimEnd()
                            ToolResult(
                                content,
                                "Method breakpoint on $className.$methodName",
                                TokenEstimator.estimate(content)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error setting method breakpoint: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /** Internal result type for PSI lookup phase. */
    private sealed class PsiLookupResult {
        data object ClassNotFound : PsiLookupResult()
        data class MethodNotFound(val availableMethods: List<String>) : PsiLookupResult()
        data class Found(
            val fileUrl: String?,
            val lineNumber: Int,
            val isOverloaded: Boolean,
            val overloadCount: Int
        ) : PsiLookupResult()
    }
}
