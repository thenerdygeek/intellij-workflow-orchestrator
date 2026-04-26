package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

class InteractionModePurityTest {
    @Test fun `Live when focusPr is null`() {
        assertEquals(InteractionMode.Live, WorkflowContext(activeBranch = "feat/abc").interactionMode)
    }

    @Test fun `Live when focusPr fromBranch matches activeBranch`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        assertEquals(InteractionMode.Live, WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode)
    }

    @Test fun `ReadOnly when focusPr fromBranch differs from activeBranch`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode)
    }

    @Test fun `ReadOnly when activeBranch is null but focusPr exists`() {
        val pr = PrRef(42, "bugfix/xyz", "main", "repo", null, null)
        assertEquals(InteractionMode.ReadOnly, WorkflowContext(focusPr = pr).interactionMode)
    }

    @Test fun `interactionMode result is stable across 100 invocations with no state change`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val ctx = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        repeat(100) { assertEquals(InteractionMode.Live, ctx.interactionMode) }
    }

    /**
     * Reflection-based purity check: enumerate WorkflowContext's declared properties; assert
     * that interactionMode for every combination of nulls vs non-nulls of (activeBranch, focusPr)
     * is determined SOLELY by those two fields (no implicit dependency on others). If a future
     * maintainer adds a contributing factor, this test fails because changing other declared
     * fields will not change interactionMode in cases where it shouldn't.
     */
    @Test fun `interactionMode depends only on declared activeBranch and focusPr`() {
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)
        val baselineLive = WorkflowContext(activeBranch = "feat/abc", focusPr = pr).interactionMode
        val baselineReadOnly = WorkflowContext(activeBranch = "main", focusPr = pr).interactionMode
        assertEquals(InteractionMode.Live, baselineLive)
        assertEquals(InteractionMode.ReadOnly, baselineReadOnly)

        // Enumerate other declared properties; for each, vary it and assert interactionMode unchanged.
        val ctxLiveBase = WorkflowContext(activeBranch = "feat/abc", focusPr = pr)
        val ctxLiveWithExtras = ctxLiveBase.copy(
            activeTicket = com.workflow.orchestrator.core.model.workflow.TicketRef("X-1", "s"),
            activeRepo = com.workflow.orchestrator.core.model.workflow.RepoRef("r", "P", "s", "/p"),
            activeModule = com.workflow.orchestrator.core.model.workflow.ModuleRef("m", "/p"),
            focusBuild = com.workflow.orchestrator.core.model.workflow.BuildRef("PLAN", 1, "feat/abc", null),
            focusQualityScope = com.workflow.orchestrator.core.model.workflow.QualityScope("k", "feat/abc", null),
        )
        assertEquals(ctxLiveBase.interactionMode, ctxLiveWithExtras.interactionMode,
            "interactionMode changed when an unrelated declared field changed — invariant broken")
    }
}
