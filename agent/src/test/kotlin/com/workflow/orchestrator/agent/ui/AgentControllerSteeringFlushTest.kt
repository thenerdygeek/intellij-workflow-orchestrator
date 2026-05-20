package com.workflow.orchestrator.agent.ui

import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class AgentControllerSteeringFlushTest {

    /**
     * Bug 2 reproducer: when the steering queue drains between two text-only
     * LLM iterations, the JS-side `streamingText` must be finalized before the
     * promoted user message lands, otherwise the next iteration's tokens
     * concatenate onto the previous bubble.
     *
     * This test pins the call ORDER on the dashboard:
     *   flushStreamBuffer()  →  finalizeToolChain()  →  promoteQueuedSteeringMessages(ids)
     *
     * The test seam delegates to the same private helper the two production
     * `onSteeringDrained` lambdas call (see AgentController.handleSteeringDrained
     * + handleSteeringDrainedWithDashboard), so any drift in production code
     * picks up here too — no parallel re-implementation in the companion.
     */
    @Test
    fun `steering drain flushes stream and finalizes tool chain before promote`() {
        val dashboard = mockk<AgentDashboardPanel>(relaxed = true)
        val drainedIds = listOf("steer-1", "steer-2")

        AgentController.invokeOnSteeringDrainedForTest(dashboard, drainedIds)

        verifyOrder {
            dashboard.flushStreamBuffer()
            dashboard.finalizeToolChain()
            dashboard.promoteQueuedSteeringMessages(drainedIds)
        }
    }

    /**
     * Bug 2, second path: after the user approves (APPROVED / ALLOWED_FOR_SESSION),
     * the agent loop resumes streaming. The pre-approval bubble must be flushed
     * before the post-approval tokens land so they don't concat into the same
     * bubble.
     */
    @Test
    fun `approval resume flushes stream before continuing`() {
        val dashboard = mockk<AgentDashboardPanel>(relaxed = true)
        AgentController.invokeOnApprovalResumeForTest(dashboard)
        verify { dashboard.flushStreamBuffer() }
    }
}
