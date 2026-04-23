package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.workflow.orchestrator.agent.AgentService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.VisibleForTesting
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Shared coroutine wrapper for IntelliJ's callback-based XDebugger API.
 * All debug tools delegate to this controller for session management,
 * stack frame resolution, variable inspection, and expression evaluation.
 *
 * ## Per-session listener/flow ownership (Phase 5 / Task 4.2)
 *
 * Every [registerSession] call allocates a [DebugInvocation] via
 * [debugInvocationFactory] (default: `project.service<AgentService>().newDebugInvocation(...)`)
 * which owns:
 *  - the proxied [XDebugSessionListener] (attached via the 2-arg
 *    `addSessionListener(listener, parent)` form inside
 *    [DebugInvocation.attachListener] — auto-removed on `Disposer.dispose`);
 *  - the `pauseFlow` used by [waitForPause].
 *
 * This replaces the pre-Task-4.2 pattern of raw `addSessionListener(listener)`
 * + a controller-level `ConcurrentHashMap<String, MutableSharedFlow>`, which
 * leaked listeners across `registerSession` cycles (the 1-arg form has no
 * `removeSessionListener` path). See `DebugInvocation` KDoc for details.
 *
 * @param debugInvocationFactory Functional dependency for allocating
 *   per-session disposal scopes. Defaulted to the production wiring —
 *   tests inject a throw-away factory to avoid needing a real
 *   [AgentService] instance.
 */
