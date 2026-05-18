package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
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
import java.util.concurrent.atomic.AtomicReference
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
            // Canonical paused check: isSuspended alone is insufficient because
            // the flag flips before the engine has populated currentStackFrame /
            // suspendContext. If any clause is false, fall through to the
            // pauseFlow — the session listener will emit a full event once state
            // is ready. Audit finding C5.
            if (session.isSuspended
                && session.currentStackFrame != null
                && session.suspendContext != null
            ) {
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
    suspend fun getStackFrames(session: XDebugSession, maxFrames: Int = 20): List<FrameInfo>? {
        val stack = session.currentStackFrame?.let {
            session.suspendContext?.activeExecutionStack
        } ?: return emptyList()

        // Partial-result fallback: even if the platform never invokes the last=true callback
        // (deep stacks, suspended JNI frames, or a debugger probe that drops mid-walk), we
        // still surface whatever frames were collected — better than a bare empty list that
        // hits the 120s tool-level timeout. The `partial` AtomicReference is read on the
        // timeout path (awaitCallback returns null).
        val partial = AtomicReference<List<FrameInfo>>(emptyList())
        val result = awaitCallback<List<FrameInfo>>(GET_STACK_FRAMES_TIMEOUT_MS) { stopped, resume, _ ->
            val frames = mutableListOf<FrameInfo>()
            var index = 0

            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frameList: List<XStackFrame>, last: Boolean) {
                    if (stopped.get()) return
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
                    partial.set(frames.toList())
                    if (last || index >= maxFrames) {
                        resume(frames)
                    }
                }

                override fun errorOccurred(errorMessage: String) {
                    if (stopped.get()) return
                    resume(frames)
                }

                override fun isObsolete(): Boolean = false
            })
        }
        return result ?: partial.get()
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

        // Same partial-result pattern as getStackFrames — return what we have on timeout
        // rather than empty, so a slow walk still gives the LLM something to act on.
        val partial = AtomicReference<List<XStackFrame>>(emptyList())
        val result = awaitCallback<List<XStackFrame>>(GET_STACK_FRAMES_TIMEOUT_MS) { stopped, resume, _ ->
            val frames = mutableListOf<XStackFrame>()
            stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frameList: List<XStackFrame>, last: Boolean) {
                    if (stopped.get()) return
                    for (f in frameList) {
                        if (frames.size >= maxFrames) break
                        frames += f
                    }
                    partial.set(frames.toList())
                    if (last || frames.size >= maxFrames) resume(frames.toList())
                }
                override fun errorOccurred(errorMessage: String) {
                    if (stopped.get()) return
                    resume(frames.toList())
                }
                override fun isObsolete(): Boolean = false
            })
        }
        return result ?: partial.get()
    }

    /**
     * Gets variables from a stack frame, recursively resolving children up to maxDepth.
     * Caps output at MAX_VARIABLE_CHARS total characters and MAX_CHILDREN_PER_LEVEL children per node.
     *
     * Cumulative time budget (feedback.md 2026-05-17 #1): per-value resolution is bounded by
     * [PRESENTATION_TIMEOUT_MS] (8s) but a frame with 15+ slow values can still walk past the
     * 120s tool-wrapper timeout. We additionally enforce [GET_VARIABLES_WALL_BUDGET_MS] (90s)
     * on the entire walk, leaving 30s for response assembly + the tool wrapper to never have
     * to fire its generic "timed out after 120s" error. When the budget trips, the walk
     * stops where it is and appends a sentinel so the LLM knows the list is partial.
     */
    suspend fun getVariables(frame: XStackFrame, maxDepth: Int = 2): List<VariableInfo> {
        val budget = WalkBudget(deadlineMs = System.currentTimeMillis() + GET_VARIABLES_WALL_BUDGET_MS)
        val result = computeChildren(frame, maxDepth, IntArray(1) { 0 }, budget)
        return if (budget.tripped) {
            result + VariableInfo(
                name = "<budget>",
                type = "truncated",
                value = "…wall-clock budget (${GET_VARIABLES_WALL_BUDGET_MS / 1000}s) hit before the full frame could be resolved. " +
                    "Use evaluate(expression=\"…\") on specific variables you care about, or pass a smaller max_depth.",
                children = emptyList(),
                truncated = true,
            )
        } else result
    }

    /** Mutable wall-clock budget shared across the recursive `computeChildren` walk. */
    private data class WalkBudget(val deadlineMs: Long, var tripped: Boolean = false) {
        fun expired(): Boolean = tripped || System.currentTimeMillis() >= deadlineMs
        fun trip() { tripped = true }
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
        charCounter: IntArray,
        budget: WalkBudget,
    ): List<VariableInfo> {
        if (charCounter[0] >= MAX_VARIABLE_CHARS) return emptyList()
        if (budget.expired()) { budget.trip(); return emptyList() }

        val children = awaitCallback<List<Pair<String, XValue>>>(5000L) { stopped, resume, _ ->
            val result = mutableListOf<XValue>()
            val names = mutableListOf<String>()

            node.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    if (stopped.get()) return
                    for (i in 0 until children.size()) {
                        if (result.size >= MAX_CHILDREN_PER_LEVEL) break
                        val childName = children.getName(i)
                        // Skip CGLIB/Spring-AOP proxy synthetic fields — they add noise
                        // (CGLIB$BOUND, CGLIB$THREAD_CALLBACKS, $$EnhancerBy...) without
                        // carrying application state.
                        if (childName.startsWith("CGLIB\$") || childName.startsWith("\$\$EnhancerBy") ||
                            childName.startsWith("\$\$FastClassBy")) continue
                        names.add(childName)
                        result.add(children.getValue(i))
                    }
                    if (last || result.size >= MAX_CHILDREN_PER_LEVEL) {
                        resume(names.zip(result))
                    }
                }

                override fun tooManyChildren(remaining: Int) {
                    if (stopped.get()) return
                    // C4: surface truncation as a visible sentinel so the LLM knows
                    // the list is partial (XCompositeNode caps at 100). Without this,
                    // a HashMap with 500 entries silently returns 100 and the LLM
                    // draws wrong conclusions about completeness.
                    names += "<truncated>"
                    result += TruncatedSentinelXValue(remaining)
                    resume(names.zip(result))
                }

                override fun tooManyChildren(remaining: Int, childrenAdder: Runnable) {
                    if (stopped.get()) return
                    names += "<truncated>"
                    result += TruncatedSentinelXValue(remaining)
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

        // mapNotNull so we can bail mid-walk when the wall-clock budget trips — the
        // accumulated VariableInfos so far are still returned by the caller, and
        // the WalkBudget.tripped flag signals to getVariables that a sentinel is needed.
        return children.mapNotNull { (name, value) ->
            if (budget.expired()) { budget.trip(); return@mapNotNull null }
            if (value is TruncatedSentinelXValue) {
                return@mapNotNull VariableInfo(
                    name = "<truncated>",
                    type = "truncated",
                    value = "…and ${value.remaining} more child${if (value.remaining == 1) "" else "ren"} (use variable_name to expand a specific one)",
                    children = emptyList(),
                    truncated = true,
                )
            }
            val presentation = resolvePresentation(value)
            charCounter[0] += name.length + presentation.first.length + presentation.second.length

            val childVars = if (depth > 0 && charCounter[0] < MAX_VARIABLE_CHARS && !budget.expired()) {
                computeChildren(value, depth - 1, charCounter, budget)
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
        // IntelliJ's XDebugger calls setPresentation in two stages for lazy values:
        //   1. Synchronously with the "Collecting data…" placeholder (XDebuggerUIConstants.COLLECTING_DATA_MESSAGE).
        //   2. Asynchronously with the real evaluated value once the JDI round-trip completes.
        // Pre-fix code resumed on the first call and returned the placeholder. We now:
        //   - Always capture the latest presentation in `latest`,
        //   - Only resume() when we see a non-placeholder value,
        //   - Fall back to the latest captured presentation if the timeout elapses.
        // Replaces an earlier retry-loop attempt (0/300/600ms back-off + re-invoke
        // computePresentation) which queued duplicate evaluations on the debugger thread.
        //
        // Race safety: if the platform fires setPresentation AFTER the timeout, `awaitCallback`'s
        // contract guarantees resume(...) becomes a no-op — it sets stopped.set(true) in
        // invokeOnCancellation, and the lambda guards every resume with `stopped.getAndSet(true)`.
        // `latest` may still be written by the late callback (harmless AtomicReference write),
        // but no double-resume can occur.
        val latest = AtomicReference<Pair<String, String>?>(null)
        val result = awaitCallback<Pair<String, String>>(PRESENTATION_TIMEOUT_MS) { stopped, resume, _ ->
            value.computePresentation(object : XValueNode {
                override fun setPresentation(
                    icon: Icon?,
                    type: String?,
                    value: String,
                    hasChildren: Boolean
                ) {
                    if (stopped.get()) return
                    val pair = Pair(type ?: "unknown", value)
                    latest.set(pair)
                    // Gate on BOTH the value AND type slots — feedback 2026-05-17 #2 showed
                    // IntelliJ occasionally surfaces the "Collecting data…" placeholder in the
                    // type field while the value side is still empty. Treating either as a
                    // placeholder keeps us in the wait loop until the real call arrives.
                    if (!isPlaceholderValue(value) && !isPlaceholderValue(type ?: "")) resume(pair)
                }

                override fun setPresentation(
                    icon: Icon?,
                    presentation: XValuePresentation,
                    hasChildren: Boolean
                ) {
                    if (stopped.get()) return
                    // XValuePresentation carries the rendered text inside renderValue(), not toString().
                    // JavaValuePresentation (and other rich presentations) always use this overload;
                    // calling toString() returns the object identity string (ClassName@hashcode).
                    val sb = StringBuilder()
                    // All 9 methods are abstract in IntelliJ Platform 2025.3+ (no default impls).
                    presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                        override fun renderValue(value: String) { sb.append(value) }
                        override fun renderStringValue(value: String) { sb.append('"').append(value).append('"') }
                        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                            val display = if (maxLength in 1 until value.length) value.take(maxLength) + "…" else value
                            sb.append('"').append(display).append('"')
                        }
                        override fun renderNumericValue(value: String) { sb.append(value) }
                        override fun renderKeywordValue(value: String) { sb.append(value) }
                        override fun renderValue(value: String, textAttributesKey: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(value) }
                        override fun renderComment(value: String) {}
                        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
                        override fun renderError(error: String) { sb.append("<error: ").append(error).append('>') }
                    })
                    val rendered = sb.toString()
                    val type = presentation.type ?: "unknown"
                    val pair = Pair(type, rendered)
                    latest.set(pair)
                    if (!isPlaceholderValue(rendered) && !isPlaceholderValue(type)) resume(pair)
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}

                override fun isObsolete(): Boolean = false
            }, XValuePlace.TREE)
        }
        // Resume succeeded → real value. Timed out → fall back to whatever placeholder we last saw
        // (better signal than "<timed out>" — the LLM can still see the type and the lazy-load note).
        if (result != null) return result
        // Timed out. If the only thing we ever captured was the "Collecting data…" placeholder,
        // returning it verbatim trains the LLM to think the value LITERALLY rendered as that
        // string — exactly the regression behavior feedback 2026-05-17 #2 reported. Emit an
        // explicit "not ready" signal instead, preserving the type if we saw a real one.
        val captured = latest.get()
        if (captured == null) return Pair("unknown", "<timed out>")
        val (capType, capValue) = captured
        val typeOut = if (isPlaceholderValue(capType)) "unknown" else capType
        val valueOut = if (isPlaceholderValue(capValue)) {
            "<value not ready — JDI evaluation didn't complete in ${PRESENTATION_TIMEOUT_MS / 1000}s; retry the evaluate / get_variables call>"
        } else capValue
        return Pair(typeOut, valueOut)
    }

    /**
     * True when the rendered value is one of IntelliJ's placeholder strings emitted before
     * the lazy JDI evaluation completes. We intentionally match both the ellipsis-char (…)
     * and the three-dots (...) forms since the constant flips between platform releases,
     * and treat blank values as placeholders too (some XValues set "" before resolving).
     */
    @VisibleForTesting
    internal fun isPlaceholderValue(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        return trimmed == "Collecting data…" ||
            trimmed == "Collecting data..." ||
            trimmed.startsWith("Collecting data")
    }

    /**
     * Evaluates an expression in the context of the given session's stack frame at [frameIndex].
     *
     * Audit finding C2: previously, any non-zero [frameIndex] silently fell back to
     * [XDebugSession.currentStackFrame] (frame 0), so "evaluate in caller frame" requests
     * always evaluated in the wrong scope. Fix: use [getRawStackFrames] to retrieve the
     * target [XStackFrame], call its [XStackFrame.evaluator], and pass
     * [XStackFrame.sourcePosition] for correct scope/import resolution.
     */
    suspend fun evaluate(session: XDebugSession, expression: String, frameIndex: Int = 0): EvaluationResult {
        val frame: XStackFrame? = if (frameIndex == 0) {
            session.currentStackFrame
        } else {
            val frames = getRawStackFrames(session, frameIndex + 1)
            if (frames.size <= frameIndex) {
                return EvaluationResult(
                    expression,
                    "Frame $frameIndex not available (stack has ${frames.size} frames; indices are 0..${frames.size - 1})",
                    "error",
                    isError = true,
                )
            }
            frames[frameIndex]
        }

        if (frame == null) {
            return EvaluationResult(expression, "No active stack frame", "error", isError = true)
        }

        val evaluator = frame.evaluator
            ?: return EvaluationResult(expression, "No evaluator available for frame at index $frameIndex", "error", isError = true)

        // Pass frame.sourcePosition so the evaluator sees the right imports and
        // local scope. Previously we passed null, which worked for simple names
        // but dropped scope for anything needing imports. Audit finding C2.
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
                frame.sourcePosition,
            )
        }

        if (evalResult == null) {
            return EvaluationResult(expression, "<timed out after 10s>", "error", isError = true)
        }

        val xValue = evalResult.getOrElse { error ->
            return EvaluationResult(expression, error.message ?: "Evaluation failed", "error", isError = true)
        }

        // Resolve the XValue's presentation to get displayable type + value strings.
        // Note: resolvePresentation has its own 8s timeout (PRESENTATION_TIMEOUT_MS),
        // sequential to the 10s JDI evaluation above — so a single call can take up
        // to ~18s wall clock even though no individual phase exceeds 10s. The tool
        // layer (DebugInspectTool.executeEvaluate) absorbs this with a sentinel-keyed
        // single retry when JDI class-loading eats into the presentation budget.
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
                // XDebugSession.stop() requires EDT. dispose() may run on either EDT
                // or a background pool depending on parent disposable; invokeAndWait
                // short-circuits when already on EDT, so it's safe from any thread.
                ApplicationManager.getApplication().invokeAndWait { entry.session.stop() }
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
        WriteAction.runAndWait<RuntimeException> {
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
        }
        agentBreakpoints.clear()
        agentGeneralBreakpoints.clear()
    }

    override fun dispose() {
        stopAllSessions()
        // Actually remove breakpoints from the IDE debugger
        try {
            val bpManager = XDebuggerManager.getInstance(project).breakpointManager
            WriteAction.runAndWait<RuntimeException> {
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
        const val GET_STACK_FRAMES_TIMEOUT_MS = 15_000L

        /**
         * Bounded wait for one XValue's presentation to resolve. Bumped from 5s to 8s
         * after the placeholder-skip fix: now that we ignore "Collecting data…", we need
         * enough room for the second async setPresentation call to arrive — typically
         * 50ms-2s but worst-case (heap-pressure debuggee, slow JDWP) up to ~6s observed.
         */
        const val PRESENTATION_TIMEOUT_MS = 8_000L

        /**
         * Cumulative wall-clock budget for a full [getVariables] walk. Chosen at 90s,
         * leaving 30s of safety margin under the 120s per-tool wrapper timeout in
         * AgentLoop so we never hit the generic "timed out after 120s" path —
         * we instead emit a structured "budget tripped" sentinel and return what was
         * collected. Feedback.md 2026-05-17 #1.
         */
        const val GET_VARIABLES_WALL_BUDGET_MS = 90_000L
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
    val children: List<VariableInfo> = emptyList(),
    val truncated: Boolean = false,
)

data class EvaluationResult(
    val expression: String,
    val result: String,
    val type: String,
    val isError: Boolean = false
)

/**
 * Sentinel XValue appended by [AgentDebugController.computeChildren] when the
 * platform fires [XCompositeNode.tooManyChildren]. Allows the formatter to
 * surface a visible "…and N more" marker so the LLM knows the variable list
 * is partial (C4 fix).
 */
private class TruncatedSentinelXValue(val remaining: Int) : XValue() {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(null, "truncated", "…and $remaining more", false)
    }
}
