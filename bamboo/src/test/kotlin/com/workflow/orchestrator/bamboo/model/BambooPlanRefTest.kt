package com.workflow.orchestrator.bamboo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BambooPlanRefTest {
    @Test fun `branch plan exposes its own key as planKey`() {
        val ref = BambooPlanRef.BranchPlan(planKey = "PROJ-PLAN138", parentPlanKey = "PROJ-PLAN", branchShortName = "feature/x")
        assertEquals("PROJ-PLAN138", ref.planKey)
        assertEquals("PROJ-PLAN", ref.parentPlanKey)
    }

    @Test fun `master-tracked branch uses master key as planKey`() {
        val ref = BambooPlanRef.MasterTrackedBranch(planKey = "PROJ-PLAN", branchShortName = "develop")
        assertEquals("PROJ-PLAN", ref.planKey)
    }

    @Test fun `master ref exposes master key`() {
        assertEquals("PROJ-PLAN", BambooPlanRef.Master("PROJ-PLAN").planKey)
    }
}
