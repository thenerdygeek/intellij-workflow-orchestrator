package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-launch disposal scope for IDE-run-and-test tools
 * (`JavaRuntimeExecTool.run_tests`, `CoverageTool.run_with_coverage`).
 *
 * Phase 3 / Task 2.1 of the IDE-state-leak fix plan — see
 * `docs/plans/2026-04-17-phase3-ide-state-leak-fixes.md`. Every call to
 * `run_tests` or `run_with_coverage` currently attaches three things that
 * are never detached:
 *
 *   1. `TestResultsViewer.EventsListener` — `TestResultsViewer` is
 *      `Disposable` with NO symmetric `removeEventsListener` API. Listener
 *      is only released when the viewer itself is disposed (which happens
 *      when the `RunContentDescriptor` is removed from `RunContentManager`).
 *      Defense-in-depth: wrap the listener in a proxy that gates on a
 *      `disposed` flag so post-dispose callbacks become no-ops even if the
 *      framework still holds a reference.
 *   2. `ProcessListener` — framework exposes a 2-arg
 *      `addProcessListener(listener, parentDisposable)` that auto-cleans
 *      via `Disposer`. [attachProcessListener] always uses that form so
 *      we never have to track removals ourselves.
 *   3. `RunContentDescriptor` — removed from `RunContentManager.removeRunContent`
 *      only when the tool registers an [onDispose] block for it. By design
 *      the tool file keeps the `removeRunContent` call literal (so source-text
 *      tests match) while the scheduling/cleanup mechanism lives here.
 *
 * Plus a process handler whose lifecycle tracks the run — `destroyProcess()`
 * is called on the handler in [dispose] if it hasn't already terminated.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val invocation = RunInvocation(sessionDisposable, "run-tests-com.example.FooTest")
 * try {
 *     // ... JUnit dispatch, descriptor capture, listeners ...
 *     invocation.descriptorRef.set(descriptor)
 *     invocation.processHandlerRef.set(descriptor.processHandler)
 *     invocation.attachListener(eventsListener, resultsViewer)
 *     invocation.attachProcessListener(handler, processListener)
 *     invocation.subscribeTopic(connection)
 *     invocation.onDispose {
 *         ExecutionManager.getInstance(project).contentManager
 *             .removeRunContent(executor, descriptor)
 *     }
 *     // ... await result ...
 * } finally {
 *     Disposer.dispose(invocation)
 * }
 * ```
 *
 * The `try { … } finally { Disposer.dispose(invocation) }` idiom satisfies
 * the dispose-on-all-paths contract — success, `processNotStarted`, timeout,
 * exception, and coroutine cancellation all route through the same cleanup.
 *
 * ## Threading
 *
 * [dispose] performs synchronous work: `ProcessHandler.destroyProcess()` can
 * block on Windows (hundreds of ms to seconds for stubborn processes), and
 * the child Disposer chain may invoke arbitrary callback code. Callers SHOULD
 * invoke `Disposer.dispose(invocation)` from an IO-capable coroutine context
 * (e.g. wrapping the outer try/finally in `withContext(Dispatchers.IO)` if the
 * calling scope isn't already on IO). Otherwise the dispatcher thread blocks
 * for the duration of the teardown.
 *
 * No internal dispatcher switching — keeping the call synchronous lets the
 * caller control ordering w.r.t. other coroutine cleanup. Tasks 2.3/2.5 will
 * wire this into tool code that's already on `Dispatchers.IO`.
 *
 * ## Concurrency contract
 *
 * Single-threaded dispose contract. Callers must invoke
 * `Disposer.dispose(invocation)` from a single thread (typically the
 * coroutine's cancellation handler or `finally` block). The [dispose] method
 * is idempotent across multiple calls: the first caller performs the cleanup,
 * subsequent callers observe the [disposed] flag and return immediately
 * WITHOUT waiting for the first caller's cleanup to complete. Concurrent
 * `attachXxx` calls with dispose are tolerated (the framework's own
 * `Disposer` handling absorbs the no-op). Callbacks are held in a
 * `CopyOnWriteArrayList` so attach/onDispose calls from multiple threads
 * don't clobber each other.
 *
 * @param parent Parent Disposable that owns this invocation's scope.
 *               Typically the AgentService or session Disposable so that
 *               a cancelled session tears down any outstanding runs.
 * @param name   Diagnostic name for the child Disposable (shows up in
 *               IntelliJ's Disposer debug tree). Prefixed with `agent-run-`.
 */
internal class RunInvocation(
    parent: Disposable,
    name: String,
) : Disposable {

    /**
     * Per-invocation child Disposable. Every cleanup registration (process
     * listeners via the 2-arg form, message bus connections, custom onDispose
     * blocks) hangs off this so a single `Disposer.dispose(invocation)` call
     * (which disposes this via [dispose] below) releases everything atomically.
     */
    internal val disposable: Disposable = Disposer.newDisposable(parent, "agent-run-$name")

    /** Populated when the JUnit/coverage runner produces a `RunContentDescriptor`. */
    val descriptorRef: AtomicReference<RunContentDescriptor?> = AtomicReference(null)

    /** Populated when the descriptor yields a process handler. */
    val processHandlerRef: AtomicReference<ProcessHandler?> = AtomicReference(null)

    private val disposed = AtomicBoolean(false)

    /**
     * LIFO stack of user-registered cleanup blocks. `CopyOnWriteArrayList`
     * lets attachXxx and onDispose calls run concurrently with each other
     * (write is rare — at most a handful per invocation); dispose walks it
     * under the [disposed] guard so double-dispose is a no-op.
     */
    private val onDisposeCallbacks: CopyOnWriteArrayList<() -> Unit> = CopyOnWriteArrayList()

    /**
     * Attach a [TestResultsViewer.EventsListener] to [viewer].
     *
     * ### Why defense-in-depth proxy
     *
     * `TestResultsViewer` is `Disposable` and exposes `addEventsListener`
     * but NO symmetric `removeEventsListener`. The documented release
     * mechanism is `Disposer.register(viewer, childDisposable)` so the
     * listener becomes unreachable when the viewer is disposed (which
     * happens via `RunContentManager.removeRunContent(descriptor)`).
     *
     * But: if the framework re-fires an event after our [dispose] but
     * before the descriptor is actually torn down, a stale `resume()` on
     * a consumed continuation would be a bug. We wrap [listener] in a
     * proxy that flips to no-op on [dispose] (via the [disposed] flag) so
     * post-dispose events are silently dropped. The cost is tiny; the
     * safety is real.
     */
    fun attachListener(listener: TestResultsViewer.EventsListener, viewer: TestResultsViewer) {
        val proxy = object : TestResultsViewer.EventsListener {
            override fun onTestingStarted(sender: TestResultsViewer) {
                if (disposed.get()) return
                try {
                    listener.onTestingStarted(sender)
                } catch (e: Exception) {
                    // Narrowed from Throwable: JVM Errors (OOM, StackOverflow,
                    // VirtualMachineError) MUST propagate — swallowing them
                    // hides fatal conditions behind a log line.
                    thisLogger().warn("RunInvocation listener onTestingStarted threw", e)
                }
            }

            override fun onTestingFinished(sender: TestResultsViewer) {
                if (disposed.get()) return
                try {
                    listener.onTestingFinished(sender)
                } catch (e: Exception) {
                    thisLogger().warn("RunInvocation listener onTestingFinished threw", e)
                }
            }

            override fun onTestNodeAdded(
                sender: TestResultsViewer,
                test: com.intellij.execution.testframework.sm.runner.SMTestProxy,
            ) {
                if (disposed.get()) return
                try {
                    listener.onTestNodeAdded(sender, test)
                } catch (e: Exception) {
                    thisLogger().warn("RunInvocation listener onTestNodeAdded threw", e)
                }
            }
        }
        viewer.addEventsListener(proxy)
    }

    /**
     * Register a [MessageBusConnection] that must be disconnected on dispose.
     *
     * `MessageBusConnection` is itself `Disposable`, so we piggy-back on the
     * invocation's Disposable chain — no manual bookkeeping.
     */
    fun subscribeTopic(connection: MessageBusConnection) {
        Disposer.register(disposable, connection)
    }

    /**
     * Attach a [ProcessListener] using the 2-arg
     * `addProcessListener(listener, parentDisposable)` form so it is
     * auto-cleaned when [disposable] disposes.
     *
     * Prefer this over the 1-arg form in tool code — it removes the need
     * to track the listener reference for a matching `removeProcessListener`
     * call and eliminates the "leak if handler outlives tool" failure mode.
     */
    fun attachProcessListener(handler: ProcessHandler, listener: ProcessListener) {
        handler.addProcessListener(listener, disposable)
    }

    /**
     * Register a cleanup block to run on [dispose]. Blocks fire in LIFO
     * order (last registered, first run) so "unwind" semantics mirror the
     * natural construction order — the outermost resource (e.g.
     * `RunContentDescriptor` registration) is released last, after the
     * inner listener/subscription state has already been torn down.
     *
     * Exceptions from individual blocks are caught and logged; subsequent
     * blocks still run. [dispose]'s post-conditions are best-effort.
     */
    fun onDispose(block: () -> Unit) {
        onDisposeCallbacks.add(block)
    }

    /**
     * Releases all resources registered on this invocation:
     *  1. Destroys the captured process handler if it hasn't terminated.
     *  2. Runs user [onDispose] callbacks in LIFO order
     *     (including any `removeRunContent` the tool registered).
     *  3. Disposes the child [disposable], which auto-cleans:
     *     - every `ProcessListener` attached via [attachProcessListener]
     *       (2-arg form registered against [disposable]);
     *     - every `MessageBusConnection` registered via [subscribeTopic];
     *     - any other `Disposer.register(disposable, …)` chains the tool
     *       set up directly.
     *
     * Idempotent: the first caller performs cleanup; subsequent callers
     * return immediately without re-running the cleanup OR waiting for the
     * first caller to complete. Callers relying on "dispose has completed
     * on return" MUST NOT invoke this method concurrently from multiple
     * threads — see the class-level "Concurrency contract" KDoc.
     *
     * Defense-in-depth: the [disposed] flag also silences
     * [attachListener]'s event proxy so late framework callbacks
     * (re-fired after descriptor teardown) become no-ops.
     */
    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            // Already disposed — no-op. Guards against the framework
            // calling dispose() twice (e.g. parent chain + explicit
            // Disposer.dispose(invocation) in the tool's finally block).
            return
        }

        // 1) Process handler cleanup first — kills the child process so
        //    downstream listener removal doesn't race a still-emitting pipe.
        //    Catching Exception (not Throwable) intentionally: JVM Errors
        //    (OOM, StackOverflow, VirtualMachineError) MUST propagate.
        processHandlerRef.get()?.let { handler ->
            try {
                if (!handler.isProcessTerminated) {
                    handler.destroyProcess()
                }
            } catch (e: Exception) {
                thisLogger().warn("RunInvocation process destroy threw", e)
            }
        }

        // 2) User-registered cleanup (LIFO).
        //    Includes RunContentManager.removeRunContent lambdas the tool
        //    installed via onDispose { … } — per design, the literal
        //    `removeRunContent` call lives in the tool file.
        val callbacksSnapshot = onDisposeCallbacks.toList()
        for (block in callbacksSnapshot.asReversed()) {
            try {
                block()
            } catch (e: Exception) {
                thisLogger().warn("RunInvocation onDispose callback threw", e)
            }
        }

        // 3) Dispose the child Disposable — auto-cleans 2-arg process
        //    listeners, message bus connections, and any direct
        //    Disposer.register(disposable, …) wiring from the tool.
        try {
            Disposer.dispose(disposable)
        } catch (e: Exception) {
            thisLogger().warn("RunInvocation Disposer.dispose threw", e)
        }
    }
}
