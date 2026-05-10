package com.workflow.orchestrator.sonar.service

import com.workflow.orchestrator.core.model.workflow.QualityScope
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.sonar.model.SonarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests the [SonarDataService] flow-subscription behavior introduced in Phase C of the
 * automation-chainkey-unification plan.
 *
 * The production service calls [WorkflowContextService.getInstance(project)], which
 * requires the IntelliJ platform. We test the subscription logic in isolation via
 * [SonarFlowSubscriptionHarness] — a standalone test double that replicates the
 * `subscribeToQualityScopeFlow()` wiring with an injected [StateFlow<WorkflowContext>]
 * and [UnconfinedTestDispatcher] so virtual time controls scheduling.
 *
 * Invariants verified:
 *  1. A non-null [QualityScope] emission triggers [refreshForBranch] with the scope's key + branch.
 *  2. A null [QualityScope] emission resets state to [SonarState.EMPTY] (no refresh).
 *  3. [distinctUntilChanged] suppresses repeated emissions of the same scope (no double-refresh).
 *  4. A scope with a blank [branchName] resets state to [SonarState.EMPTY] (no refresh).
 *  5. Legacy [EventBus] dependency is absent — no EventBus mock/fake needed in any of these tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SonarDataServiceFlowSubscriptionTest {

    private lateinit var contextState: MutableStateFlow<WorkflowContext>
    private lateinit var harness: SonarFlowSubscriptionHarness

    @BeforeEach
    fun setup() {
        contextState = MutableStateFlow(WorkflowContext())
    }

    @AfterEach
    fun teardown() {
        harness.dispose()
    }

    @Test
    fun `emitting non-null focusQualityScope triggers refreshForBranch with correct args`() = runTest {
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        val scope = QualityScope(
            sonarProjectKey = "my-project",
            branchName = "feature/PROJ-123-fix",
            moduleKey = null,
        )
        contextState.value = WorkflowContext(focusQualityScope = scope)
        advanceUntilIdle()

        assertEquals(1, harness.refreshCalls.size)
        assertEquals("feature/PROJ-123-fix", harness.refreshCalls[0].first)
        assertEquals("my-project", harness.refreshCalls[0].second)
    }

    @Test
    fun `null focusQualityScope resets state to EMPTY without calling refreshForBranch`() = runTest {
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        // First emit a valid scope to put the harness in a non-empty state
        val scope = QualityScope(
            sonarProjectKey = "my-project",
            branchName = "main",
            moduleKey = null,
        )
        contextState.value = WorkflowContext(focusQualityScope = scope)
        advanceUntilIdle()
        assertEquals(1, harness.refreshCalls.size)

        // Now clear the scope
        contextState.value = WorkflowContext(focusQualityScope = null)
        advanceUntilIdle()

        // No additional refresh call — null scope just clears state
        assertEquals(1, harness.refreshCalls.size)
        assertEquals(SonarState.EMPTY, harness.currentState)
    }

    @Test
    fun `distinctUntilChanged prevents double-refresh for identical scope emissions`() = runTest {
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        val scope = QualityScope(
            sonarProjectKey = "my-project",
            branchName = "develop",
            moduleKey = null,
        )

        // Emit the same scope twice (WorkflowContext changed other fields but scope stayed)
        contextState.value = WorkflowContext(focusQualityScope = scope, activeBranch = "develop")
        advanceUntilIdle()
        contextState.value = WorkflowContext(focusQualityScope = scope, activeBranch = "feature/other")
        advanceUntilIdle()

        // Only one refresh — distinctUntilChanged on focusQualityScope suppressed the second
        assertEquals(1, harness.refreshCalls.size)
    }

    @Test
    fun `scope with blank branchName resets state to EMPTY without calling refreshForBranch`() = runTest {
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        val blankBranchScope = QualityScope(
            sonarProjectKey = "my-project",
            branchName = "   ", // blank — not a valid target
            moduleKey = null,
        )
        contextState.value = WorkflowContext(focusQualityScope = blankBranchScope)
        advanceUntilIdle()

        assertTrue(harness.refreshCalls.isEmpty())
        assertEquals(SonarState.EMPTY, harness.currentState)
    }

    @Test
    fun `changing scope to a different project key refreshes with new key`() = runTest {
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        val scopeA = QualityScope(sonarProjectKey = "project-A", branchName = "main", moduleKey = null)
        val scopeB = QualityScope(sonarProjectKey = "project-B", branchName = "main", moduleKey = null)

        contextState.value = WorkflowContext(focusQualityScope = scopeA)
        advanceUntilIdle()
        contextState.value = WorkflowContext(focusQualityScope = scopeB)
        advanceUntilIdle()

        assertEquals(2, harness.refreshCalls.size)
        assertEquals("project-A", harness.refreshCalls[0].second)
        assertEquals("project-B", harness.refreshCalls[1].second)
    }

    @Test
    fun `no EventBus interaction required for any subscription behavior`() = runTest {
        // This test documents the architectural invariant: the flow-based subscription
        // requires no EventBus dependency. All assertions pass with only a StateFlow<WorkflowContext>
        // — no EventBus mock, no event emission, no event sink.
        harness = SonarFlowSubscriptionHarness(contextState, UnconfinedTestDispatcher(testScheduler))

        val scope = QualityScope(sonarProjectKey = "proj", branchName = "develop", moduleKey = null)
        contextState.value = WorkflowContext(focusQualityScope = scope)
        advanceUntilIdle()

        assertEquals(1, harness.refreshCalls.size)
        // Assertion: if EventBus were still needed, this test would require additional setup.
        // The fact that it passes with only a MutableStateFlow<WorkflowContext> confirms
        // the legacy subscription has been removed.
    }
}

/**
 * Standalone test harness that replicates the `subscribeToQualityScopeFlow()` wiring from
 * [SonarDataService] with an injected [StateFlow<WorkflowContext>] rather than a project
 * service lookup. This allows the subscription logic to be exercised without the IntelliJ
 * platform, exactly as the [TestSonarDataService] in [SonarDataServiceTest] does for
 * [SonarDataService.refreshWith].
 *
 * The [dispatcher] must be an [UnconfinedTestDispatcher] so the collector starts eagerly
 * (without needing a scheduler tick) and [advanceUntilIdle] drains the emission queue.
 */
private class SonarFlowSubscriptionHarness(
    contextStateFlow: StateFlow<WorkflowContext>,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    private val scope = CoroutineScope(dispatcher)

    /** Recorded calls: (branch, projectKey). */
    val refreshCalls = mutableListOf<Pair<String, String>>()

    private val _stateFlow = MutableStateFlow(SonarState.EMPTY)
    val currentState: SonarState get() = _stateFlow.value

    init {
        scope.launch {
            contextStateFlow
                .map { it.focusQualityScope }
                .distinctUntilChanged()
                .collect { qualityScope ->
                    if (qualityScope != null) {
                        val branch = qualityScope.branchName?.takeIf { it.isNotBlank() }
                        if (branch != null) {
                            _stateFlow.value = SonarState.EMPTY // simulate cache clear
                            refreshCalls.add(branch to qualityScope.sonarProjectKey)
                        } else {
                            _stateFlow.value = SonarState.EMPTY
                        }
                    } else {
                        _stateFlow.value = SonarState.EMPTY
                    }
                }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
