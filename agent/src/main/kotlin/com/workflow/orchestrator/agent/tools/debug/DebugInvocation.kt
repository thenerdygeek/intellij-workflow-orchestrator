package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-session disposal scope for IntelliJ XDebugger listeners and
 * per-session flows.
 *
 * Phase 5 / Task 4.3 of the debug-tools fix plan — see
 * `docs/plans/2026-04-17-phase5-debug-tools-fixes.md`. Ported one-to-one
 * from the Phase-3 [com.workflow.orchestrator.agent.tools.runtime.RunInvocation]
 * pattern. Reuses the same lifecycle, idempotency, and `Disposer`-child
 * structure, specialised for debug sessions.
 *
 * ## Why this class exists
 *
 * Pre-Phase-5, [AgentDebugController.registerSession] called
 * `session.addSessionListener(listener)` — the 1-arg form with NO
 * symmetric `removeSessionListener` path wired up. Every `start_session` /
 * `attach_to_process` call stacked another listener on the same
 * [XDebugSession], so a debug-heavy agent run leaked N listeners per
 * session. [XDebugSession] itself does expose the 2-arg form
 * `addSessionListener(listener, parentDisposable)` — it auto-removes via
 * `Disposer` — which is exactly what [attachListener] routes through,
 * against this invocation's child [disposable].
 *
 * ## Debug-specific surface vs [RunInvocation]
 *
 * Where [RunInvocation] exposes `descriptorRef` / `processHandlerRef`
 * (`AtomicReference` for the JUnit runner's captured `RunContentDescriptor`
 * and `ProcessHandler`), [DebugInvocation] exposes a first-class
 * [pauseFlow]. Debug sessions don't own descriptor/handler lifecycle —
 * those are managed by `ExecutionManager` from the original launch and
 * stay alive across debug cycles (the handler is the JVM being debugged,
 * not a one-shot runner process). So neither field has a sensible
 * equivalent here, and [dispose] therefore does not call
 * `destroyProcess()`.
 *
 * If a future tool needs to attach a `ProcessListener` to a debug
 * session's underlying handle, add `attachProcessListener` then — it's a
 * one-liner mirror of [RunInvocation.attachProcessListener] and not worth
 * implementing speculatively.
 *
 * ### [pauseFlow]
 *
 * `MutableSharedFlow<DebugPauseEvent>(replay = 1)` lets
 * `AgentDebugController.waitForPause` use `.first()` so a caller that
 * subscribes AFTER the pause has already fired still observes it — the
 * replay cache remembers the most recent emission. The flow is a plain
 * field on the invocation (not a map keyed by sessionId on the
 * controller, as it was pre-Phase-5) so it's GC'd alongside this
 * invocation when [dispose] runs and the invocation becomes unreachable.
 *
 * There is no explicit "unsubscribe" API on [MutableSharedFlow] — each
 * `.first()` call installs a one-shot collector that completes when the
 * value arrives. By the time [dispose] runs, any in-flight collectors on
 * the owning coroutine have either already consumed a value (so the
 * collector is gone) or are blocked on `suspendCancellableCoroutine` and
 * will be cancelled when the parent scope is cancelled. Either way there
 * is nothing for this class to tear down beyond resetting the replay
 * cache so a late-arriving subscriber doesn't observe stale pause events
 * from an already-torn-down session — see [dispose].
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val invocation = project.service<AgentService>().newDebugInvocation("debug-$counter")
 * try {
 *     invocation.attachListener(xDebugSession, listener)
 *     invocation.pauseFlow.emit(pauseEvent)
 *     // ... await pause / step / stop events ...
 * } finally {
 *     Disposer.dispose(invocation)
 * }
 * ```
 *
 * The `try { … } finally { Disposer.dispose(invocation) }` idiom
 * satisfies the dispose-on-all-paths contract — success, timeout,
 * exception, and coroutine cancellation all route through the same
 * cleanup. On session reset (new chat), the session Disposable cascades
 * through `SessionDisposableHolder.resetSession()` and every outstanding
 * invocation's [dispose] runs.
 *
 * ## Threading
 *
 * [dispose] performs synchronous work: disposing the child [disposable]
 * chain may invoke arbitrary callback code installed via [onDispose].
 * Callers SHOULD invoke `Disposer.dispose(invocation)` from an
 * IO-capable coroutine context so the dispatcher thread isn't blocked
 * while the teardown completes.
 *
 * No internal dispatcher switching — keeping the call synchronous lets
 * the caller control ordering w.r.t. other coroutine cleanup.
 *
 * ## Concurrency contract
 *
 * Single-threaded dispose contract. Callers must invoke
 * `Disposer.dispose(invocation)` from a single thread (typically the
 * coroutine's cancellation handler or `finally` block). The [dispose]
 * method is idempotent across multiple calls: the first caller performs
 * the cleanup, subsequent callers observe the [disposed] flag and return
 * immediately WITHOUT waiting for the first caller's cleanup to
 * complete. Concurrent `attachXxx` calls with dispose are tolerated (the
 * framework's own `Disposer` handling absorbs the no-op). Callbacks are
 * held in a `CopyOnWriteArrayList` so attach/onDispose calls from
 * multiple threads don't clobber each other.
 *
 * @param parent Parent Disposable that owns this invocation's scope.
 *               Typically the session Disposable from
 *               `SessionDisposableHolder` so that a cancelled session
 *               tears down any outstanding debug invocations.
 * @param name   Diagnostic name for the child Disposable (shows up in
 *               IntelliJ's Disposer debug tree). Prefixed with
 *               `agent-debug-`.
 */
internal class DebugInvocation(
    parent: Disposable,
    name: String,
) : Disposable {

    /**
     * Per-invocation child Disposable. Every cleanup registration (the
     * 2-arg `addSessionListener` form, message bus connections, any
     * direct `Disposer.register(disposable, …)` wiring) hangs off this
     * so a single `Disposer.dispose(invocation)` call releases
     * everything atomically.
     */
    internal val disposable: Disposable = Disposer.newDisposable(parent, "agent-debug-$name")

    /**
     * Per-session pause event flow. Replaces the ad-hoc
     * `ConcurrentHashMap<String, MutableSharedFlow<DebugPauseEvent>>`
     * that was held on `AgentDebugController` pre-Phase-5 — the flow
     * now lives inside the invocation so it is garbage-collected
     * alongside the session scope.
     *
     * `replay = 1` so a `waitForPause` caller that subscribes AFTER the
     * [XDebugSessionListener.sessionPaused] callback fired still
     * observes the pause event. [dispose] calls `resetReplayCache()` to
     * evict any buffered event and prevent stale-event leakage to a
     * late-arriving subscriber.
     */
    val pauseFlow: MutableSharedFlow<DebugPauseEvent> = MutableSharedFlow(replay = 1)

    private val disposed = AtomicBoolean(false)

    /**
     * LIFO stack of user-registered cleanup blocks. `CopyOnWriteArrayList`
     * lets attachXxx and onDispose calls run concurrently with each
     * other (write is rare — at most a handful per invocation); dispose
     * walks it under the [disposed] guard so double-dispose is a no-op.
     */
    private val onDisposeCallbacks: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    /**
     * Attach an [XDebugSessionListener] to [session] using the 2-arg
     * `addSessionListener(listener, parentDisposable)` overload so it is
     * auto-removed when this invocation's [disposable] is disposed — no
     * explicit `removeSessionListener` call is needed.
     *
     * ### Why defense-in-depth proxy
     *
     * [XDebugSession.addSessionListener] with a parent Disposable is
     * the documented clean-up path, but if the framework re-fires an
     * event after our [dispose] but before `Disposer` has actually
     * finished removing the listener from the session's internal list,
     * a stale `resume()` on a consumed continuation would be a bug. We
     * wrap [listener] in a proxy that flips to no-op on [dispose] (via
     * the [disposed] flag) so any post-dispose events are silently
     * dropped. The cost is tiny; the safety is real.
     *
     * ### Method coverage
     *
     * [XDebugSessionListener] declares 7 `default` methods on the
     * target platform (IntelliJ 2025.1.x — `platformVersion` in
     * `gradle.properties`): `sessionPaused`, `sessionResumed`,
     * `sessionStopped`, `stackFrameChanged`, `beforeSessionResume`,
     * `settingsChanged`, `breakpointsMuted`. All 7 are overridden in
     * the proxy below so the post-dispose gate covers every callback
     * surface.
     *
     * Later platform releases (2025.3+) add an experimental 1-arg
     * `stackFrameChanged(changedByUser: Boolean)` overload. When this
     * module's `platformVersion` is bumped, add the override here —
     * the proxy must list every override explicitly to preserve the
     * forwarding-to-`listener` behaviour (the default implementations
     * on the interface are empty and would silently drop events if we
     * forgot to override).
     */
    fun attachListener(session: XDebugSession, listener: XDebugSessionListener) {
        val proxy = object : XDebugSessionListener {
            override fun sessionPaused() {
                if (disposed.get()) return
                try {
                    listener.sessionPaused()
                } catch (e: Exception) {
                    // Narrowed from Throwable: JVM Errors (OOM, StackOverflow,
                    // VirtualMachineError) MUST propagate — swallowing them
                    // hides fatal conditions behind a log line.
                    thisLogger().warn("DebugInvocation listener sessionPaused threw", e)
                }
            }

            override fun sessionResumed() {
                if (disposed.get()) return
                try {
                    listener.sessionResumed()
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener sessionResumed threw", e)
                }
            }

            override fun sessionStopped() {
                if (disposed.get()) return
                try {
                    listener.sessionStopped()
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener sessionStopped threw", e)
                }
            }

            override fun stackFrameChanged() {
                if (disposed.get()) return
                try {
                    listener.stackFrameChanged()
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener stackFrameChanged threw", e)
                }
            }

            override fun beforeSessionResume() {
                if (disposed.get()) return
                try {
                    listener.beforeSessionResume()
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener beforeSessionResume threw", e)
                }
            }

            override fun settingsChanged() {
                if (disposed.get()) return
                try {
                    listener.settingsChanged()
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener settingsChanged threw", e)
                }
            }

            override fun breakpointsMuted(muted: Boolean) {
                if (disposed.get()) return
                try {
                    listener.breakpointsMuted(muted)
                } catch (e: Exception) {
                    thisLogger().warn("DebugInvocation listener breakpointsMuted threw", e)
                }
            }
        }
        session.addSessionListener(proxy, disposable)
    }

    /**
     * Register a [MessageBusConnection] that must be disconnected on
     * dispose.
     *
     * `MessageBusConnection` is itself `Disposable`, so we piggy-back on
     * the invocation's Disposable chain — no manual bookkeeping.
     */
    fun subscribeTopic(connection: MessageBusConnection) {
        Disposer.register(disposable, connection)
    }

    /**
     * Register a cleanup block to run on [dispose]. Blocks fire in LIFO
     * order (last registered, first run) so "unwind" semantics mirror
     * the natural construction order — the outermost resource is
     * released last, after the inner listener/subscription state has
     * already been torn down.
     *
     * Exceptions from individual blocks are caught and logged;
     * subsequent blocks still run. [dispose]'s post-conditions are
     * best-effort.
     */
    fun onDispose(block: () -> Unit) {
        onDisposeCallbacks.add(block)
    }

    /**
     * Releases all resources registered on this invocation:
     *  1. Resets the [pauseFlow] replay cache so any straggler
     *     subscriber doesn't observe a stale pause event after session
     *     tear-down.
     *  2. Runs user [onDispose] callbacks in LIFO order.
     *  3. Disposes the child [disposable], which auto-cleans:
     *     - every `XDebugSessionListener` attached via [attachListener]
     *       (2-arg `addSessionListener` form registered against
     *       [disposable]);
     *     - every `MessageBusConnection` registered via
     *       [subscribeTopic];
     *     - any other `Disposer.register(disposable, …)` chains the
     *       tool set up directly.
     *
     * Idempotent: the first caller performs cleanup; subsequent
     * callers return immediately without re-running the cleanup OR
     * waiting for the first caller to complete. Callers relying on
     * "dispose has completed on return" MUST NOT invoke this method
     * concurrently from multiple threads — see the class-level
     * "Concurrency contract" KDoc.
     *
     * Defense-in-depth: the [disposed] flag also silences
     * [attachListener]'s event proxy so late framework callbacks
     * (re-fired after `Disposer` unwinds) become no-ops.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            // Already disposed — no-op. Guards against the framework
            // calling dispose() twice (e.g. parent chain + explicit
            // Disposer.dispose(invocation) in the tool's finally block).
            return
        }

        // 1) Reset the replay cache so any straggler subscriber doesn't
        //    observe a stale pause event after session tear-down.
        //    Catching Exception (not Throwable) intentionally: JVM Errors
        //    (OOM, StackOverflow, VirtualMachineError) MUST propagate.
        try {
            pauseFlow.resetReplayCache()
        } catch (e: Exception) {
            thisLogger().warn("DebugInvocation pauseFlow resetReplayCache threw", e)
        }

        // 2) User-registered cleanup (LIFO).
        val callbacksSnapshot = onDisposeCallbacks.toList()
        for (block in callbacksSnapshot.asReversed()) {
            try {
                block()
            } catch (e: Exception) {
                thisLogger().warn("DebugInvocation onDispose callback threw", e)
            }
        }

        // 3) Dispose the child Disposable — auto-cleans 2-arg session
        //    listeners, message bus connections, and any direct
        //    Disposer.register(disposable, …) wiring from the tool.
        try {
            Disposer.dispose(disposable)
        } catch (e: Exception) {
            thisLogger().warn("DebugInvocation Disposer.dispose threw", e)
        }
    }
}
