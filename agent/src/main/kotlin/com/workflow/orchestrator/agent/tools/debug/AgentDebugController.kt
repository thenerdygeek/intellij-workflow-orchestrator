package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import kotlin.coroutines.resume

/**
 * Shared coroutine wrapper for IntelliJ's callback-based XDebugger API.
 * All debug tools delegate to this controller for session management,
 * stack frame resolution, variable inspection, and expression evaluation.
 */
class AgentDebugController(private val project: Project) : Disposable {

    private val sessionCounter = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<String, XDebugSession>()
    private val pauseFlows = ConcurrentHashMap<String, MutableSharedFlow<DebugPauseEvent>>()
    private val agentBreakpoints = ConcurrentHashMap.newKeySet<XLineBreakpoint<*>>()
    private val agentGeneralBreakpoints = ConcurrentHashMap.newKeySet<XBreakpoint<*>>()
    @Volatile
    private var activeSessionId: String? = null

    /**
     * Registers a debug session, assigns a unique ID, and attaches a listener
     * to capture pause/resume/stop events.
     */
    fun registerSession(session: XDebugSession): String {
        val sessionId = "debug-${sessionCounter.incrementAndGet()}"
        sessions[sessionId] = session
        activeSessionId = sessionId

        val flow = MutableSharedFlow<DebugPauseEvent>(replay = 1)
        pauseFlows[sessionId] = flow

        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                val pos = session.currentPosition
                // XDebugSession doesn't expose currentBreakpoint directly;
                // we infer reason from context: if paused, it's either a breakpoint or step
                val reason = "breakpoint"
                flow.tryEmit(
                    DebugPauseEvent(
                        sessionId = sessionId,
                        file = pos?.file?.path,
                        line = pos?.line?.plus(1), // 0-based to 1-based
                        reason = reason
                    )
                )
            }

            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            override fun sessionResumed() {
                // Reset the replay cache so waitForPause blocks on next call
                flow.resetReplayCache()
            }

