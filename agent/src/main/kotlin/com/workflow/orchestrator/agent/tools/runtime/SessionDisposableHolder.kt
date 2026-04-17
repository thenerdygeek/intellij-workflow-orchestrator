package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer

/**
 * Session-scoped `Disposable` manager for `AgentService`.
 *
 * Phase 3 / Task 2.2 of the IDE-state-leak fix plan — see
 * `docs/plans/2026-04-17-phase3-ide-state-leak-fixes.md`. This helper owns a
 * child `Disposable` that represents "the current chat session". Every
 * [newRunInvocation] call hands out a [RunInvocation] whose parent is the
 * session Disposable, so when the user clicks "new chat" and we call
 * [resetSession], every outstanding `RunInvocation` is torn down
 * transitively — process handlers killed, listeners unwired,
 * `RunContentDescriptor` removed — before the next session starts.
 *
 * ## Why a holder class
 *
 * `AgentService` is a project service with a heavy `init` (memory system,
 * tool registration, hook loading, disk I/O) that can't be cleanly
 * instantiated from a plain unit test. Extracting the session-Disposable
 * lifecycle into this thin, pure class lets us cover the behavior with
 * straightforward JUnit + MockK tests using a real `Disposer.newDisposable`
 * root — mirroring the pattern used in [RunInvocationTest]. `AgentService`
 * just delegates.
 *
 * ## Lifecycle
 *
 * ```
 * root (AgentService)
 *   └── sessionDisposable (this)
 *         ├── agent-run-foo    (RunInvocation #1)
 *         ├── agent-run-bar    (RunInvocation #2)
 *         └── ...
 * ```
 *
 * `resetSession()` disposes the current `sessionDisposable` (cascading to
 * every outstanding `RunInvocation`) and swaps in a fresh one. The holder
 * itself is a `Disposable` child of `parent`, so when the enclosing
 * service/project is disposed the whole subtree is released automatically.
 *
 * ## Threading
 *
 * Callers SHOULD invoke [resetSession] from a single thread (typically the
 * same thread that starts the next session) so the field swap is
 * observable. [newRunInvocation] is safe to call concurrently from any
 * thread — it always reads the current `sessionDisposable` atomically via
 * the `@Volatile` field.
 *
 * @param parent The enclosing parent `Disposable` (typically the
 *               `AgentService` instance). When the parent is disposed, the
 *               holder's own `Disposable` chain is released, preventing
 *               leaks across service shutdown.
 * @param diagnosticName Prefix used when constructing child session
 *                       Disposables — shows up in IntelliJ's Disposer
 *                       debug tree as `agent-session-<counter>`. Defaults
 *                       to `agent-session` for the default use case.
 */
internal class SessionDisposableHolder(
    private val parent: Disposable,
    private val diagnosticName: String = "agent-session",
) : Disposable {

    /**
     * Monotonic counter so each session's diagnostic name is unique in the
     * Disposer debug tree — helps distinguish "the session that just got
     * torn down" from "the session that just started" when investigating
     * leaks across multiple new-chat transitions.
     */
    private var sessionCounter: Int = 0

    /**
     * The current session's parent `Disposable`. All `RunInvocation`
     * instances created via [newRunInvocation] hang off this. Swapped by
     * [resetSession].
     *
     * `@Volatile` so that a reset on one thread is observable from a
     * concurrent `newRunInvocation` on another. Callers racing reset with
     * newRunInvocation may see either the old or the new session — by
     * design, reset is a clean boundary, not a synchronization barrier.
     */
    @Volatile
    private var sessionDisposable: Disposable = newSession()

    /**
     * Construct a fresh session `Disposable` parented to [parent]. Its
     * name includes [sessionCounter] for disambiguation in Disposer debug
     * dumps.
     */
    private fun newSession(): Disposable {
        val name = "$diagnosticName-${sessionCounter++}"
        return Disposer.newDisposable(parent, name)
    }

    /**
     * Allocate a new per-run disposal scope under the current session.
     * Tasks 2.3/2.4/2.5 call this from `run_tests` / `run_with_coverage`
     * tool executions:
     *
     * ```kotlin
     * val invocation = holder.newRunInvocation("run-tests-$className")
     * try {
     *     invocation.attachListener(...)
     *     invocation.attachProcessListener(handler, listener)
     *     invocation.onDispose { removeRunContent(...) }
     *     // ... await test result ...
     * } finally {
     *     Disposer.dispose(invocation)
     * }
     * ```
     *
     * If the user clicks "new chat" while the run is in flight,
     * [resetSession] disposes the parent session, which cascades to this
     * `RunInvocation` — listeners detach, the process is destroyed, and
     * the RunContent descriptor is cleaned up.
     *
     * ### Why two registrations
     *
     * `RunInvocation` constructs its own internal `disposable` as a child
     * of [sessionDisposable] (used for 2-arg `addProcessListener` auto-
     * cleanup and `MessageBusConnection` teardown), but the
     * `RunInvocation` *instance itself* must also participate in the
     * Disposer tree so cascading a session reset actually invokes
     * `RunInvocation.dispose()` — which is what fires the user-registered
     * `onDispose { removeRunContent(...) }` hooks and kills the process
     * handler. So we `Disposer.register` the invocation itself as a child
     * of [sessionDisposable]. The internal `disposable`'s own parenting
     * is redundant for the cascade path but harmless — [RunInvocation.dispose]
     * is idempotent.
     */
    fun newRunInvocation(name: String): RunInvocation {
        val session = sessionDisposable
        val invocation = RunInvocation(session, name)
        // Register the invocation itself so a cascade dispose (e.g. resetSession)
        // actually invokes RunInvocation.dispose() — not merely its internal
        // `disposable` child. Disposer.dispose is idempotent so the fact that
        // the internal `disposable` is ALSO a child of session is safe.
        Disposer.register(session, invocation)
        return invocation
    }

    /**
     * Tear down the current session's `Disposable` (cascading to every
     * outstanding `RunInvocation`) and install a fresh one for the next
     * session.
     *
     * Called from `AgentService.resetForNewChat()` and implicitly via
     * [dispose] when the enclosing service shuts down.
     *
     * Safe to call multiple times. Each call produces a new session scope
     * with an incremented counter, so diagnostic names remain unique
     * across the service's lifetime.
     */
    fun resetSession() {
        val old = sessionDisposable
        sessionDisposable = newSession()
        Disposer.dispose(old)
    }

    /**
     * Dispose the current session scope. The enclosing
     * `Disposer.register(parent, this)` chain already releases the tree
     * when `parent` goes down; this override lets callers (or a direct
     * `Disposer.dispose(holder)`) force an earlier release without
     * tearing down `parent` itself.
     */
    override fun dispose() {
        Disposer.dispose(sessionDisposable)
    }
}
