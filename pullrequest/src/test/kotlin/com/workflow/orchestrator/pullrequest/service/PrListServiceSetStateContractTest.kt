package com.workflow.orchestrator.pullrequest.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text contract tests for PULLREQUEST-COV-6.
 *
 * [PrListService.setState] writes the `currentState` field (declared `@Volatile`)
 * and immediately launches a coroutine on Dispatchers.IO to call refresh(). The
 * `@Volatile` declaration prevents torn reads but provides only best-effort
 * ordering between a setState write and a concurrent refresh read.
 *
 * These tests pin the design contract:
 *   1. `currentState` must remain a `@Volatile var` — replacing it with a
 *      MutableStateFlow, AtomicReference, or bare `var` would change the
 *      semantics the rest of the service relies on.
 *   2. `setState` must dispatch the refresh on Dispatchers.IO (not inline on
 *      the calling thread) so the UI thread is never blocked.
 *   3. `setState` must write to `currentState` before launching the coroutine
 *      so the refresh sees the updated value.
 *
 * Why source-text: PrListService depends on IntelliJ platform services that
 * cannot be instantiated in a plain JUnit 5 environment. Threading invariants
 * on @Volatile fields are established patterns for source-text contract tests
 * in this module (see [PrListServiceEdtMutationTest]).
 */
class PrListServiceSetStateContractTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt"
        ).readText()
    }

    @Test
    fun `currentState is declared as @Volatile var`() {
        // The field must be @Volatile to prevent torn reads across threads
        assertTrue(
            src.contains("@Volatile") && src.contains("var currentState"),
            "currentState must be declared as @Volatile var in PrListService"
        )
    }

    @Test
    fun `setState writes currentState before launching the refresh coroutine`() {
        // Locate the setState function body and verify currentState = state appears
        // before the cs.launch call
        val setStateFnIdx = src.indexOf("fun setState(")
        assertTrue(setStateFnIdx >= 0, "setState function must exist in PrListService")

        // Find assignment and launch within the function body
        val assignIdx = src.indexOf("currentState = state", setStateFnIdx)
        val launchIdx = src.indexOf("cs.launch", setStateFnIdx)

        assertTrue(assignIdx > setStateFnIdx,
            "currentState = state assignment must appear inside setState function")
        assertTrue(launchIdx > setStateFnIdx,
            "cs.launch must appear inside setState function")
        assertTrue(assignIdx < launchIdx,
            "currentState = state assignment must come BEFORE cs.launch so refresh sees the updated value")
    }

    @Test
    fun `setState dispatches the refresh coroutine on Dispatchers IO`() {
        val setStateFnIdx = src.indexOf("fun setState(")
        assertTrue(setStateFnIdx >= 0, "setState function must exist in PrListService")

        // The launch inside setState must use Dispatchers.IO
        val launchIdx = src.indexOf("cs.launch", setStateFnIdx)
        assertTrue(launchIdx > setStateFnIdx, "cs.launch must exist in setState")

        val launchContext = src.substring(launchIdx, launchIdx + 60)
        assertTrue(
            launchContext.contains("Dispatchers.IO"),
            "setState must dispatch refresh on Dispatchers.IO to avoid blocking the UI thread; found: $launchContext"
        )
    }
}
