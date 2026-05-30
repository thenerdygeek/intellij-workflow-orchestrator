package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * BUG #3 source-pin: the real [AgentController.startDelegatedSession] / [AgentController.resumeDelegatedSession]
 * must CLAIM the atomic [com.workflow.orchestrator.agent.delegation.DelegationStartReservation] in their
 * busy gate (so concurrent inbound delegations don't both decide RUN_NOW), and the fire-and-forget runners
 * ([AgentController.runDelegatedNow] / `runResumedDelegatedNow`) must RELEASE it once `currentJob` is
 * assigned AND on every failure path (so a failed start can't wedge the gate).
 *
 * The concurrency contract itself is exercised headlessly by `DelegationStartReservationTest`; constructing
 * a live [AgentController] needs a real Application/EDT (it owns a JCEF panel), so these are source pins —
 * the same style as `SafeAutoWakeRouteTest`'s wiring pins and `StartDelegatedSessionTest`.
 */
class IncomingDelegationReservationWiringTest {

    private val source: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    }

    private fun bodyOf(signature: String, maxLen: Int = 3800): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "expected to find `$signature` in AgentController.kt")
        return source.substring(start, minOf(start + maxLen, source.length))
    }

    @Test
    fun `the controller owns a single DelegationStartReservation field`() {
        assertTrue(
            source.contains("private val startReservation =") &&
                source.contains("com.workflow.orchestrator.agent.delegation.DelegationStartReservation()"),
            "AgentController must hold ONE DelegationStartReservation that the start + resume gates share",
        )
    }

    @Test
    fun `startDelegatedSession claims the reservation, folding in the job-based busy verdict`() {
        val body = bodyOf("suspend fun startDelegatedSession(")
        val reserveIdx = body.indexOf("startReservation.tryReserve(")
        val busyIdx = body.indexOf("decideIncomingBusy(")
        assertTrue(reserveIdx >= 0, "startDelegatedSession must claim the reservation via tryReserve")
        assertTrue(busyIdx >= 0, "the claim must still consult decideIncomingBusy (do not regress Fix B)")
        // The busy verdict is passed INTO tryReserve(busy = ...), so decideIncomingBusy sits inside the
        // tryReserve call.
        assertTrue(
            busyIdx > reserveIdx,
            "decideIncomingBusy must be the `busy` argument of tryReserve (atomic check-and-claim), " +
                "not a separate pre-check that re-opens the race",
        )
    }

    @Test
    fun `the RUN_NOW decision is driven by the reservation outcome`() {
        val body = bodyOf("suspend fun startDelegatedSession(")
        assertTrue(
            body.contains("reservedRunNow") && body.contains("!reservedRunNow"),
            "the QUEUE_INCOMING vs RUN_NOW surface must be derived from whether the reservation was won",
        )
    }

    @Test
    fun `resumeDelegatedSession also claims the shared reservation`() {
        val body = bodyOf("suspend fun resumeDelegatedSession(")
        assertTrue(
            body.contains("startReservation.tryReserve(") && body.contains("decideIncomingBusy("),
            "the resume path shares the gate and the same currentJob-assigned-later window, so it must " +
                "claim the same reservation (R2/Step-3 resume protection)",
        )
        assertTrue(
            body.contains("DelegatedStartOutcome.DECLINED_TIMEOUT"),
            "a lost claim on the resume path declines gracefully (never hijacks)",
        )
    }

    @Test
    fun `runDelegatedNow releases on currentJob assignment AND on every exit path`() {
        val body = bodyOf("private fun runDelegatedNow(")
        // onJobCreated assigns currentJob THEN releases — assignment must come first so the gate is
        // never briefly open with no live job.
        val onJobCreatedIdx = body.indexOf("onJobCreated = { job ->")
        assertTrue(onJobCreatedIdx >= 0, "runDelegatedNow must wire onJobCreated")
        val assignIdx = body.indexOf("currentJob = job", onJobCreatedIdx)
        val releaseInCb = body.indexOf("startReservation.release()", assignIdx)
        assertTrue(assignIdx >= 0, "onJobCreated must assign currentJob")
        assertTrue(releaseInCb >= 0, "onJobCreated must release the reservation")
        assertTrue(assignIdx < releaseInCb, "currentJob must be assigned BEFORE the reservation is released")
        // A finally-block safety-net release covers throw / cancellation / onJobCreated-never-fired.
        val finallyIdx = body.indexOf("} finally {")
        assertTrue(finallyIdx >= 0, "runDelegatedNow must release in a finally so a failed start can't wedge the gate")
        assertTrue(
            body.indexOf("startReservation.release()", finallyIdx) >= 0,
            "the finally block must release the reservation",
        )
    }

    @Test
    fun `runResumedDelegatedNow releases on currentJob assignment AND on every exit path`() {
        val body = bodyOf("private fun runResumedDelegatedNow(")
        val onJobCreatedIdx = body.indexOf("onJobCreated = { job ->")
        assertTrue(onJobCreatedIdx >= 0, "runResumedDelegatedNow must wire onJobCreated")
        val assignIdx = body.indexOf("currentJob = job", onJobCreatedIdx)
        val releaseInCb = body.indexOf("startReservation.release()", assignIdx)
        assertTrue(assignIdx >= 0 && releaseInCb >= 0 && assignIdx < releaseInCb,
            "onJobCreated must assign currentJob then release the reservation (in that order)")
        val finallyIdx = body.indexOf("} finally {")
        assertTrue(finallyIdx >= 0 && body.indexOf("startReservation.release()", finallyIdx) >= 0,
            "runResumedDelegatedNow must release in a finally so a failed resume can't wedge the gate")
    }
}
