package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GetMethodBodyTool : AgentTool {
    override val name = "get_method_body"
    override val description =
        "Get the full source code of a specific method including annotations, signature, and body. " +
        "More targeted than read_file — no need to know line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "method" to ParameterProperty(type = "string", description = "Method name to retrieve"),
            "class_name" to ParameterProperty(type = "string", description = "Class containing the method"),
            "context_lines" to ParameterProperty(
                type = "integer",
                description = "Lines of context before/after the method (default: 0, max: 5)"
            )
        ),
        required = listOf("method", "class_name")
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val methodName = params["method"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'method' parameter required",
                "Error: missing method",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val contextLines = (params["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 5)

        val content = ReadAction.nonBlocking<String> {
            val psiClass = PsiToolUtils.findClassAnywhere(project, className)
                ?: return@nonBlocking "Error: Class '$className' not found in project. " +
                        "Check the class name spelling or provide the fully qualified name."

            // Try direct (non-inherited) methods first
            var methods = psiClass.findMethodsByName(methodName, false).toList()

            // If not found in own class, check inherited
            val foundInherited = if (methods.isEmpty()) {
                val inheritedMethods = psiClass.findMethodsByName(methodName, true).toList()
                if (inheritedMethods.isNotEmpty()) {
                    methods = inheritedMethods
                    true
                } else {
                    false
                }
            } else {
                false
            }

            if (methods.isEmpty()) {
                val available = psiClass.methods.take(20).joinToString(", ") { it.name }
                val availableMsg = if (available.isNotEmpty()) "\nAvailable methods: $available" else ""
                return@nonBlocking "Error: Method '$methodName' not found in class '$className'.$availableMsg"
            }

            val inheritedHint = if (foundInherited) {
                val declaringClass = methods.first().containingClass?.qualifiedName ?: methods.first().containingClass?.name
                "\nNote: '$methodName' is inherited from '$declaringClass', not declared directly in '$className'.\n"
            } else {
                ""
            }

            val documentManager = PsiDocumentManager.getInstance(project)
            val overloadsToShow = methods.take(3)
            val hiddenCount = methods.size - overloadsToShow.size

            val sb = StringBuilder()
            if (inheritedHint.isNotEmpty()) sb.append(inheritedHint)

            overloadsToShow.forEachIndexed { index, method ->
                if (overloadsToShow.size > 1) {
                    sb.appendLine("(overload #${index + 1})")
                }

                val containingFile = method.containingFile
                val document = containingFile?.let { documentManager.getDocument(it) }
                val filePath = containingFile?.virtualFile?.path
                    ?.let { PsiToolUtils.relativePath(project, it) } ?: "unknown"

                if (document == null) {
                    sb.appendLine("// Source unavailable for ${method.name} in $filePath")
                    if (index < overloadsToShow.size - 1) sb.appendLine("---")
                    return@forEachIndexed
                }

                // Determine the start of annotations (annotations are siblings before the method in PSI,
                // but PsiMethod.textRange already includes its own modifier list which contains annotations).
                val methodStartOffset = method.textRange.startOffset
                val methodEndOffset = method.textRange.endOffset

                val methodStartLine = document.getLineNumber(methodStartOffset)
                val methodEndLine = document.getLineNumber(methodEndOffset)

                // Apply context clamped to document bounds
                val rangeStart = maxOf(0, methodStartLine - contextLines)
                val rangeEnd = minOf(document.lineCount - 1, methodEndLine + contextLines)

                sb.appendLine("// $filePath")

                for (lineIdx in rangeStart..rangeEnd) {
                    val lineStartOffset = document.getLineStartOffset(lineIdx)
                    val lineEndOffset = document.getLineEndOffset(lineIdx)
                    val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
                    val lineNum = lineIdx + 1
                    sb.appendLine("$lineNum: $lineText")
                }

                if (index < overloadsToShow.size - 1) {
                    sb.appendLine("---")
                }
            }

            if (hiddenCount > 0) {
                sb.appendLine("... ($hiddenCount more overload(s) not shown)")
            }

            sb.toString().trimEnd()
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Method body of '$className#$methodName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }
}
