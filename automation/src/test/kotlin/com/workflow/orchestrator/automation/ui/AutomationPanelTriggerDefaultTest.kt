package com.workflow.orchestrator.automation.ui

import com.workflow.orchestrator.automation.service.AutomationSettingsService
import com.workflow.orchestrator.automation.service.TriggerDefaultAction
import com.workflow.orchestrator.automation.service.resolveTriggerDefaultAction
import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveTriggerDefaultAction] — the extracted trigger-default business logic.
 *
 * [com.workflow.orchestrator.automation.ui.AutomationPanel.onTriggerDefault] delegates to
 * [resolveTriggerDefaultAction], so these tests exercise the full decision tree without
 * requiring IntelliJ platform infrastructure (no Project, no services, no Swing).
 *
 * Scenarios:
 *  1. Saved default exists → [TriggerDefaultAction.EnqueueWith] (the saved set).
 *  2. No saved default → [TriggerDefaultAction.OpenCustomizeDialog].
 *  3. Plan-stage fetch fails → [TriggerDefaultAction.FetchError], NO trigger fires.
 *  4. Stale-stage filter empties the saved set → [TriggerDefaultAction.OpenCustomizeDialog]
 *     (treated as "no default"), no silent "run all" or "run first".
 *
 * See `docs/architecture/automation-stage-picker-c-faithful-plan.md` Phase H2 + H8
 * and the Blocker 2 fix description.
 */
class AutomationPanelTriggerDefaultTest {

    private lateinit var bambooService: BambooService
    private lateinit var automationSettings: AutomationSettingsService

    private fun buildResult(vararg stageNames: String): ToolResult<BuildResultData> {
        val stages = stageNames.map { name ->
            BuildStageData(name = name, state = "Successful", durationSeconds = 0)
        }
        return ToolResult.success(
            BuildResultData(
                planKey = "PROJ-AUTO",
                buildNumber = 1,
                state = "Successful",
                durationSeconds = 0,
                stages = stages
            ),
            summary = "Build #1 Successful"
        )
    }

    @BeforeEach
    fun setUp() {
        bambooService = mockk()
        automationSettings = AutomationSettingsService()
    }

    // ==================== Scenario 1: saved default exists → EnqueueWith ====================

    @Test
    fun `saved default stages present enqueues with that exact set`() = runTest {
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("Build", "Test", "Deploy")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertTrue(action is TriggerDefaultAction.EnqueueWith,
            "Expected EnqueueWith, got $action")
        assertEquals(
            setOf("Build", "Deploy"),
            (action as TriggerDefaultAction.EnqueueWith).stages,
            "Should enqueue with the saved default set"
        )
    }

    @Test
    fun `saved default with single stage enqueues with that stage`() = runTest {
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("Build")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertTrue(action is TriggerDefaultAction.EnqueueWith)
        assertEquals(setOf("Build"), (action as TriggerDefaultAction.EnqueueWith).stages)
    }

    // ==================== Scenario 2: no saved default → OpenCustomizeDialog ====================

    @Test
    fun `no saved default opens customize dialog without triggering`() = runTest {
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("Build", "Test")

        // No default configured — getSuiteDefaultStages returns null
        // (nothing saved for this suite)

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertEquals(
            TriggerDefaultAction.OpenCustomizeDialog,
            action,
            "No default → should open dialog, NOT enqueue or error"
        )
    }

    @Test
    fun `cleared default opens customize dialog`() = runTest {
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("Build", "Test")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build"))
        automationSettings.setSuiteDefaultStages("PROJ-AUTO", null) // cleared

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertEquals(TriggerDefaultAction.OpenCustomizeDialog, action)
    }

    // ==================== Scenario 3: fetch error → FetchError, NO trigger ====================

    @Test
    fun `plan stage fetch failure returns FetchError without triggering`() = runTest {
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns
            ToolResult.error("Cannot reach Bamboo: connection refused")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertTrue(action is TriggerDefaultAction.FetchError,
            "Fetch failure must return FetchError, NOT EnqueueWith or OpenCustomizeDialog. Got: $action")
        assertTrue(
            (action as TriggerDefaultAction.FetchError).errorMessage.contains("Bamboo"),
            "Error message should propagate: ${action.errorMessage}"
        )
    }

    @Test
    fun `fetch error never falls back to all-stages or first-stage`() = runTest {
        // Regression guard: no matter what, a fetch failure must not silently run anything.
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns
            ToolResult.error("403 Forbidden")

        // Even if a default is saved, if stage fetch fails we can't validate it → FetchError.
        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertFalse(action is TriggerDefaultAction.EnqueueWith,
            "Must NOT enqueue when stage fetch fails — faulty fallback is worse than no data")
        assertTrue(action is TriggerDefaultAction.FetchError)
    }

    // ==================== Scenario 4: stale filter empties saved set → OpenCustomizeDialog ====================

    @Test
    fun `stale stages all removed opens customize dialog without triggering`() = runTest {
        // Saved {X, Y} but current plan only has {A, B} — all stale → intersection is empty
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("A", "B")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("X", "Y"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertEquals(
            TriggerDefaultAction.OpenCustomizeDialog,
            action,
            "All saved stages stale → treat as no default, open dialog. Must NOT enqueue with stale stages."
        )
    }

    @Test
    fun `partial stale filter enqueues with only the still-valid stages`() = runTest {
        // Saved {Build, Deploy} but current plan has {Build, Test} — Deploy was renamed.
        // Should enqueue with only {Build} (the intersection).
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("Build", "Test")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("Build", "Deploy"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        assertTrue(action is TriggerDefaultAction.EnqueueWith,
            "Partial intersection → should EnqueueWith the valid subset")
        assertEquals(
            setOf("Build"),
            (action as TriggerDefaultAction.EnqueueWith).stages,
            "Should enqueue with only the stages still present in the plan"
        )
    }

    @Test
    fun `stale filter does not silently fall back to run all stages`() = runTest {
        // Critical invariant: stale → dialog, never "run all"
        coEvery { bambooService.getLatestBuild("PROJ-AUTO") } returns buildResult("A", "B", "C")

        automationSettings.setSuiteDefaultStages("PROJ-AUTO", setOf("X", "Y"))

        val action = resolveTriggerDefaultAction("PROJ-AUTO", bambooService, automationSettings)

        // Must NOT be EnqueueWith(null) or EnqueueWith(all stages)
        assertFalse(
            action is TriggerDefaultAction.EnqueueWith,
            "All-stale must never produce EnqueueWith — that would silently run the wrong stages"
        )
    }
}
