package com.workflow.orchestrator.agent.delegation

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Atomic single-slot reservation that closes the check-then-act gap in
 * [com.workflow.orchestrator.agent.ui.AgentController.startDelegatedSession] /
 * [com.workflow.orchestrator.agent.ui.AgentController.resumeDelegatedSession] (BUG #3).
 *
 * **The window it closes.** The incoming-delegation busy decision ([decideIncomingBusy]) gates on the
 * LIVE agent loop (`currentJob?.isActive`), but `currentJob` is assigned LATER, on a separate (EDT)
 * coroutine inside the fire-and-forget `runDelegatedNow` (via `onJobCreated`). `DelegationServer`'s
 * accept loop launches each inbound handler WITHOUT joining, so two near-simultaneous inbound
 * delegations BOTH observe `currentJob` inactive and BOTH decide RUN_NOW. The second's
 * `resetForNewChat` → `cancelCurrentTask` then cancels the first's just-started loop and wipes its
 * session state, and IDE-A receives a CANCELED result for a delegation it believes is still running.
 *
 * **What it does.** It makes the busy CHECK and the slot CLAIM a single atomic step. Exactly one of N
 * concurrent inbound delegations wins the reservation ([tryReserve] returns true → RUN_NOW); every
 * other concurrent claimant is refused (→ QUEUE_INCOMING for an incoming delegation,
 * → DECLINED_TIMEOUT for a resume). The reservation is held across the gap "decided RUN_NOW →
 * `currentJob` assigned", and is [release]d the moment `currentJob` is actually wired up
 * (`onJobCreated`) AND on every failure / decline / exception path so a failed start can never wedge
 * the gate closed forever.
 *
 * **Why a CAS, not a Mutex.** The claim is a single non-suspending decision with no critical section
 * to protect across suspension points — a lock-free `AtomicBoolean.compareAndSet` is the minimal
 * correct primitive and avoids any risk of holding a coroutine `Mutex` across the EDT hop in
 * `runDelegatedNow`.
 *
 * **Relationship to [decideIncomingBusy] (Fix B) — do NOT regress it.** A genuinely running loop is
 * still busy: callers pass that result in as [tryReserve]'s `busy` argument, and a `busy=true` claim
 * is refused WITHOUT changing reservation state. A completed-but-loaded session is still NOT busy
 * (Fix B), so it can still claim the slot. The reservation only ADDS the previously-missing dimension:
 * "a delegation is starting but `currentJob` isn't assigned yet" now also counts as busy.
 *
 * Pure and headless — no Project/Application/EDT — so the concurrency contract is unit-testable
 * (`DelegationStartReservationTest`), mirroring the [decideIncomingBusy] / [DelegatedSessionSurface]
 * seam factoring.
 */
class DelegationStartReservation {

    private val reserved = AtomicBoolean(false)

    /**
     * Atomically decide whether THIS delegation may proceed to RUN_NOW.
     *
     * @param busy the job-based busy verdict ([decideIncomingBusy]) — `true` iff an agent loop is
     *   actively running right now. A busy gate refuses the claim and leaves reservation state
     *   untouched.
     * @return `true` iff the caller won the slot (no live job AND no outstanding reservation). The
     *   winner MUST eventually call [release] (when `currentJob` is assigned, or on any failure /
     *   decline / exception). `false` means the slot is taken — either a live loop or another
     *   in-flight start that hasn't wired `currentJob` yet — so the caller must take the
     *   QUEUE_INCOMING / DECLINED_TIMEOUT path.
     */
    fun tryReserve(busy: Boolean): Boolean {
        if (busy) return false
        return reserved.compareAndSet(false, true)
    }

    /**
     * Release the reservation so the gate re-opens for the next delegation. Idempotent — safe to call
     * on every code path (success-after-`currentJob`-assigned, decline, exception) without
     * double-release bookkeeping.
     */
    fun release() {
        reserved.set(false)
    }

    /** Whether a reservation is currently outstanding. */
    fun isReserved(): Boolean = reserved.get()
}
