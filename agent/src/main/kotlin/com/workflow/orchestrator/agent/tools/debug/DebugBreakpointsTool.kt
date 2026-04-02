package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaFieldBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaMethodBreakpointType
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.remote.RemoteConfiguration
import com.intellij.execution.remote.RemoteConfigurationType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaFieldBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaMethodBreakpointProperties
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Breakpoint management + debug session start/attach.
 *
 * 8 actions covering breakpoint CRUD and session lifecycle initiation.
 */
class DebugBreakpointsTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_breakpoints"

    override val description = """
Breakpoint management — add, remove, list breakpoints, and start/attach debug sessions.

Actions and their parameters:
- add_breakpoint(file, line, condition?, log_expression?, temporary?) → Add line breakpoint
- method_breakpoint(class_name, method_name, watch_entry?, watch_exit?) → Add method breakpoint
- exception_breakpoint(exception_class, caught?, uncaught?, condition?) → Break on exception
- field_watchpoint(class_name, field_name, file?, watch_read?, watch_write?) → Watch field access/modification
- remove_breakpoint(file, line) → Remove breakpoint at file:line
- list_breakpoints(file?) → List all breakpoints, optionally filtered by file
- start_session(config_name, wait_for_pause?) → Launch run config in debug mode
- attach_to_process(port, host?, name?) → Attach debugger to remote JVM

All breakpoint actions modify IDE state. start_session/attach_to_process create a debug session.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "add_breakpoint", "method_breakpoint", "exception_breakpoint", "field_watchpoint",
                    "remove_breakpoint", "list_breakpoints", "start_session", "attach_to_process"
                )
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number — for add_breakpoint, remove_breakpoint"
            ),
            "condition" to ParameterProperty(
                type = "string",
                description = "Optional conditional expression — for add_breakpoint, exception_breakpoint"
            ),
            "log_expression" to ParameterProperty(
                type = "string",
                description = "Optional expression to log when hit without stopping (log breakpoint) — for add_breakpoint"
            ),
            "temporary" to ParameterProperty(
                type = "boolean",
                description = "If true, breakpoint removed after first hit — for add_breakpoint"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name — for method_breakpoint, exception_breakpoint, field_watchpoint"
            ),
            "method_name" to ParameterProperty(
                type = "string",
                description = "Method name — for method_breakpoint"
            ),
            "field_name" to ParameterProperty(
                type = "string",
                description = "Field name to watch — for field_watchpoint"
            ),
            "watch_entry" to ParameterProperty(
                type = "boolean",
                description = "Break on method entry (default: true) — for method_breakpoint"
            ),
            "watch_exit" to ParameterProperty(
                type = "boolean",
                description = "Break on method exit (default: false) — for method_breakpoint"
            ),
            "watch_read" to ParameterProperty(
                type = "boolean",
                description = "Break on field read (default: false) — for field_watchpoint"
            ),
            "watch_write" to ParameterProperty(
                type = "boolean",
                description = "Break on field write (default: true) — for field_watchpoint"
            ),
            "exception_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified exception class name — for exception_breakpoint"
            ),
            "caught" to ParameterProperty(
                type = "boolean",
                description = "Break on caught exceptions (default: true) — for exception_breakpoint"
            ),
            "uncaught" to ParameterProperty(
                type = "boolean",
                description = "Break on uncaught exceptions (default: true) — for exception_breakpoint"
            ),
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration to launch in debug mode — for start_session"
            ),
            "wait_for_pause" to ParameterProperty(
                type = "integer",
                description = "Seconds to wait for first breakpoint hit (default 0) — for start_session"
            ),
            "host" to ParameterProperty(
                type = "string",
                description = "Host to connect to (default: localhost) — for attach_to_process"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Debug port to connect to (e.g., 5005) — for attach_to_process"
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Display name for the debug configuration — for attach_to_process"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "add_breakpoint" -> executeAddBreakpoint(params, project)
            "method_breakpoint" -> executeMethodBreakpoint(params, project)
            "exception_breakpoint" -> executeExceptionBreakpoint(params, project)
            "field_watchpoint" -> executeFieldWatchpoint(params, project)
            "remove_breakpoint" -> executeRemoveBreakpoint(params, project)
            "list_breakpoints" -> executeListBreakpoints(params, project)
            "start_session" -> executeStartSession(params, project)
            "attach_to_process" -> executeAttachToProcess(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint, remove_breakpoint, list_breakpoints, start_session, attach_to_process",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── add_breakpoint ──────────────────────────────────────────────────────

    private suspend fun executeAddBreakpoint(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return missingParam("line")
        val condition = params["condition"]?.jsonPrimitive?.content
        val logExpression = params["log_expression"]?.jsonPrimitive?.content
        val temporary = params["temporary"]?.jsonPrimitive?.booleanOrNull ?: false

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = resolveBreakpointType(vFile.name)
                    val zeroBasedLine = line - 1
                    val bp: XLineBreakpoint<*> = addLineBreakpointSafe(
                        bpManager as com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
                        bpType, vFile.url, zeroBasedLine, vFile, temporary
                    ) ?: return@compute ToolResult(
                        "Failed to add breakpoint at ${vFile.name}:$line — line may not be breakpointable",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }
                    if (logExpression != null) {
                        bp.logExpressionObject = XExpressionImpl.fromText(logExpression)
                        bp.suspendPolicy = SuspendPolicy.NONE
                    }

                    controller.trackBreakpoint(bp)

                    val fileName = vFile.name
                    val sb = StringBuilder("Breakpoint added at $fileName:$line")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    if (logExpression != null) sb.append("\n  Log expression: $logExpression")
                    val traits = mutableListOf<String>()
                    if (condition != null) traits.add("conditional")
                    if (logExpression != null) traits.add("log")
                    if (temporary) traits.add("temporary")
                    val suspendType = if (logExpression != null) "non-suspend" else "suspend"
                    traits.add(suspendType)
                    sb.append("\n  Type: ${traits.joinToString(", ")}")

                    val content = sb.toString()
                    ToolResult(content, "Breakpoint at $fileName:$line", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult("Error adding breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── method_breakpoint ───────────────────────────────────────────────────

    private suspend fun executeMethodBreakpoint(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content ?: return missingParam("class_name")
        val methodName = params["method_name"]?.jsonPrimitive?.content ?: return missingParam("method_name")
        val watchEntry = params["watch_entry"]?.jsonPrimitive?.booleanOrNull ?: true
        val watchExit = params["watch_exit"]?.jsonPrimitive?.booleanOrNull ?: false

        if (!watchEntry && !watchExit) {
            return ToolResult(
                "Both watch_entry and watch_exit are false — the breakpoint would never trigger. Set at least one to true.",
                "Invalid config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val psiResult = ReadAction.compute<PsiLookupResult, Exception> {
                val facade = JavaPsiFacade.getInstance(project)
                val psiClass = facade.findClass(className, GlobalSearchScope.allScope(project))
                    ?: return@compute PsiLookupResult.ClassNotFound

                val methods = psiClass.methods.filter { it.name == methodName }
                if (methods.isEmpty()) {
                    val availableMethods = psiClass.methods.map { it.name }.distinct().sorted()
                    return@compute PsiLookupResult.MethodNotFound(availableMethods)
                }

                val targetMethod = methods.first()
                val containingFile = targetMethod.containingFile?.virtualFile
                val lineNumber = if (containingFile != null) {
                    val document = PsiDocumentManager.getInstance(project)
                        .getDocument(targetMethod.containingFile)
                    document?.getLineNumber(targetMethod.textOffset) ?: -1
                } else {
                    -1
                }

                PsiLookupResult.Found(
                    fileUrl = containingFile?.url,
                    lineNumber = lineNumber,
                    isOverloaded = methods.size > 1,
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

                            controller.trackBreakpoint(bp)

                            val sb = StringBuilder()
                            sb.appendLine("Method breakpoint set on $className.$methodName")
                            sb.appendLine("  Watch entry: $watchEntry")
                            sb.appendLine("  Watch exit: $watchExit")
                            if (psiResult.isOverloaded) {
                                sb.appendLine("  NOTE: Method is overloaded (${psiResult.overloadCount} variants). Breakpoint set on first match.")
                            }
                            sb.appendLine("  PERFORMANCE WARNING: Method breakpoints are 5-10x slower than line breakpoints. Use sparingly.")

                            val content = sb.toString().trimEnd()
                            ToolResult(content, "Method breakpoint on $className.$methodName", TokenEstimator.estimate(content))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult("Error setting method breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── exception_breakpoint ────────────────────────────────────────────────

    private suspend fun executeExceptionBreakpoint(params: JsonObject, project: Project): ToolResult {
        val exceptionClass = params["exception_class"]?.jsonPrimitive?.content ?: return missingParam("exception_class")
        val caught = params["caught"]?.jsonPrimitive?.booleanOrNull ?: true
        val uncaught = params["uncaught"]?.jsonPrimitive?.booleanOrNull ?: true
        val condition = params["condition"]?.jsonPrimitive?.content

        if (exceptionClass.isBlank()) {
            return ToolResult("exception_class cannot be blank", "Invalid param", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaExceptionBreakpointType::class.java)
                        ?: return@compute ToolResult(
                            "Java exception breakpoint type not available — Java debugger plugin may not be installed",
                            "Type not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val props = JavaExceptionBreakpointProperties(exceptionClass)
                    props.NOTIFY_CAUGHT = caught
                    props.NOTIFY_UNCAUGHT = uncaught

                    val bp = bpManager.addBreakpoint(bpType, props)
                        ?: return@compute ToolResult(
                            "Failed to create exception breakpoint for $exceptionClass",
                            "Creation failed",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    if (condition != null) {
                        bp.conditionExpression = XExpressionImpl.fromText(condition)
                    }

                    controller.trackGeneralBreakpoint(bp)

                    val simpleName = exceptionClass.substringAfterLast('.')
                    val sb = StringBuilder("Exception breakpoint set for $exceptionClass")
                    sb.append("\n  Caught: $caught")
                    sb.append("\n  Uncaught: $uncaught")
                    if (condition != null) sb.append("\n  Condition: $condition")
                    sb.append("\n  Note: No validation that '$exceptionClass' exists in the classpath — verify the class name is correct")

                    val content = sb.toString()
                    ToolResult(content, "Exception breakpoint on $simpleName", TokenEstimator.estimate(content))
                }
            }
        } catch (e: Exception) {
            ToolResult("Error setting exception breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── field_watchpoint ────────────────────────────────────────────────────

    private suspend fun executeFieldWatchpoint(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content ?: return missingParam("class_name")
        val fieldName = params["field_name"]?.jsonPrimitive?.content ?: return missingParam("field_name")
        val filePath = params["file"]?.jsonPrimitive?.content
        val watchRead = params["watch_read"]?.jsonPrimitive?.booleanOrNull ?: false
        val watchWrite = params["watch_write"]?.jsonPrimitive?.booleanOrNull ?: true

        if (!watchRead && !watchWrite) {
            return ToolResult(
                "Warning: both watch_read and watch_write are false — watchpoint will never trigger. Set at least one to true.",
                "Will never trigger",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
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

            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val bpType = XDebuggerUtil.getInstance()
                        .findBreakpointType(JavaFieldBreakpointType::class.java)

                    val bp = bpManager.addLineBreakpoint(
                        bpType, fieldInfo.fileUrl, fieldInfo.lineNumber,
                        JavaFieldBreakpointProperties(fieldName, className), false
                    ) ?: return@compute ToolResult(
                        "Failed to add field watchpoint for $className.$fieldName at line ${fieldInfo.lineNumber + 1}",
                        "Add failed",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                    val props = bp.properties
                    if (props is JavaFieldBreakpointProperties) {
                        props.WATCH_ACCESS = watchRead
                        props.WATCH_MODIFICATION = watchWrite
                    }

                    controller.trackBreakpoint(bp)

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
            ToolResult("Error setting field watchpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── remove_breakpoint ───────────────────────────────────────────────────

    private suspend fun executeRemoveBreakpoint(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return missingParam("line")

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return try {
            withContext(Dispatchers.EDT) {
                WriteAction.compute<ToolResult, Exception> {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                        ?: return@compute ToolResult(
                            "File not found: $absolutePath",
                            "File not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )

                    val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val fileUrl = vFile.url
                    val zeroBasedLine = line - 1

                    val matchingBp = bpManager.allBreakpoints
                        .filterIsInstance<XLineBreakpoint<*>>()
                        .find { bp -> bp.fileUrl == fileUrl && bp.line == zeroBasedLine }

                    if (matchingBp == null) {
                        return@compute ToolResult(
                            "No breakpoint found at ${vFile.name}:$line",
                            "Not found",
                            ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }

                    bpManager.removeBreakpoint(matchingBp)
                    ToolResult(
                        "Breakpoint removed from ${vFile.name}:$line",
                        "Removed ${vFile.name}:$line",
                        ToolResult.ERROR_TOKEN_ESTIMATE
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult("Error removing breakpoint: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── list_breakpoints ────────────────────────────────────────────────────

    private suspend fun executeListBreakpoints(params: JsonObject, project: Project): ToolResult {
        val filterFile = params["file"]?.jsonPrimitive?.content

        return try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager

            val filterFileUrl = if (filterFile != null) {
                val absolutePath = if (File(filterFile).isAbsolute) {
                    filterFile
                } else {
                    val basePath = project.basePath
                        ?: return ToolResult("Cannot resolve relative path: project basePath is null", "Path error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                    File(basePath, filterFile).canonicalPath
                }
                LocalFileSystem.getInstance().findFileByPath(absolutePath)?.url
            } else {
                null
            }

            val lineBreakpoints = bpManager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { bp -> filterFileUrl == null || bp.fileUrl == filterFileUrl }

            if (lineBreakpoints.isEmpty()) {
                val qualifier = if (filterFile != null) " in $filterFile" else ""
                return ToolResult("No breakpoints found$qualifier.", "No breakpoints", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val sb = StringBuilder()
            sb.appendLine("Breakpoints (${lineBreakpoints.size}):")
            sb.appendLine()

            for (bp in lineBreakpoints) {
                val fileName = bp.fileUrl.substringAfterLast('/')
                val oneBased = bp.line + 1

                val traits = mutableListOf<String>()
                traits.add(if (bp.isEnabled) "enabled" else "disabled")
                val bpCondition = bp.conditionExpression?.expression
                if (!bpCondition.isNullOrBlank()) traits.add("conditional: $bpCondition")
                val logExpr = bp.logExpressionObject?.expression
                if (!logExpr.isNullOrBlank()) traits.add("log: $logExpr")
                if (bp.isTemporary) traits.add("temporary")
                if (bp.suspendPolicy == SuspendPolicy.NONE) traits.add("non-suspend")

                sb.appendLine("$fileName:$oneBased [${traits.joinToString(", ")}]")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${lineBreakpoints.size} breakpoints", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error listing breakpoints: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── start_session ───────────────────────────────────────────────────────

    private suspend fun executeStartSession(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content ?: return missingParam("config_name")
        val waitForPause = params["wait_for_pause"]?.jsonPrimitive?.intOrNull ?: 0

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Run configuration not found: '$configName'. Use get_run_configurations to list available configurations.",
                    "Config not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val sessionId = withTimeoutOrNull(30_000L) {
                suspendCancellableCoroutine<String> { cont ->
                    val connection = project.messageBus.connect()
                    cont.invokeOnCancellation { connection.disconnect() }
                    connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                        override fun processStarted(debugProcess: XDebugProcess) {
                            val session = debugProcess.session
                            val id = controller.registerSession(session)
                            connection.disconnect()
                            if (cont.isActive) cont.resume(id)
                        }
                    })

                    invokeLater {
                        try {
                            val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                            val env = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()
                            ProgramRunnerUtil.executeConfiguration(env, true, true)
                        } catch (e: Exception) {
                            connection.disconnect()
                            if (cont.isActive) cont.resume("")
                        }
                    }
                }
            }

            if (sessionId == null || sessionId.isEmpty()) {
                return ToolResult(
                    "Debug session failed to start within 30 seconds. Check run configuration, build errors, or port conflicts.",
                    "Debug session timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val pauseEvent = if (waitForPause > 0) {
                controller.waitForPause(sessionId, waitForPause * 1000L)
            } else {
                null
            }

            val sb = StringBuilder("Debug session started: $sessionId\n")
            sb.append("Configuration: $configName\n")
            if (pauseEvent != null) {
                sb.append("Status: paused\n")
                sb.append("Location: ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\n")
                sb.append("Reason: ${pauseEvent.reason}")
            } else if (waitForPause > 0) {
                sb.append("Status: running (no breakpoint hit within ${waitForPause}s)")
            } else {
                sb.append("Status: running")
            }

            val content = sb.toString()
            ToolResult(content, "Debug session $sessionId started", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error starting debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── attach_to_process ───────────────────────────────────────────────────

    private suspend fun executeAttachToProcess(params: JsonObject, project: Project): ToolResult {
        val host = params["host"]?.jsonPrimitive?.content ?: "localhost"
        val port = params["port"]?.jsonPrimitive?.intOrNull ?: return missingParam("port")
        val displayName = params["name"]?.jsonPrimitive?.content ?: "[Agent] Remote Debug $host:$port"

        if (port < 1 || port > 65535) {
            return ToolResult("Port must be between 1 and 65535, got $port", "Invalid port", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            val sessionId = withContext(Dispatchers.EDT) {
                val runManager = RunManager.getInstance(project)
                val remoteConfigType = RemoteConfigurationType.getInstance()
                val settings = runManager.createConfiguration(displayName, remoteConfigType.factory)
                val remoteConfig = settings.configuration as RemoteConfiguration
                remoteConfig.HOST = host
                remoteConfig.PORT = port.toString()
                remoteConfig.SERVER_MODE = false
                remoteConfig.USE_SOCKET_TRANSPORT = true

                val executor = DefaultDebugExecutor.getDebugExecutorInstance()
                val env = ExecutionEnvironmentBuilder.create(project, executor, remoteConfig).build()

                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<String> { cont ->
                        val connection = project.messageBus.connect()
                        cont.invokeOnCancellation { connection.disconnect() }
                        connection.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                            override fun processStarted(debugProcess: XDebugProcess) {
                                val session = debugProcess.session
                                val id = controller.registerSession(session)
                                connection.disconnect()
                                cont.resume(id)
                            }
                        })

                        ProgramRunnerUtil.executeConfiguration(env, true, true)
                    }
                }
            }

            if (sessionId == null) {
                return ToolResult(
                    "Failed to attach to $host:$port within 30 seconds. Verify the target JVM is running with JDWP agent enabled on port $port.",
                    "Attach timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val content = buildString {
                append("Attached to remote JVM: $host:$port\n")
                append("Session: $sessionId\n")
                append("Configuration: $displayName\n")
                append("Status: connected")
            }
            ToolResult(content, "Attached to $host:$port as $sessionId", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error attaching to process at $host:$port: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    private fun resolveBreakpointType(fileName: String): com.intellij.xdebugger.breakpoints.XLineBreakpointType<*> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return if (ext == "kt" || ext == "kts") {
            try {
                val kotlinType = Class.forName("org.jetbrains.kotlin.idea.debugger.breakpoints.KotlinLineBreakpointType")
                XDebuggerUtil.getInstance().findBreakpointType(
                    @Suppress("UNCHECKED_CAST")
                    (kotlinType as Class<out com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>>)
                )
            } catch (_: ClassNotFoundException) {
                XDebuggerUtil.getInstance().findBreakpointType(
                    com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
                )
            }
        } else {
            XDebuggerUtil.getInstance().findBreakpointType(
                com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType::class.java
            )
        }
    }

    private fun addLineBreakpointSafe(
        bpManager: com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
        bpType: com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>,
        fileUrl: String,
        line: Int,
        vFile: com.intellij.openapi.vfs.VirtualFile,
        temporary: Boolean
    ): XLineBreakpoint<*>? {
        return addTyped<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>(bpManager, bpType, fileUrl, line, vFile, temporary)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <P : com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>> addTyped(
        bpManager: com.intellij.xdebugger.impl.breakpoints.XBreakpointManagerImpl,
        bpType: com.intellij.xdebugger.breakpoints.XLineBreakpointType<*>,
        fileUrl: String,
        line: Int,
        vFile: com.intellij.openapi.vfs.VirtualFile,
        temporary: Boolean
    ): XLineBreakpoint<P>? {
        val typed = bpType as com.intellij.xdebugger.breakpoints.XLineBreakpointType<P>
        val properties = typed.createBreakpointProperties(vFile, line)
        return bpManager.addLineBreakpoint(typed, fileUrl, line, properties, temporary)
    }

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

    private data class FieldInfo(
        val fileUrl: String,
        val fileName: String,
        val lineNumber: Int
    )

    private fun findFieldInClass(
        project: Project,
        className: String,
        fieldName: String,
        filePath: String?
    ): FieldInfo? {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(className, GlobalSearchScope.projectScope(project))

        if (psiClass != null) {
            val field = psiClass.fields.firstOrNull { it.name == fieldName } ?: return null
            val containingFile = field.containingFile ?: return null
            val virtualFile = containingFile.virtualFile ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
            val lineNumber = document.getLineNumber(field.textOffset)
            return FieldInfo(fileUrl = virtualFile.url, fileName = virtualFile.name, lineNumber = lineNumber)
        }

        if (filePath != null) {
            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null || absolutePath == null) return null
            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
            val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
            val lineNumber = findFieldLineInDocument(document, fieldName)
            return FieldInfo(fileUrl = vFile.url, fileName = vFile.name, lineNumber = lineNumber)
        }

        return null
    }

    private fun findFieldLineInDocument(document: Document, fieldName: String): Int {
        val text = document.text
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
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
}
