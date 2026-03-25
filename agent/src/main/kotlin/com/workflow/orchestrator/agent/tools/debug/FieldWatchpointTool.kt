package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaFieldBreakpointType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.java.debugger.breakpoints.properties.JavaFieldBreakpointProperties

/**
 * Sets a field watchpoint that triggers when a specific field is read or written.
 *
 * Threading: PSI lookup via ReadAction, breakpoint creation via EDT + WriteAction.
 */
class FieldWatchpointTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "field_watchpoint"
    override val description = "Set a watchpoint that triggers when a specific field is read or written. " +
        "Use to find who is modifying a field. Moderate performance impact — faster than method breakpoints " +
        "but slower than line breakpoints."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name (e.g., \"com.example.MyClass\")"
            ),
            "field_name" to ParameterProperty(
                type = "string",
                description = "Field name to watch"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path (for resolving field declaration line via PSI). Optional — if omitted, class is located via PSI search."
            ),
            "watch_read" to ParameterProperty(
                type = "boolean",
                description = "Break on field read (default: false)"
            ),
            "watch_write" to ParameterProperty(
                type = "boolean",
                description = "Break on field write (default: true)"
            )
        ),
        required = listOf("class_name", "field_name")
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
        val fieldName = params["field_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Missing required parameter: field_name",
                "Missing param",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        val filePath = params["file"]?.jsonPrimitive?.content
        val watchRead = params["watch_read"]?.jsonPrimitive?.booleanOrNull ?: false
        val watchWrite = params["watch_write"]?.jsonPrimitive?.booleanOrNull ?: true

        // Warn if both watch flags are false — watchpoint will never trigger
        if (!watchRead && !watchWrite) {
            return ToolResult(
                "Warning: both watch_read and watch_write are false — watchpoint will never trigger. " +
                    "Set at least one to true.",
                "Will never trigger",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            // Step 1: Find the field line via PSI (ReadAction)
            val fieldInfo = withContext(Dispatchers.IO) {
                ReadAction.compute<FieldInfo?, Exception> {
                    findFieldInClass(project, className, fieldName, filePath)
                }
            }

            if (fieldInfo == null) {
                return ToolResult(
                    "Could not find field '$fieldName' in class '$className'. " +
                        "Ensure the class exists in the project scope and the field name is correct. " +
                        "For Kotlin properties, use the property name (backing field name is usually the same).",
                    "Field not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            // Step 2: Create the watchpoint on EDT + WriteAction
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaFieldBreakpointType::class.java)

                    val bp = bpManager.addLineBreakpoint(
                        bpType,
                        fieldInfo.fileUrl,
                        fieldInfo.lineNumber,
                        JavaFieldBreakpointProperties(fieldName, className),
                        false
                    ) ?: return@compute ToolResult(
                        "Failed to add field watchpoint for $className.$fieldName at line ${fieldInfo.lineNumber + 1}",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    // Configure watch access/modification flags
                    val props = bp.properties
                    if (props is JavaFieldBreakpointProperties) {
                        props.WATCH_ACCESS = watchRead
                        props.WATCH_MODIFICATION = watchWrite
                    }

                    // Track for agent cleanup
                    controller.trackBreakpoint(bp)

                    // Build output
                    val watchTypes = mutableListOf<String>()
                    if (watchRead) watchTypes.add("read")
                    if (watchWrite) watchTypes.add("write")
                    val watchDesc = watchTypes.joinToString(" + ")
                    val displayLine = fieldInfo.lineNumber + 1

                    val sb = StringBuilder("Field watchpoint set on $className.$fieldName")
                    sb.append("\n  File: ${fieldInfo.fileName}:$displayLine")
                    sb.append("\n  Watching: $watchDesc")

                    val content = sb.toString()
                    ToolResult(content, "Watchpoint on $className.$fieldName ($watchDesc)", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult(
                "Error setting field watchpoint: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Finds a field in a class via PSI and returns its file URL and line number.
     * Must be called inside ReadAction.
     */
    private fun findFieldInClass(
        project: Project,
        className: String,
        fieldName: String,
        filePath: String?
    ): FieldInfo? {
        // Try to find the class via PSI
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))

        if (psiClass != null) {
            // Search fields for the matching name
            val field = psiClass.fields.firstOrNull { it.name == fieldName }
                ?: return null

            val containingFile = field.containingFile ?: return null
            val virtualFile = containingFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
                ?: return null
            val lineNumber = document.getLineNumber(field.textOffset)

            return FieldInfo(
                fileUrl = virtualFile.url,
                fileName = virtualFile.name,
                lineNumber = lineNumber
            )
        }

        // Fallback: if file path is provided and class not found via PSI,
        // try resolving the file directly and use line 0 as best effort
        if (filePath != null) {
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null || absolutePath == null) return null

            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
            val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null

            // Try to find the field name in the document text as a fallback
            val lineNumber = findFieldLineInDocument(document, fieldName)
            return FieldInfo(
                fileUrl = vFile.url,
                fileName = vFile.name,
                lineNumber = lineNumber
            )
        }

        return null
    }

    /**
     * Simple text-based fallback to find a field declaration line in a document.
     * Returns the 0-based line number, or 0 if not found.
     */
    private fun findFieldLineInDocument(document: Document, fieldName: String): Int {
        val text = document.text
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            // Match common field declaration patterns
            val trimmed = line.trim()
            if (trimmed.contains(fieldName) &&
                (trimmed.contains("private ") || trimmed.contains("protected ") ||
                    trimmed.contains("public ") || trimmed.contains("val ") ||
                    trimmed.contains("var ") || trimmed.contains("static "))
            ) {
                return index
            }
        }
        return 0
    }

    private data class FieldInfo(
        val fileUrl: String,
        val fileName: String,
        val lineNumber: Int
    )
}