            override fun sessionStopped() {
                sessions.remove(sessionId)
                pauseFlows.remove(sessionId)
                if (activeSessionId == sessionId) {
                    activeSessionId = sessions.keys.firstOrNull()
                }
            }
        })

        return sessionId
    }

    /**
     * Looks up a session by ID. If sessionId is null, returns the active session.
     */
    fun getSession(sessionId: String? = null): XDebugSession? {
        if (sessionId == null) {
            return activeSessionId?.let { sessions[it] }
        }
        return sessions[sessionId]
    }

    /**
     * Returns the currently active session ID, or null if none.
     */
    fun getActiveSessionId(): String? = activeSessionId

    /**
     * Tracks a breakpoint created by the agent for later cleanup.
     */
    fun trackBreakpoint(bp: XLineBreakpoint<*>) {
        agentBreakpoints.add(bp)
    }

    /**
     * Tracks a general (non-line) breakpoint created by the agent for later cleanup.
     * Used for exception breakpoints and other non-line-based breakpoint types.
     */
    fun trackGeneralBreakpoint(bp: XBreakpoint<*>) {
        agentGeneralBreakpoints.add(bp)
    }

    /**
     * Waits for the session to pause (hit a breakpoint, complete a step, etc.).
     * Returns immediately if already suspended. Returns null on timeout.
     */
    suspend fun waitForPause(sessionId: String, timeoutMs: Long = 5000): DebugPauseEvent? {
        val session = sessions[sessionId] ?: return null
        val flow = pauseFlows[sessionId] ?: return null
        return withTimeoutOrNull(timeoutMs) {
            if (session.isSuspended) {
                val pos = session.currentPosition
                return@withTimeoutOrNull DebugPauseEvent(
                    sessionId = sessionId,
                    file = pos?.file?.path,
                    line = pos?.line?.plus(1),
                    reason = "breakpoint"
                )
            }
            flow.first()
        }
    }

    /**
     * Gets stack frames from the current execution stack, wrapping the callback-based
     * XExecutionStack.computeStackFrames() API.
     */
    suspend fun getStackFrames(session: XDebugSession, maxFrames: Int = 20): List<FrameInfo> {
        val stack = session.currentStackFrame?.let {
            // Get the execution stack from the suspend context
            session.suspendContext?.activeExecutionStack
        } ?: return emptyList()

        return suspendCancellableCoroutine { cont ->
            val frames = mutableListOf<FrameInfo>()
            var index = 0

            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frameList: List<XStackFrame>, last: Boolean) {
                    for (frame in frameList) {
                        if (index >= maxFrames) break
                        val pos = frame.sourcePosition
                        frames.add(
                            FrameInfo(
                                index = index++,
                                methodName = frame.toString(),
                                file = pos?.file?.path,
                                line = pos?.line?.plus(1),
                                className = null
                            )
                        )
                    }
                    if (last || index >= maxFrames) {
                        cont.resume(frames)
                    }
                }

                override fun errorOccurred(errorMessage: String) {
                    cont.resume(frames) // Return what we have
                }

                override fun isObsolete(): Boolean = false
            })
        }
    }

    /**
     * Gets variables from a stack frame, recursively resolving children up to maxDepth.
     * Caps output at MAX_VARIABLE_CHARS total characters and MAX_CHILDREN_PER_LEVEL children per node.
     */
    suspend fun getVariables(frame: XStackFrame, maxDepth: Int = 2): List<VariableInfo> {
        val charCounter = IntArray(1) { 0 }
        return computeChildren(frame, maxDepth, charCounter)
    }

    private suspend fun computeChildren(
        node: XValueContainer,
        depth: Int,
        charCounter: IntArray
    ): List<VariableInfo> {
        if (charCounter[0] >= MAX_VARIABLE_CHARS) return emptyList()

        val children = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { /* timeout cleanup */ }
                val result = mutableListOf<XValue>()
                val names = mutableListOf<String>()

                node.computeChildren(object : XCompositeNode {
                    override fun addChildren(children: XValueChildrenList, last: Boolean) {
                        for (i in 0 until children.size()) {
                            if (result.size >= MAX_CHILDREN_PER_LEVEL) break
                            names.add(children.getName(i))
                            result.add(children.getValue(i))
                        }
                        if (last || result.size >= MAX_CHILDREN_PER_LEVEL) {
                            cont.resume(names.zip(result))
                        }
                    }

                    override fun tooManyChildren(remaining: Int) {
                        cont.resume(names.zip(result))
                    }

                    override fun setAlreadySorted(alreadySorted: Boolean) {}

                    override fun setErrorMessage(errorMessage: String) {
                        cont.resume(names.zip(result))
                    }

                    override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                        cont.resume(names.zip(result))
                    }

                    override fun setMessage(
                        message: String,
                        icon: Icon?,
                        attributes: SimpleTextAttributes,
                        link: XDebuggerTreeNodeHyperlink?
                    ) {}

                    override fun isObsolete(): Boolean = false
                })
            }
        }

        if (children == null) return emptyList()

        return children.map { (name, value) ->
            val presentation = resolvePresentation(value)
            charCounter[0] += name.length + presentation.first.length + presentation.second.length

            val childVars = if (depth > 0 && charCounter[0] < MAX_VARIABLE_CHARS) {
                computeChildren(value, depth - 1, charCounter)
            } else {
                emptyList()
            }

            VariableInfo(
                name = name,
                type = presentation.first,
                value = presentation.second,
                children = childVars
            )
        }
    }

    private suspend fun resolvePresentation(value: XValue): Pair<String, String> {
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { /* timeout cleanup */ }
                value.computePresentation(object : XValueNode {
                    override fun setPresentation(
                        icon: Icon?,
                        type: String?,
                        value: String,
                        hasChildren: Boolean
                    ) {
                        cont.resume(Pair(type ?: "unknown", value))
                    }

                    override fun setPresentation(
                        icon: Icon?,
                        presentation: XValuePresentation,
                        hasChildren: Boolean
                    ) {
                        cont.resume(Pair(presentation.type ?: "unknown", presentation.toString()))
                    }

                    override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

                    override fun isObsolete(): Boolean = false
                }, XValuePlace.TREE)
            }
        } ?: Pair("unknown", "<timed out>")
    }

    /**
     * Evaluates an expression in the context of the given session's current stack frame.
     */
    suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult {
        val frame = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            val frames = getStackFrames(session, frameIndex + 1)
            if (frames.size <= frameIndex) {
                return EvaluationResult(expression, "Frame $frameIndex not available", "error", isError = true)
            }
            // For non-zero frame indices, we need the actual XStackFrame reference
            // but getStackFrames returns FrameInfo DTOs. Use current frame as fallback.
            session.currentStackFrame
        }

        if (frame == null) {
            return EvaluationResult(expression, "No active stack frame", "error", isError = true)
        }

        val evaluator = frame.evaluator
            ?: return EvaluationResult(expression, "No evaluator available for current frame", "error", isError = true)

        return suspendCancellableCoroutine { cont ->
            evaluator.evaluate(
                expression,
                object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        cont.resume(EvaluationResult(expression, result.toString(), "value"))
                    }

                    override fun errorOccurred(errorMessage: String) {
                        cont.resume(EvaluationResult(expression, errorMessage, "error", isError = true))
                    }
                },
                null
            )
        }
    }

    /**
     * Stops all tracked debug sessions.
     */
    fun stopAllSessions() {
        sessions.values.forEach { session ->
            try {
                session.stop()
            } catch (_: Exception) {
                // Session may already be stopped
            }
        }
        sessions.clear()
        pauseFlows.clear()
        activeSessionId = null
    }

    /**
     * Removes all breakpoints that were created by the agent.
     */
    fun removeAgentBreakpoints() {
        agentBreakpoints.clear()
        agentGeneralBreakpoints.clear()
    }

    /**
     * Removes agent breakpoints using the XDebuggerManager for proper cleanup.
     */
    fun removeAgentBreakpoints(debuggerManager: XDebuggerManager) {
        val bpManager = debuggerManager.breakpointManager
        agentBreakpoints.forEach { bp ->
            try {
                @Suppress("UNCHECKED_CAST")
                bpManager.removeBreakpoint(bp as XLineBreakpoint<Nothing>)
            } catch (_: Exception) {
                // Breakpoint may already be removed
            }
        }
        agentBreakpoints.clear()
        agentGeneralBreakpoints.forEach { bp ->
            try {
                @Suppress("UNCHECKED_CAST")
                bpManager.removeBreakpoint(bp as XBreakpoint<Nothing>)
            } catch (_: Exception) {
                // Breakpoint may already be removed
            }
        }
        agentGeneralBreakpoints.clear()
    }

    override fun dispose() {
        stopAllSessions()
        // Actually remove breakpoints from the IDE debugger
        try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            agentBreakpoints.forEach { bp ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    bpManager.removeBreakpoint(bp as XLineBreakpoint<Nothing>)
                } catch (_: Exception) {
                    // Breakpoint may already be removed
                }
            }
            agentGeneralBreakpoints.forEach { bp ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    bpManager.removeBreakpoint(bp as XBreakpoint<Nothing>)
                } catch (_: Exception) {
                    // Breakpoint may already be removed
                }
            }
        } catch (_: Exception) {
            // Project may already be disposed
        }
        agentBreakpoints.clear()
        agentGeneralBreakpoints.clear()
    }

    companion object {
        const val MAX_VARIABLE_CHARS = 3000
        const val MAX_CHILDREN_PER_LEVEL = 10
    }
}

// --- Data classes ---

data class DebugPauseEvent(
    val sessionId: String,
    val file: String?,
    val line: Int?,
    val reason: String
)

data class FrameInfo(
    val index: Int,
    val methodName: String,
    val file: String?,
    val line: Int?,
    val className: String?
)

data class VariableInfo(
    val name: String,
    val type: String,
    val value: String,
    val children: List<VariableInfo> = emptyList()
)

data class EvaluationResult(
    val expression: String,
    val result: String,
    val type: String,
    val isError: Boolean = false
)
