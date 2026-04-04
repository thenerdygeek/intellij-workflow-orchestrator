package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.debugger.ui.HotSwapUIImpl
import com.intellij.debugger.ui.HotSwapStatusListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Debug inspection and advanced operations — evaluate, variables, stack frames,
 * thread dump, memory view, hotswap, force return, drop frame.
 *
 * 8 actions covering runtime inspection and advanced debugging.
 */
class DebugInspectTool(private val controller: AgentDebugController) : AgentTool {

    override val name = "debug_inspect"

    override val description = """
Debug inspection — evaluate expressions, inspect variables, and advanced operations.

Actions and their parameters:
- evaluate(expression, session_id?) → Evaluate Java/Kotlin expression in current context
- get_stack_frames(session_id?, thread_name?, max_frames?) → Get call stack
- get_variables(session_id?, variable_name?, max_depth?) → Inspect local variables
- thread_dump(session_id?, max_frames?, include_stacks?, include_daemon?) → Full thread dump
- memory_view(class_name, session_id?, max_instances?) → Count/inspect live instances
- hotswap(session_id?, compile_first?) → Hot-reload changed classes
- force_return(session_id?, return_value?, return_type?) → Force method to return immediately
- drop_frame(session_id?, frame_index?) → Rewind execution to frame start

Most actions require a suspended session. session_id defaults to active session.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "evaluate", "get_stack_frames", "get_variables", "thread_dump",
                    "memory_view", "hotswap", "force_return", "drop_frame"
                )
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
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
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified class name — for memory_view"
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
            "evaluate" -> executeEvaluate(params, project)
            "get_stack_frames" -> executeGetStackFrames(params, project)
            "get_variables" -> executeGetVariables(params, project)
            "thread_dump" -> executeThreadDump(params, project)
            "memory_view" -> executeMemoryView(params, project)
            "hotswap" -> executeHotSwap(params, project)
            "force_return" -> executeForceReturn(params, project)
            "drop_frame" -> executeDropFrame(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: evaluate, get_stack_frames, get_variables, thread_dump, memory_view, hotswap, force_return, drop_frame",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
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

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

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

    // --- Thread dump helpers ---

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
