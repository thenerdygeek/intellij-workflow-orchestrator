package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.debugger.ui.HotSwapUIImpl
import com.intellij.debugger.ui.HotSwapStatusListener
import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaFieldBreakpointType
import com.intellij.debugger.ui.breakpoints.JavaMethodBreakpointType
import com.intellij.debugger.impl.DebuggerSession
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
import com.intellij.xdebugger.XSourcePosition
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

/**
 * Consolidated debug meta-tool replacing 24 individual debug tools.
 *
 * Saves token budget per API call by collapsing all debug operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint,
 *          remove_breakpoint, list_breakpoints, start_session, get_state,
 *          step_over, step_into, step_out, resume, pause, run_to_cursor, stop,
 *          evaluate, get_stack_frames, get_variables, thread_dump, memory_view,
 *          hotswap, force_return, drop_frame, attach_to_process
 */
class DebugTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug"

    override val description =
        "Interactive debugger — breakpoints, stepping, inspection, memory, hot swap, and remote attach.\n" +
        "Actions: add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint, " +
        "remove_breakpoint, list_breakpoints, start_session, get_state, step_over, step_into, " +
        "step_out, resume, pause, run_to_cursor, stop, evaluate, get_stack_frames, get_variables, " +
        "thread_dump, memory_view, hotswap, force_return, drop_frame, attach_to_process"

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "add_breakpoint", "method_breakpoint", "exception_breakpoint", "field_watchpoint",
                    "remove_breakpoint", "list_breakpoints", "start_session", "get_state",
                    "step_over", "step_into", "step_out", "resume", "pause", "run_to_cursor", "stop",
                    "evaluate", "get_stack_frames", "get_variables", "thread_dump", "memory_view",
                    "hotswap", "force_return", "drop_frame", "attach_to_process"
                )
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path relative to project or absolute — for add_breakpoint, remove_breakpoint, list_breakpoints, field_watchpoint, run_to_cursor"
            ),
            "line" to ParameterProperty(
                type = "integer",
                description = "1-based line number — for add_breakpoint, remove_breakpoint, run_to_cursor"
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
                description = "Fully qualified class name — for method_breakpoint, exception_breakpoint, field_watchpoint, memory_view"
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
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration to launch in debug mode — for start_session"
            ),
            "wait_for_pause" to ParameterProperty(
                type = "integer",
                description = "Seconds to wait for first breakpoint hit (default 0) — for start_session"
            ),
            "expression" to ParameterProperty(
                type = "string",
                description = "Java/Kotlin expression to evaluate — for evaluate"
            ),
            "thread_name" to ParameterProperty(
                type = "string",
                description = "Thread name to get stack from — for get_stack_frames"
            ),
            "max_frames" to ParameterProperty(
                type = "integer",
                description = "Maximum stack frames to return (default 20, max 50) — for get_stack_frames, thread_dump"
            ),
            "max_depth" to ParameterProperty(
                type = "integer",
                description = "Maximum depth for variable expansion (default 2, max 4) — for get_variables"
            ),
            "variable_name" to ParameterProperty(
                type = "string",
                description = "Specific variable name to deep-inspect — for get_variables"
            ),
            "include_stacks" to ParameterProperty(
                type = "boolean",
                description = "Include stack traces per thread (default: true) — for thread_dump"
            ),
            "include_daemon" to ParameterProperty(
                type = "boolean",
                description = "Include daemon threads (default: false) — for thread_dump"
            ),
            "max_instances" to ParameterProperty(
                type = "integer",
                description = "Max instances to list details for (0=count only). Default 0 — for memory_view"
            ),
            "compile_first" to ParameterProperty(
                type = "boolean",
                description = "Compile changed files before reloading (default: true) — for hotswap"
            ),
            "return_value" to ParameterProperty(
                type = "string",
                description = "Value to return: \"null\", \"42\", \"true\", etc. Omit for void — for force_return"
            ),
            "return_type" to ParameterProperty(
                type = "string",
                description = "Return type: void, null, int, long, boolean, string, double, float, char, byte, short, auto (default) — for force_return",
                enumValues = listOf("auto", "void", "null", "int", "long", "boolean", "string", "double", "float", "char", "byte", "short")
            ),
            "frame_index" to ParameterProperty(
                type = "integer",
                description = "Frame index to drop to (0=current, 1=caller). Default 0 — for drop_frame"
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
            "get_state" -> executeGetState(params, project)
            "step_over" -> executeStepAction(params, "step_over") { it.stepOver(false) }
            "step_into" -> executeStepAction(params, "step_into") { it.stepInto() }
            "step_out" -> executeStepAction(params, "step_out") { it.stepOut() }
            "resume" -> executeResume(params, project)
            "pause" -> executePause(params, project)
            "run_to_cursor" -> executeRunToCursor(params, project)
            "stop" -> executeStop(params, project)
            "evaluate" -> executeEvaluate(params, project)
            "get_stack_frames" -> executeGetStackFrames(params, project)
            "get_variables" -> executeGetVariables(params, project)
            "thread_dump" -> executeThreadDump(params, project)
            "memory_view" -> executeMemoryView(params, project)
            "hotswap" -> executeHotSwap(params, project)
            "force_return" -> executeForceReturn(params, project)
            "drop_frame" -> executeDropFrame(params, project)
            "attach_to_process" -> executeAttachToProcess(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
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

    // ── get_state ───────────────────────────────────────────────────────────

    private suspend fun executeGetState(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val resolvedId = sessionId ?: controller.getActiveSessionId()

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val sb = StringBuilder()
            sb.append("Session: ${resolvedId ?: "unknown"}\n")

            val isStopped = session.isStopped
            val isSuspended = session.isSuspended
            val pos = session.currentPosition

            val status = when {
                isStopped -> "STOPPED"
                isSuspended && pos != null -> {
                    val file = pos.file.name
                    val line = pos.line + 1
                    "PAUSED at $file:$line"
                }
                isSuspended -> "PAUSED"
                else -> "RUNNING"
            }
            sb.append("Status: $status\n")

            if (isSuspended) {
                sb.append("Reason: breakpoint\n")
            }

            val suspendContext = session.suspendContext
            if (suspendContext != null && isSuspended) {
                val activeStack = suspendContext.activeExecutionStack
                val allStacks = suspendContext.executionStacks

                val totalThreads = allStacks.size
                val suspendedCount = allStacks.size.coerceAtLeast(1)
                sb.append("Suspended threads: $suspendedCount of $totalThreads\n")

                if (activeStack != null) {
                    val threadName = activeStack.displayName
                    val frameDesc = if (pos != null) {
                        val currentFrame = session.currentStackFrame
                        val file = pos.file.name
                        val line = pos.line + 1
                        "$currentFrame".takeIf { it != "null" } ?: "$file:$line"
                    } else {
                        "unknown position"
                    }
                    sb.append("  $threadName (SUSPENDED) at $frameDesc\n")
                }

                allStacks.filter { it != activeStack }.take(5).forEach { stack ->
                    sb.append("  ${stack.displayName}\n")
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Debug state: $status", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting debug state: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── step_over / step_into / step_out ────────────────────────────────────

    private suspend fun executeStepAction(
        params: JsonObject,
        actionName: String,
        action: (com.intellij.xdebugger.XDebugSession) -> Unit
    ): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        return executeStep(controller, sessionId, actionName, action)
    }

    // ── resume ──────────────────────────────────────────────────────────────

    private suspend fun executeResume(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            session.resume()
            val content = "Session resumed. Session: ${resolvedId ?: "unknown"}"
            ToolResult(content, "Session resumed", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error resuming debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── pause ───────────────────────────────────────────────────────────────

    private suspend fun executePause(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            session.pause()

            val id = resolvedId ?: "unknown"
            val pauseEvent = controller.waitForPause(id, 5000)

            val content = if (pauseEvent != null) {
                "Session paused at ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $id"
            } else {
                "Pause requested. Session: $id"
            }

            ToolResult(content, "Pause requested", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error pausing debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── run_to_cursor ───────────────────────────────────────────────────────

    private suspend fun executeRunToCursor(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content ?: return missingParam("file")
        val line = params["line"]?.jsonPrimitive?.intOrNull ?: return missingParam("line")
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        if (line < 1) {
            return ToolResult("Line number must be >= 1, got: $line", "Invalid line", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            if (!session.isSuspended) {
                return ToolResult(
                    "Session is not suspended. Pause or wait for a breakpoint first.",
                    "Not suspended",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val (absolutePath, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
            if (pathError != null) return pathError

            val position = withContext(Dispatchers.EDT) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath!!)
                    ?: return@withContext null
                XDebuggerUtil.getInstance().createPosition(vFile, line - 1)
            } ?: return ToolResult("File not found: $absolutePath", "File not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            session.runToPosition(position, false)

            val id = resolvedId ?: "unknown"
            val pauseEvent = controller.waitForPause(id, 30000)

            val content = if (pauseEvent != null) {
                "Reached ${pauseEvent.file ?: "unknown"}:${pauseEvent.line ?: "?"}\nSession: $id"
            } else {
                "Run to cursor requested ($filePath:$line). Session did not pause within 30s.\nSession: $id"
            }

            ToolResult(content, "Run to cursor", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error running to cursor: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── stop ────────────────────────────────────────────────────────────────

    private suspend fun executeStop(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content

        return try {
            val resolvedId = sessionId ?: controller.getActiveSessionId()
            val session = controller.getSession(sessionId)
                ?: return ToolResult(
                    "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                    "No session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            session.stop()
            val content = "Debug session stopped. Session: ${resolvedId ?: "unknown"}"
            ToolResult(content, "Debug session stopped", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error stopping debug session: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── evaluate ────────────────────────────────────────────────────────────

    private suspend fun executeEvaluate(params: JsonObject, project: Project): ToolResult {
        val expression = params["expression"]?.jsonPrimitive?.content ?: return missingParam("expression")
        if (expression.isBlank()) {
            return ToolResult("Expression cannot be blank.", "Blank expression", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val sessionId = params["session_id"]?.jsonPrimitive?.content

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot evaluate expressions while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val evalResult = controller.evaluate(session, expression, 0)

            if (evalResult.isError) {
                return ToolResult("Error: ${evalResult.result}", "Evaluation error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val sb = StringBuilder()
            sb.append("Expression: $expression\n")
            sb.append("Result: ${evalResult.result}\n")
            sb.append("Type: ${evalResult.type}")

            val content = sb.toString()
            ToolResult(content, "Evaluated: $expression", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error evaluating expression: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── get_stack_frames ────────────────────────────────────────────────────

    private suspend fun executeGetStackFrames(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val maxFrames = (params["max_frames"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_FRAMES)
            .coerceIn(1, MAX_FRAMES_CAP)

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot get stack frames while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val frames = controller.getStackFrames(session, maxFrames)

            if (frames.isEmpty()) {
                return ToolResult("No stack frames available.", "No frames", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val threadName = params["thread_name"]?.jsonPrimitive?.content
                ?: session.suspendContext?.activeExecutionStack?.displayName
                ?: "main"

            val sb = StringBuilder()
            sb.append("Stack trace ($threadName thread, ${frames.size} frames):\n")

            for (frame in frames) {
                val location = buildString {
                    append(frame.methodName)
                    if (frame.file != null && frame.line != null) {
                        val fileName = frame.file.substringAfterLast('/')
                        append("($fileName:${frame.line})")
                    }
                }
                sb.append("#${frame.index}  $location\n")
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Stack trace: ${frames.size} frames", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting stack frames: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── get_variables ───────────────────────────────────────────────────────

    private suspend fun executeGetVariables(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val variableName = params["variable_name"]?.jsonPrimitive?.content
        val maxDepth = (params["max_depth"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_DEPTH)
            .coerceIn(1, MAX_DEPTH_CAP)

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Cannot inspect variables while running.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val frame = session.currentStackFrame
                ?: return ToolResult("No active stack frame available.", "No frame", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val effectiveDepth = if (variableName != null) maxDepth.coerceAtLeast(DEFAULT_MAX_DEPTH) else maxDepth
            val variables = controller.getVariables(frame, effectiveDepth)

            if (variables.isEmpty()) {
                return ToolResult("No variables in the current frame.", "No variables", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val targetVars = if (variableName != null) {
                val match = variables.filter { it.name == variableName }
                if (match.isEmpty()) {
                    return ToolResult(
                        "Variable '$variableName' not found in frame. Available: ${variables.joinToString(", ") { it.name }}",
                        "Variable not found",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                match
            } else {
                variables
            }

            val pos = session.currentPosition
            val frameHeader = if (pos != null) {
                val file = pos.file.name
                val line = pos.line + 1
                "Frame #0: $file:$line"
            } else {
                "Frame #0"
            }

            val sb = StringBuilder()
            sb.append("$frameHeader\n\nVariables:\n")
            sb.append(formatVariables(targetVars))

            var content = sb.toString()
            if (content.length > MAX_OUTPUT_CHARS) {
                content = content.take(MAX_OUTPUT_CHARS) +
                    "\n... (use variable_name to inspect specific variable)"
            }

            val varCount = targetVars.size
            val summary = if (variableName != null) {
                "Variable '$variableName' inspected"
            } else {
                "$varCount variables in frame #0"
            }
            ToolResult(content, summary, TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting variables: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── thread_dump ─────────────────────────────────────────────────────────

    private suspend fun executeThreadDump(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val includeStacks = params["include_stacks"]?.jsonPrimitive?.booleanOrNull ?: true
        val maxFrames = params["max_frames"]?.jsonPrimitive?.intOrNull ?: 20
        val includeDaemon = params["include_daemon"]?.jsonPrimitive?.booleanOrNull ?: false

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val threadInfos = controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine
                val allThreads = vm.allThreads()
                allThreads.mapNotNull { thread ->
                    val isDaemon = inferDaemon(thread)
                    if (!includeDaemon && isDaemon) return@mapNotNull null

                    val threadName = try { thread.name() } catch (_: Exception) { "<unknown>" }
                    val status = try { thread.status() } catch (_: Exception) { THREAD_STATUS_UNKNOWN }
                    val threadId = try { thread.uniqueID() } catch (_: Exception) { -1L }
                    val isSuspended = try { thread.isSuspended } catch (_: Exception) { false }

                    val frames = if (includeStacks) {
                        try {
                            thread.frames().take(maxFrames).map { frame ->
                                val location = frame.location()
                                val clsName = try { location.declaringType().name() } catch (_: Exception) { "<unknown>" }
                                val methName = try { location.method().name() } catch (_: Exception) { "<unknown>" }
                                val sourceName = try { location.sourceName() } catch (_: Exception) { null }
                                val lineNumber = try { location.lineNumber() } catch (_: Exception) { -1 }
                                ThreadFrameInfo(clsName, methName, sourceName, lineNumber)
                            }
                        } catch (_: Exception) { null }
                    } else { null }

                    ThreadInfo(
                        name = threadName, id = threadId, status = status,
                        statusText = statusToString(status), isDaemon = isDaemon,
                        isSuspended = isSuspended, frames = frames
                    )
                }
            }

            if (threadInfos.isEmpty()) {
                return ToolResult("Thread dump returned empty — VM may be disconnected.", "Empty thread dump", ToolResult.ERROR_TOKEN_ESTIMATE)
            }

            val suspendedCount = threadInfos.count { it.isSuspended }
            val sb = StringBuilder()
            sb.append("Thread dump (${threadInfos.size} threads, $suspendedCount suspended):\n")

            for (thread in threadInfos) {
                sb.append("\n[${thread.statusText}] ${thread.name} (id=${thread.id}")
                if (thread.isDaemon) sb.append(", daemon")
                sb.append(")\n")

                if (thread.frames == null && includeStacks) {
                    sb.append("  (frames unavailable — thread not suspended)\n")
                } else if (thread.frames != null) {
                    for (frame in thread.frames) {
                        val sourceRef = if (frame.sourceName != null && frame.lineNumber > 0) {
                            "${frame.sourceName}:${frame.lineNumber}"
                        } else if (frame.sourceName != null) {
                            frame.sourceName
                        } else {
                            "Unknown Source"
                        }
                        sb.append("  ${frame.className}.${frame.methodName}($sourceRef)\n")
                    }
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Thread dump: ${threadInfos.size} threads, $suspendedCount suspended", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error getting thread dump: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── memory_view ─────────────────────────────────────────────────────────

    private suspend fun executeMemoryView(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val className = params["class_name"]?.jsonPrimitive?.content ?: return missingParam("class_name")
        val maxInstances = params["max_instances"]?.jsonPrimitive?.intOrNull ?: 0

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult(
                "Session is not suspended. Memory view requires the debug session to be paused.",
                "Not suspended",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            controller.executeOnManagerThread(session) { _, vmProxy ->
                val vm = vmProxy.virtualMachine

                if (!vm.canGetInstanceInfo()) {
                    return@executeOnManagerThread ToolResult(
                        "VM does not support instance info (canGetInstanceInfo=false). This may be a remote or non-HotSpot JVM.",
                        "Not supported",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val refTypes = vm.classesByName(className)
                if (refTypes.isEmpty()) {
                    return@executeOnManagerThread ToolResult(
                        "Class '$className' is not loaded in the JVM. It may not have been instantiated yet, or the name may be incorrect.",
                        "Class not loaded",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val counts = vm.instanceCounts(refTypes)
                val totalCount = counts.sum()

                val content = buildString {
                    append("Memory view for: $className\n")
                    append("Total live instances: $totalCount\n")

                    if (refTypes.size > 1) {
                        append("\nBreakdown by type:\n")
                        refTypes.forEachIndexed { i, refType ->
                            append("  ${refType.name()}: ${counts[i]}\n")
                        }
                    }

                    if (maxInstances > 0 && totalCount > 0) {
                        val cappedMax = maxInstances.coerceAtMost(MAX_INSTANCE_DETAILS)
                        append("\nInstance details (first $cappedMax):\n")
                        val instances = refTypes[0].instances(cappedMax.toLong())
                        instances.forEachIndexed { i, instance ->
                            append("  [$i] ${instance.referenceType().name()} @ ${instance.uniqueID()}\n")
                        }
                        if (totalCount > cappedMax) {
                            append("  ... and ${totalCount - cappedMax} more\n")
                        }
                    }

                    append("\nSession: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}")
                }

                ToolResult(content, "$totalCount instances of $className", TokenEstimator.estimate(content))
            }
        } catch (e: Exception) {
            ToolResult("Error viewing memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── hotswap ─────────────────────────────────────────────────────────────

    private suspend fun executeHotSwap(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val compileFirst = params["compile_first"]?.jsonPrimitive?.booleanOrNull ?: true

        controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val hotSwapUI = HotSwapUI.getInstance(project)
            val debuggerManager = DebuggerManagerEx.getInstanceEx(project)
            val debuggerSession = debuggerManager.sessions.firstOrNull()
                ?: return ToolResult(
                    "No active debugger session found in DebuggerManagerEx.",
                    "No debugger session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val status = withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    (hotSwapUI as HotSwapUIImpl).reloadChangedClasses(
                        debuggerSession, compileFirst,
                        object : HotSwapStatusListener {
                            override fun onSuccess(sessions: MutableList<DebuggerSession>) { cont.resume("success") }
                            override fun onFailure(sessions: MutableList<DebuggerSession>) { cont.resume("failure") }
                            override fun onCancel(sessions: MutableList<DebuggerSession>) { cont.resume("cancelled") }
                            override fun onNothingToReload(sessions: MutableList<DebuggerSession>) { cont.resume("nothing_to_reload") }
                        }
                    )
                }
            } ?: "timeout"

            val resolvedId = sessionId ?: controller.getActiveSessionId() ?: "unknown"
            val content = buildString {
                append("Hot swap result: $status\n")
                append("Session: $resolvedId\n")
                append("Compile first: $compileFirst\n")
                when (status) {
                    "success" -> append("Classes reloaded successfully. Execution continues with new code.")
                    "failure" -> append("Hot swap failed. Check for structural changes (new/removed methods, fields, or signature changes).")
                    "cancelled" -> append("Hot swap was cancelled by the user or IDE.")
                    "nothing_to_reload" -> append("No changed classes detected. Make code changes first.")
                    "timeout" -> append("Hot swap timed out after 60 seconds. Check compilation and IDE status.")
                }
            }

            val isError = status == "failure" || status == "timeout"
            ToolResult(content, "Hot swap: $status", TokenEstimator.estimate(content), isError = isError)
        } catch (e: Exception) {
            ToolResult("Error during hot swap: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── force_return ────────────────────────────────────────────────────────

    private suspend fun executeForceReturn(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val returnValue = params["return_value"]?.jsonPrimitive?.content
        val returnType = params["return_type"]?.jsonPrimitive?.content ?: "auto"

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult("Session is not suspended. Cannot force return while running.", "Not suspended", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            controller.executeOnManagerThread(session) { debugProcess, vmProxy ->
                if (!vmProxy.canForceEarlyReturn()) {
                    throw IllegalStateException(
                        "JVM does not support force early return. This requires a JDWP-compliant JVM with canForceEarlyReturn capability."
                    )
                }

                val suspendContext = debugProcess.suspendManager.getPausedContext()
                    ?: throw IllegalStateException("No suspended context available. Ensure the session is paused.")

                val thread = (suspendContext as? SuspendContextImpl)?.thread
                    ?: throw IllegalStateException("Cannot access the suspended thread. The thread may not be available.")

                val effectiveType = if (returnType == "auto") inferReturnType(returnValue) else returnType

                val value: com.sun.jdi.Value? = when (effectiveType) {
                    "void" -> vmProxy.mirrorOfVoid()
                    "null" -> null
                    "int" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for int type")
                        vmProxy.mirrorOf(v.toInt())
                    }
                    "long" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for long type")
                        vmProxy.mirrorOf(v.toLong())
                    }
                    "boolean" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for boolean type")
                        vmProxy.mirrorOf(v.toBoolean())
                    }
                    "string" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for string type")
                        vmProxy.mirrorOf(v)
                    }
                    "double" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for double type")
                        vmProxy.mirrorOf(v.toDouble())
                    }
                    "float" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for float type")
                        vmProxy.mirrorOf(v.toFloat())
                    }
                    "char" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for char type")
                        if (v.isEmpty()) throw IllegalArgumentException("return_value for char type cannot be empty")
                        vmProxy.mirrorOf(v[0])
                    }
                    "byte" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for byte type")
                        vmProxy.mirrorOf(v.toByte())
                    }
                    "short" -> {
                        val v = returnValue ?: throw IllegalArgumentException("return_value required for short type")
                        vmProxy.mirrorOf(v.toShort())
                    }
                    else -> throw IllegalArgumentException("Unknown return type: $effectiveType")
                }

                thread.forceEarlyReturn(value)
            }

            val sb = StringBuilder()
            sb.append("Forced early return from current method.\n")
            sb.append("Return type: $returnType\n")
            if (returnValue != null) {
                sb.append("Return value: $returnValue\n")
            } else {
                sb.append("Return value: (void/none)\n")
            }
            sb.append("The method will return immediately, skipping remaining execution.")

            val content = sb.toString()
            ToolResult(content, "Forced return with ${returnValue ?: "void"}", TokenEstimator.estimate(content))
        } catch (e: com.sun.jdi.IncompatibleThreadStateException) {
            ToolResult("Thread is not in a compatible state for force return. The thread must be suspended at a non-native frame.", "Incompatible thread state", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: com.sun.jdi.NativeMethodException) {
            ToolResult("Cannot force return from a native method. Step out of the native method first.", "Native method", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: com.sun.jdi.InvalidTypeException) {
            ToolResult("Type mismatch: the return value type does not match the method's return type. ${e.message}", "Type mismatch", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: IllegalStateException) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: IllegalArgumentException) {
            ToolResult("Invalid parameter: ${e.message}", "Invalid parameter", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: NumberFormatException) {
            ToolResult("Invalid number format for return value: ${e.message}", "Number format error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            ToolResult("Error forcing return: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── drop_frame ──────────────────────────────────────────────────────────

    private suspend fun executeDropFrame(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val frameIndex = params["frame_index"]?.jsonPrimitive?.intOrNull ?: 0

        if (frameIndex < 0) {
            return ToolResult("frame_index must be >= 0, got $frameIndex", "Invalid frame index", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (!session.isSuspended) {
            return ToolResult("Session is not suspended. Cannot drop frame while running.", "Not suspended", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return try {
            controller.executeOnManagerThread(session) { _, vmProxy ->
                if (!vmProxy.canPopFrames()) {
                    return@executeOnManagerThread ToolResult(
                        "VM does not support frame popping (canPopFrames=false). This may be a remote or non-HotSpot JVM.",
                        "Not supported",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val suspendContext = session.suspendContext
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspend context available. Session may not be properly paused.",
                        "No suspend context",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val activeStack = suspendContext.activeExecutionStack
                    ?: return@executeOnManagerThread ToolResult(
                        "No active execution stack. Cannot determine current thread.",
                        "No active stack",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val vm = vmProxy.virtualMachine
                val threads = vm.allThreads()
                val threadName = activeStack.displayName

                val thread = threads.firstOrNull { t ->
                    t.name() == threadName && t.isSuspended
                } ?: threads.firstOrNull { it.isSuspended }
                    ?: return@executeOnManagerThread ToolResult(
                        "No suspended thread found matching '$threadName'.",
                        "No suspended thread",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )

                val frames = thread.frames()
                if (frameIndex >= frames.size) {
                    return@executeOnManagerThread ToolResult(
                        "frame_index $frameIndex is out of range. Stack has ${frames.size} frames (0..${frames.size - 1}).",
                        "Frame out of range",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val targetFrame = frames[frameIndex]
                val location = targetFrame.location()
                val methodName = "${location.declaringType().name()}.${location.method().name()}"

                thread.popFrames(targetFrame)

                val content = buildString {
                    append("Dropped frame: rewound to beginning of $methodName\n")
                    append("Frame index: $frameIndex\n")
                    append("Thread: ${thread.name()}\n")
                    append("Session: ${sessionId ?: controller.getActiveSessionId() ?: "unknown"}\n")
                    append("\nNote: Variable state is NOT reset. Side effects are NOT undone.")
                }
                ToolResult(content, "Dropped frame to $methodName", TokenEstimator.estimate(content))
            }
        } catch (e: Exception) {
            ToolResult("Error dropping frame: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
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

    // --- Breakpoint helpers (from AddBreakpointTool companion) ---

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

    // --- PSI lookup result (from MethodBreakpointTool) ---

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

    // --- Field lookup helpers (from FieldWatchpointTool) ---

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

    // --- Force return helpers (from ForceReturnTool) ---

    private fun inferReturnType(returnValue: String?): String {
        if (returnValue == null) return "void"
        return when {
            returnValue == "null" -> "null"
            returnValue == "true" || returnValue == "false" -> "boolean"
            returnValue.startsWith("\"") && returnValue.endsWith("\"") -> "string"
            returnValue.contains(".") -> "double"
            returnValue.toLongOrNull() != null -> {
                val num = returnValue.toLong()
                if (num in Int.MIN_VALUE..Int.MAX_VALUE) "int" else "long"
            }
            else -> "string"
        }
    }

    // --- Thread dump helpers (from ThreadDumpTool) ---

    private data class ThreadFrameInfo(
        val className: String,
        val methodName: String,
        val sourceName: String?,
        val lineNumber: Int
    )

    private data class ThreadInfo(
        val name: String,
        val id: Long,
        val status: Int,
        val statusText: String,
        val isDaemon: Boolean,
        val isSuspended: Boolean,
        val frames: List<ThreadFrameInfo>?
    )

    private fun inferDaemon(thread: com.sun.jdi.ThreadReference): Boolean {
        return try {
            val method = thread.javaClass.getMethod("isDaemon")
            method.invoke(thread) as? Boolean ?: false
        } catch (_: Exception) {
            try {
                val groupName = thread.threadGroup()?.name() ?: ""
                groupName.contains("daemon", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun statusToString(status: Int): String = when (status) {
        THREAD_STATUS_RUNNING -> "RUNNING"
        THREAD_STATUS_SLEEPING -> "SLEEPING"
        THREAD_STATUS_MONITOR -> "BLOCKED"
        THREAD_STATUS_WAIT -> "WAITING"
        THREAD_STATUS_NOT_STARTED -> "NOT_STARTED"
        THREAD_STATUS_ZOMBIE -> "TERMINATED"
        else -> "UNKNOWN"
    }

    companion object {
        // GetStackFramesTool constants
        private const val DEFAULT_MAX_FRAMES = 20
        private const val MAX_FRAMES_CAP = 50

        // GetVariablesTool constants
        private const val DEFAULT_MAX_DEPTH = 2
        private const val MAX_DEPTH_CAP = 4
        private const val MAX_OUTPUT_CHARS = 3000

        // MemoryViewTool constants
        private const val MAX_INSTANCE_DETAILS = 50

        // ThreadDumpTool constants
        private const val THREAD_STATUS_UNKNOWN = -1
        private const val THREAD_STATUS_ZOMBIE = 0
        private const val THREAD_STATUS_RUNNING = 1
        private const val THREAD_STATUS_SLEEPING = 2
        private const val THREAD_STATUS_MONITOR = 3
        private const val THREAD_STATUS_WAIT = 4
        private const val THREAD_STATUS_NOT_STARTED = 5
    }
}
