package com.workflow.orchestrator.bamboo.listeners

import org.junit.jupiter.api.Test

/**
 * Pins the scope-lifecycle contract for [BuildFailureBridgeStartupActivity] (audit bamboo:F-17,
 * S5 fix): the activity launches a forever-collecting coroutine on a [kotlinx.coroutines.CoroutineScope]
 * it creates itself, so that scope MUST be cancelled when the project is disposed — otherwise the
 * background collection of `BuildFinished` events outlives the project.
 *
 * The scope is a local val inside the suspend `execute()` and is not observable through the public
 * API without a production-only-for-test refactor, so this is a source-text contract test (same
 * style as RunInvocationLeakTest / SubagentRunnerWiringTest). It fails if the `Disposer.register`
 * wiring or the `scope.cancel()` body is removed.
 */
class BuildFailureBridgeScopeDisposeTest {

    private val src: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/bamboo/listeners/BuildFailureBridgeStartupActivity.kt"
        ).readText()
    }

    @Test
    fun `execute registers a project-scoped Disposable`() {
        assert("Disposer.register(project" in src) {
            "BuildFailureBridgeStartupActivity.execute must register a Disposable on the project " +
                "so the background scope is tied to project lifetime (bamboo:F-17)."
        }
    }

    @Test
    fun `the registered Disposable cancels the coroutine scope`() {
        // Match `Disposer.register(project, Disposable { ... scope.cancel() ... })` across
        // whitespace/newlines — the body must cancel the scope.
        val pattern = Regex("""Disposer\.register\(\s*project\s*,[\s\S]*?scope\.cancel\(\)""")
        assert(pattern.containsMatchIn(src)) {
            "The project Disposable registered by execute() must call scope.cancel() so background " +
                "BuildFinished collection does not outlive the project (bamboo:F-17)."
        }
    }
}
