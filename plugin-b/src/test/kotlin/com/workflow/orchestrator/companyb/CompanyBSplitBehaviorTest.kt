package com.workflow.orchestrator.companyb

import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.contribution.ToolContributionRunner
import com.workflow.orchestrator.agent.tools.contribution.ToolRegistrationContext
import com.workflow.orchestrator.core.config.DefaultWorkflowConfig
import com.workflow.orchestrator.core.config.WorkflowConfig
import com.workflow.orchestrator.core.settings.ConnectionSettings
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioral pins on plugin B's ACTUAL split impls — replaces the manual two-plugin runIde smoke
 * for the things runIde could only show by eye:
 *  - CompanyBWorkflowConfig sits at order=0 and beats DefaultWorkflowConfig via the same selection
 *    rule WorkflowConfig.resolve() uses;
 *  - CompanyBToolContributor actually registers its `companyb_noop` tool through the real
 *    ToolRegistrationContext / ToolContributionRunner path the agent EP delegates to.
 * Exercises the genuine production classes (no test doubles for the impls under test), so a break
 * in either fails here instead of at runIde.
 */
class CompanyBSplitBehaviorTest {

    @Test
    fun `CompanyBWorkflowConfig sits at order 0 below the base default`() {
        assertEquals(0, CompanyBWorkflowConfig().order)
        assertTrue(CompanyBWorkflowConfig().order < Int.MAX_VALUE,
            "B's override must be a lower order than DefaultWorkflowConfig (Int.MAX_VALUE) to win")
    }

    @Test
    fun `CompanyBWorkflowConfig wins the lowest-order selection rule over the base default`() {
        // WorkflowConfig.lowestOrderOf is internal to :core, so from plugin B we exercise the same
        // public selection rule it implements (minByOrNull { it.order }) over both providers — proving
        // CompanyBWorkflowConfig wins regardless of list position.
        val default = DefaultWorkflowConfig { ConnectionSettings.State() }
        val companyB = CompanyBWorkflowConfig()
        val providers = listOf<WorkflowConfig>(default, companyB)
        val winner = providers.minByOrNull { it.order }
        assertSame(companyB, winner, "the order=0 CompanyBWorkflowConfig must win over the base default")
        // And position-independent: reversed list selects the same winner.
        assertSame(companyB, providers.reversed().minByOrNull { it.order })
    }

    @Test
    fun `CompanyBToolContributor registers companyb_noop via the registration context`() {
        val registry = ToolRegistry()
        CompanyBToolContributor().registerTools(ToolRegistrationContext(mockk(relaxed = true), registry))
        assertTrue(registry.has("companyb_noop"),
            "CompanyBToolContributor must register its companyb_noop tool into the registry")
    }

    @Test
    fun `ToolContributionRunner runs CompanyBToolContributor cleanly`() {
        val registry = ToolRegistry()
        val diag = ToolContributionRunner.run(
            listOf(CompanyBToolContributor()),
            ToolRegistrationContext(mockk(relaxed = true), registry),
            registry,
        )
        assertEquals(1, diag.contributorCount)
        assertTrue("companyb_noop" in diag.addedToolNames,
            "the contributed tool must surface in the diagnostic — addedToolNames was ${diag.addedToolNames}")
        assertTrue(diag.failures.isEmpty(), "a well-behaved contributor must produce no failures")
    }
}
