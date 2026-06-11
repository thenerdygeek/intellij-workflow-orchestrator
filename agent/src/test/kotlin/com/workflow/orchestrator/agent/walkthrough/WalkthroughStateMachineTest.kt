package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WalkthroughStateMachineTest {

    private fun step(n: Int) = WalkthroughStep("f$n.kt", n, n + 1, null, "body $n")
    private fun startedMachine(count: Int = 2): WalkthroughStateMachine =
        WalkthroughStateMachine().apply { start("Tour", (1..count).map { step(it) }) }

    @Test
    fun `start is only legal from IDLE or ENDED`() {
        val m = startedMachine()
        assertEquals(WalkthroughStateMachine.Status.GENERATING, m.status)
        assertFalse(m.start("Again", listOf(step(9))))
        m.end(byUser = true)
        assertTrue(m.start("Again", listOf(step(9))))
    }

    @Test
    fun `next advances, parks pendingNext at queue end, and append auto-advances`() {
        val m = startedMachine(2)
        assertTrue(m.next() is WalkthroughStateMachine.NextOutcome.Advanced)
        assertEquals(WalkthroughStateMachine.NextOutcome.AwaitingMore, m.next())
        assertTrue(m.pendingNext)
        assertEquals(2, m.append(listOf(step(3)))) // auto-advanced to index 2
        assertFalse(m.pendingNext)
        assertEquals("f3.kt", m.currentStep()!!.file)
    }

    @Test
    fun `next at queue end after finish reports TourFinished`() {
        val m = startedMachine(1)
        m.finish()
        assertEquals(WalkthroughStateMachine.NextOutcome.TourFinished, m.next())
    }

    @Test
    fun `back steps backwards and clears pendingNext, stops at first step`() {
        val m = startedMachine(2)
        m.next() // cursor=1
        m.next() // pendingNext=true
        assertEquals("f1.kt", m.back()!!.file)
        assertFalse(m.pendingNext)
        assertNull(m.back()) // already at first step
    }

    @Test
    fun `counter shows plus while generating and drops it after finish`() {
        val m = startedMachine(2)
        assertEquals("Step 1 of 2+", m.counterText())
        m.finish()
        assertEquals("Step 1 of 2", m.counterText())
    }

    @Test
    fun `markGenerationEnded is idempotent, completes queue, and clears paused`() {
        val m = startedMachine()
        m.setGenerationPaused(true)
        m.markGenerationEnded()
        assertEquals(WalkthroughStateMachine.Status.COMPLETE, m.status)
        assertFalse(m.generationPaused)
        m.markGenerationEnded() // idempotent
        assertEquals(WalkthroughStateMachine.Status.COMPLETE, m.status)
    }

    @Test
    fun `updateStep replaces or appends body and reports whether it hit the current step`() {
        val m = startedMachine(3)
        m.next() // cursor = 1 (step 2)
        // append to a non-current step: stored, not current
        val r1 = m.updateStep(1, "extra for step 1", append = true)
        assertFalse(r1.isCurrent)
        assertEquals("body 1\n\nextra for step 1", m.stepAt(1)!!.bodyMarkdown)
        // replace the current step: isCurrent = true
        val r2 = m.updateStep(2, "rewritten step 2", append = false)
        assertTrue(r2.isCurrent)
        assertEquals("rewritten step 2", m.currentStep()!!.bodyMarkdown)
    }

    @Test
    fun `updateStep rejects out-of-range index and inactive tour`() {
        val m = startedMachine(2)
        assertFalse(m.updateStep(0, "x", append = true).ok)
        assertFalse(m.updateStep(99, "x", append = true).ok)
        m.end(byUser = true)
        assertFalse(m.updateStep(1, "x", append = true).ok)
    }

    @Test
    fun `paused flag only applies while generating`() {
        val m = startedMachine()
        m.finish()
        m.setGenerationPaused(true)
        assertFalse(m.generationPaused)
    }

    @Test
    fun `end records byUser and toolStatusLine reports position`() {
        val m = startedMachine(3)
        m.next()
        assertEquals(
            "Walkthrough \"Tour\": ACTIVE, still generating — you may append more steps. " +
                "3 step(s); user is on step 2 of 3.",
            m.toolStatusLine(),
        )
        m.end(byUser = true)
        assertTrue(m.endedByUser)
        assertFalse(m.isActive)
    }

    @Test
    fun `mutating operations throw on a fresh IDLE machine`() {
        val m = WalkthroughStateMachine()
        assertThrows<IllegalStateException> { m.next() }
        assertThrows<IllegalStateException> { m.append(listOf(step(1))) }
        assertThrows<IllegalStateException> { m.back() }
        assertThrows<IllegalStateException> { m.finish() }
    }

    @Test
    fun `mutating operations throw after the user ended the tour`() {
        val m = startedMachine()
        m.end(byUser = true)
        assertThrows<IllegalStateException> { m.next() }
        assertThrows<IllegalStateException> { m.append(listOf(step(1))) }
        assertThrows<IllegalStateException> { m.back() }
        assertThrows<IllegalStateException> { m.finish() }
    }
}
