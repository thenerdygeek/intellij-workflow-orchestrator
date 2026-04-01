package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.CondensationAction
import com.workflow.orchestrator.agent.context.events.MessageAction
import com.workflow.orchestrator.agent.context.events.View
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CondenserPipelineTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun viewWithEvents(count: Int): View {
        val events = (1..count).map { i ->
            MessageAction(content = "message-$i", id = i)
        }
        return View(events = events)
    }

    private fun contextOf(view: View, utilization: Double = 0.5): CondenserContext {
        return CondenserContext(
            view = view,
            tokenUtilization = utilization,
            effectiveBudget = 100_000,
            currentTokens = (100_000 * utilization).toInt()
        )
    }

    /** A condenser that filters events to only those with even IDs. */
    private class EvenFilterCondenser : Condenser {
        override fun condense(context: CondenserContext): CondenserResult {
            val filtered = context.view.events.filter { it.id % 2 == 0 }
            return CondenserView(View(events = filtered))
        }
    }

    /** A condenser that tracks whether it was called. */
    private class TrackingCondenser : Condenser {
        var called = false
        override fun condense(context: CondenserContext): CondenserResult {
            called = true
            return CondenserView(context.view)
        }
    }

    /** A condenser that always returns a Condensation. */
    private class AlwaysCondenseCondenser : Condenser {
        override fun condense(context: CondenserContext): CondenserResult {
            return Condensation(
                CondensationAction(
                    forgottenEventIds = listOf(1, 2, 3),
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = "Condensed by AlwaysCondenseCondenser",
                    summaryOffset = 0
                )
            )
        }
    }

    /** A RollingCondenser that triggers when utilization exceeds a threshold. */
    private class ThresholdCondenser(private val threshold: Double) : RollingCondenser() {
        override fun shouldCondense(context: CondenserContext): Boolean {
            return context.tokenUtilization > threshold
        }

        override fun getCondensation(context: CondenserContext): Condensation {
            return Condensation(
                CondensationAction(
                    forgottenEventIds = context.view.events.map { it.id },
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = "Threshold exceeded: ${context.tokenUtilization}",
                    summaryOffset = 0
                )
            )
        }
    }

    // -----------------------------------------------------------------------
    // NoOpCondenser tests
    // -----------------------------------------------------------------------

    @Test
    fun `NoOpCondenser returns view unchanged`() {
        val view = viewWithEvents(5)
        val context = contextOf(view)
        val result = NoOpCondenser().condense(context)

        assertTrue(result is CondenserView)
        val resultView = (result as CondenserView).view
        assertSame(view, resultView)
        assertEquals(5, resultView.size)
    }

    // -----------------------------------------------------------------------
    // CondenserPipeline tests
    // -----------------------------------------------------------------------

    @Test
    fun `pipeline passes view between stages - two NoOps pass through`() {
        val view = viewWithEvents(3)
        val context = contextOf(view)
        val pipeline = CondenserPipeline(listOf(NoOpCondenser(), NoOpCondenser()))

        val result = pipeline.condense(context)

        assertTrue(result is CondenserView)
        val resultView = (result as CondenserView).view
        assertEquals(3, resultView.size)
        assertEquals(view.events, resultView.events)
    }

    @Test
    fun `pipeline short-circuits on Condensation - second condenser never called`() {
        val view = viewWithEvents(3)
        val context = contextOf(view)
        val tracker = TrackingCondenser()
        val pipeline = CondenserPipeline(listOf(AlwaysCondenseCondenser(), tracker))

        val result = pipeline.condense(context)

        assertTrue(result is Condensation)
        val condensation = result as Condensation
        assertEquals(listOf(1, 2, 3), condensation.action.forgottenEventIds)
        assertFalse(tracker.called, "Second condenser should not have been called")
    }

    @Test
    fun `pipeline updates view between stages - first filters, second sees filtered count`() {
        val view = viewWithEvents(6) // IDs 1-6; even = 2,4,6
        val context = contextOf(view)

        var secondStageEventCount = -1
        val countingCondenser = object : Condenser {
            override fun condense(context: CondenserContext): CondenserResult {
                secondStageEventCount = context.view.events.size
                return CondenserView(context.view)
            }
        }

        val pipeline = CondenserPipeline(listOf(EvenFilterCondenser(), countingCondenser))
        val result = pipeline.condense(context)

        assertTrue(result is CondenserView)
        assertEquals(3, (result as CondenserView).view.size) // 3 even events
        assertEquals(3, secondStageEventCount, "Second condenser should see filtered view with 3 events")
    }

    @Test
    fun `empty pipeline returns original view`() {
        val view = viewWithEvents(4)
        val context = contextOf(view)
        val pipeline = CondenserPipeline(emptyList())

        val result = pipeline.condense(context)

        assertTrue(result is CondenserView)
        assertEquals(view.events, (result as CondenserView).view.events)
    }

    // -----------------------------------------------------------------------
    // RollingCondenser tests
    // -----------------------------------------------------------------------

    @Test
    fun `RollingCondenser returns view when shouldCondense is false`() {
        val view = viewWithEvents(3)
        val context = contextOf(view, utilization = 0.3) // below 0.8 threshold
        val condenser = ThresholdCondenser(threshold = 0.8)

        val result = condenser.condense(context)

        assertTrue(result is CondenserView)
        assertEquals(view.events, (result as CondenserView).view.events)
    }

    @Test
    fun `RollingCondenser returns condensation when shouldCondense is true`() {
        val view = viewWithEvents(3)
        val context = contextOf(view, utilization = 0.9) // above 0.8 threshold
        val condenser = ThresholdCondenser(threshold = 0.8)

        val result = condenser.condense(context)

        assertTrue(result is Condensation)
        val condensation = result as Condensation
        assertEquals(listOf(1, 2, 3), condensation.action.forgottenEventIds)
        assertTrue(condensation.action.summary!!.contains("0.9"))
    }

    // -----------------------------------------------------------------------
    // CondenserContext tests
    // -----------------------------------------------------------------------

    @Test
    fun `CondenserContext data class carries all fields correctly`() {
        val view = viewWithEvents(2)
        val context = CondenserContext(
            view = view,
            tokenUtilization = 0.75,
            effectiveBudget = 200_000,
            currentTokens = 150_000
        )

        assertSame(view, context.view)
        assertEquals(0.75, context.tokenUtilization)
        assertEquals(200_000, context.effectiveBudget)
        assertEquals(150_000, context.currentTokens)
    }

    @Test
    fun `CondenserContext copy preserves non-copied fields`() {
        val view1 = viewWithEvents(2)
        val view2 = viewWithEvents(5)
        val context = CondenserContext(
            view = view1,
            tokenUtilization = 0.6,
            effectiveBudget = 100_000,
            currentTokens = 60_000
        )

        val copied = context.copy(view = view2)

        assertSame(view2, copied.view)
        assertEquals(0.6, copied.tokenUtilization)
        assertEquals(100_000, copied.effectiveBudget)
        assertEquals(60_000, copied.currentTokens)
    }

    // -----------------------------------------------------------------------
    // Pipeline is itself a Condenser (composable)
    // -----------------------------------------------------------------------

    @Test
    fun `pipeline is composable - nested pipelines work`() {
        val view = viewWithEvents(6)
        val context = contextOf(view)

        val innerPipeline = CondenserPipeline(listOf(EvenFilterCondenser()))
        val outerPipeline = CondenserPipeline(listOf(innerPipeline, NoOpCondenser()))

        val result = outerPipeline.condense(context)

        assertTrue(result is CondenserView)
        assertEquals(3, (result as CondenserView).view.size)
    }
}