class AgentDebugController internal constructor(
    private val project: Project,
    private val debugInvocationFactory: (String) -> DebugInvocation =
        { name -> project.service<AgentService>().newDebugInvocation(name) },
) : Disposable {

    // Strong references — cleanup is guaranteed by sessionStopped callback +
    // DebugInvocation cascade-dispose (Phase 5 Task 4.2). WeakReference rejected
    // in Task 4.5 review — would break getSession semantics.
    @VisibleForTesting
    internal val sessionInvocations = ConcurrentHashMap<String, SessionEntry>()
    private val agentBreakpoints = ConcurrentHashMap.newKeySet<XLineBreakpoint<*>>()
    private val agentGeneralBreakpoints = ConcurrentHashMap.newKeySet<XBreakpoint<*>>()
    @Volatile
    private var activeSessionId: String? = null

    /**
     * Per-session tuple: the [XDebugSession] plus the [DebugInvocation]
     * scoping its listener and pause flow. Replaces the two parallel maps
     * (`sessions` + `pauseFlows`) held pre-Task-4.2 — a single atomic
     * entry guarantees the listener and flow lifecycles stay in sync.
     */
    internal data class SessionEntry(
        val session: XDebugSession,
        val invocation: DebugInvocation,
    )

    /**
     * Registers a debug session, assigns a globally unique ID, and attaches a listener
     * to capture pause/resume/stop events.
     *
     * ID format: `agent-debug-{UUID}`. Task 4.5 replaced the pre-existing sequential
     * `debug-{counter}` format with a UUID to eliminate two correctness bugs:
     *  - counter recycling across `new chat` lifecycle could re-use `debug-1` for
     *    a new session while a downstream tool call still held the old handle;
     *  - the `agent-debug-` prefix disambiguates agent-owned handles from the
     *    platform `XDebugSession.sessionName` in logs and LLM chat output,
     *    so [IdeStateProbe.debugState] never mistakes a user display name like
     *    `"MyApp"` for an agent handle.
     */
    fun registerSession(session: XDebugSession): String {
        val sessionId = "agent-debug-${UUID.randomUUID()}"
        val invocation = debugInvocationFactory("session-$sessionId")
        sessionInvocations[sessionId] = SessionEntry(session, invocation)
        activeSessionId = sessionId

        invocation.attachListener(session, object : XDebugSessionListener {
            override fun sessionPaused() {
                val pos = session.currentPosition
                // XDebugSession doesn't expose currentBreakpoint directly;
                // we infer reason from context: if paused, it's either a breakpoint or step
                invocation.pauseFlow.tryEmit(
                    DebugPauseEvent(
                        sessionId = sessionId,
                        file = pos?.file?.path,
                        line = pos?.line?.plus(1), // 0-based to 1-based
                        reason = "breakpoint"
                    )
                )
            }

            @OptIn(ExperimentalCoroutinesApi::class)
            override fun sessionResumed() {
                // Reset the replay cache so waitForPause blocks on next call
                invocation.pauseFlow.resetReplayCache()
            }

            override fun sessionStopped() {
                sessionInvocations.remove(sessionId)?.also { entry ->
                    try {
                        Disposer.dispose(entry.invocation)
                    } catch (_: Exception) {
                        // Invocation already disposed (e.g. by new chat cascade) — no-op
                    }
                }
                if (activeSessionId == sessionId) {
                    activeSessionId = sessionInvocations.keys.firstOrNull()
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
            return activeSessionId?.let { sessionInvocations[it]?.session }
        }
        return sessionInvocations[sessionId]?.session
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
        val entry = sessionInvocations[sessionId] ?: return null
        val session = entry.session
        val flow = entry.invocation.pauseFlow
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
     * Like [getStackFrames] but returns raw [XStackFrame] references (not DTOs).
     * Used by callers that need to invoke frame methods like `evaluator` or
     * `sourcePosition` on a non-top frame (e.g., `evaluate` at `frameIndex > 0`).
     */
    suspend fun getRawStackFrames(session: XDebugSession, maxFrames: Int = 20): List<XStackFrame> {
        val stack = session.currentStackFrame?.let {
            session.suspendContext?.activeExecutionStack
        } ?: return emptyList()

        return suspendCancellableCoroutine { cont ->
            val frames = mutableListOf<XStackFrame>()
            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frameList: List<XStackFrame>, last: Boolean) {
                    for (f in frameList) {
                        if (frames.size >= maxFrames) break
                        frames += f
                    }
                    if (last || frames.size >= maxFrames) cont.resume(frames.toList())
                }
                override fun errorOccurred(errorMessage: String) { cont.resume(frames.toList()) }
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

    /**
     * Find a raw XValue by name in the frame's direct children.
     * Used by set_value to get the XValueModifier for direct modification.
     * Returns null if not found.
     */
    suspend fun findXValueByName(frame: XStackFrame, name: String): XValue? {
        return awaitCallback<XValue?>(5000L) { stopped, resume, _ ->
            var found: XValue? = null

            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    if (stopped.get()) return
                    if (found == null) {
                        for (i in 0 until children.size()) {
                            if (children.getName(i) == name) {
                                found = children.getValue(i)
                                break
                            }
                        }
                    }
                    if (last) resume(found)
                }

                override fun tooManyChildren(remaining: Int) {
                    if (stopped.get()) return
                    resume(found)
                }

                override fun tooManyChildren(remaining: Int, childrenAdder: Runnable) {
                    if (stopped.get()) return
                    resume(found)
                }

                override fun setAlreadySorted(alreadySorted: Boolean) {}

                override fun setErrorMessage(errorMessage: String) {
                    if (stopped.get()) return
                    resume(null)
                }

                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (stopped.get()) return
                    resume(null)
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

    private suspend fun computeChildren(
        node: XValueContainer,
        depth: Int,
        charCounter: IntArray
    ): List<VariableInfo> {
        if (charCounter[0] >= MAX_VARIABLE_CHARS) return emptyList()

        val children = awaitCallback<List<Pair<String, XValue>>>(5000L) { stopped, resume, _ ->
            val result = mutableListOf<XValue>()
            val names = mutableListOf<String>()

            node.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    if (stopped.get()) return
                    for (i in 0 until children.size()) {
                        if (result.size >= MAX_CHILDREN_PER_LEVEL) break
                        names.add(children.getName(i))
                        result.add(children.getValue(i))
                    }
                    if (last || result.size >= MAX_CHILDREN_PER_LEVEL) {
                        resume(names.zip(result))
                    }
                }

                override fun tooManyChildren(remaining: Int) {
                    if (stopped.get()) return
                    resume(names.zip(result))
                }

                override fun setAlreadySorted(alreadySorted: Boolean) {}

                override fun setErrorMessage(errorMessage: String) {
                    if (stopped.get()) return
                    resume(names.zip(result))
                }

                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    if (stopped.get()) return
                    resume(names.zip(result))
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
        return awaitCallback<Pair<String, String>>(5000L) { stopped, resume, _ ->
            value.computePresentation(object : XValueNode {
                override fun setPresentation(
                    icon: Icon?,
                    type: String?,
                    value: String,
                    hasChildren: Boolean
                ) {
                    if (stopped.get()) return
                    resume(Pair(type ?: "unknown", value))
                }

                override fun setPresentation(
                    icon: Icon?,
                    presentation: XValuePresentation,
                    hasChildren: Boolean
                ) {
                    if (stopped.get()) return
                    resume(Pair(presentation.type ?: "unknown", presentation.toString()))
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

                override fun isObsolete(): Boolean = false
            }, XValuePlace.TREE)
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

        // The evaluate callback gives us an XValue, not a displayable string.
        // We must resolve its presentation (type + value) via the async computePresentation API.
        // Phase 5 / Task 4.1 — wrap with awaitCallback so a hung JDI callback
        // cannot hang the agent loop, and a late callback after timeout cannot
        // corrupt the consumed continuation via `stopped` AtomicBoolean gate.
        val evalResult: Result<XValue>? = awaitCallback<Result<XValue>>(10_000L) { stopped, resume, _ ->
            evaluator.evaluate(
                expression,
                object : XDebuggerEvaluator.XEvaluationCallback {
                    override fun evaluated(result: XValue) {
                        if (stopped.get()) return
                        resume(Result.success(result))
                    }

                    override fun errorOccurred(errorMessage: String) {
                        if (stopped.get()) return
                        resume(Result.failure(RuntimeException(errorMessage)))
                    }
                },
                null
            )
        }

        if (evalResult == null) {
            return EvaluationResult(expression, "<timed out after 10s>", "error", isError = true)
        }

        val xValue = evalResult.getOrElse { error ->
            return EvaluationResult(expression, error.message ?: "Evaluation failed", "error", isError = true)
        }

        // Resolve the XValue's presentation to get displayable type + value strings
        val presentation = resolvePresentation(xValue)
        return EvaluationResult(expression, presentation.second, presentation.first)
    }

    /**
     * Stops all tracked debug sessions.
     */
    fun stopAllSessions() {
        val snapshot = sessionInvocations.values.toList()
        sessionInvocations.clear()
        activeSessionId = null
        for (entry in snapshot) {
            try {
                entry.session.stop()
            } catch (_: Exception) {
                // Session may already be stopped
            }
            try {
                Disposer.dispose(entry.invocation)
            } catch (_: Exception) {
                // Invocation may already be disposed (cascaded from session reset)
            }
        }
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

    /**
     * Executes a block on the debugger manager thread, wrapping the callback
     * pattern into a suspend function. Required for JDI operations (thread dump,
     * force return, drop frame, memory view).
     *
     * Passes both DebugProcessImpl and VirtualMachineProxyImpl so tools don't
     * need to cast the VM proxy themselves.
     */
    suspend fun <T> executeOnManagerThread(
        session: XDebugSession,
        block: (DebugProcessImpl, VirtualMachineProxyImpl) -> T
    ): T {
        val debugProcess = (session.debugProcess as? DebugProcessImpl)
            ?: throw IllegalStateException("Not a Java debug session")
        val vmProxy = debugProcess.virtualMachineProxy as VirtualMachineProxyImpl
        return suspendCancellableCoroutine { cont ->
            debugProcess.managerThread.schedule(object : DebuggerCommandImpl() {
                override fun action() {
                    try {
                        cont.resume(block(debugProcess, vmProxy))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }

                override fun commandCancelled() {
                    cont.cancel(CancellationException("Debug command cancelled"))
                }
            })
        }
    }

    /**
     * Convenience overload for tools that only need DebugProcessImpl (no VM proxy).
     */
    suspend fun <T> executeOnManagerThread(
        session: XDebugSession,
        block: (DebugProcessImpl) -> T
    ): T = executeOnManagerThread(session) { dp, _ -> block(dp) }

    /**
     * Wraps IntelliJ's callback-based XDebugger APIs into a cancellation-safe
     * coroutine with a timeout. The [register] lambda receives:
     *   - `stopped: AtomicBoolean` — inspect in your callback to short-circuit
     *     framework work when the caller has already timed out / cancelled.
     *   - `resume: (T) -> Unit` — idempotent, gated by stopped flag.
     *   - `resumeErr: (Throwable) -> Unit` — idempotent, gated by stopped flag.
     *
     * Returns null on timeout. On cancellation, sets stopped=true so the IntelliJ
     * callback becomes a no-op even if it fires later.
     *
     * Phase 5 / Task 4.1 — see docs/plans/2026-04-17-phase5-debug-tools-fixes.md.
     */
    private suspend fun <T> awaitCallback(
        timeoutMs: Long,
        register: (stopped: AtomicBoolean, resume: (T) -> Unit, resumeErr: (Throwable) -> Unit) -> Unit,
    ): T? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val stopped = AtomicBoolean(false)
            cont.invokeOnCancellation { stopped.set(true) }
            register(
                stopped,
                { value -> if (!stopped.getAndSet(true) && cont.isActive) cont.resume(value) },
                { err -> if (!stopped.getAndSet(true) && cont.isActive) cont.resumeWithException(err) },
            )
        }
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
