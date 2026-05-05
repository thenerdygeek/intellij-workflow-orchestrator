package com.workflow.orchestrator.agent.testutil

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.DumbService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic

/**
 * Stubs IntelliJ's suspending read-action builders so they invoke their lambdas inline on
 * the calling coroutine. Required because the real builders call
 * `ApplicationManager.getApplication().getService(ReadWriteActionSupport::class.java)`,
 * which NPEs in unit tests without an Application instance.
 *
 * Call this from `@BeforeEach` (or `@Before`). Pair with `unmockkAll()` in `@AfterEach`.
 *
 * Stubs `readAction { … }`, `readActionBlocking { … }`, and `smartReadAction(project) { … }`
 * by default — covers the universal cases. Tests that don't invoke one of these builders
 * incur no cost; the stub only intercepts when the builder is actually called.
 */
fun installReadActionInlineShim() {
    mockkStatic("com.intellij.openapi.application.CoroutinesKt")
    coEvery { readAction<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
    coEvery { readActionBlocking<Any?>(any()) } coAnswers { firstArg<() -> Any?>().invoke() }
    coEvery { smartReadAction<Any?>(any(), any()) } coAnswers { secondArg<() -> Any?>().invoke() }
}

/**
 * Stubs `DumbService.getInstance(project).isDumb` so tests using `mockk<Project>(relaxed = true)`
 * can transit `core/vfs/PostMutationRefresh.waitForSmartModeOrTimeout` (Bug 4 Layer C barrier
 * installed at the top of `JavaRuntimeExecTool`, `PythonRuntimeExecTool`, `CoverageTool`, and
 * `RuntimeExecTool.executeRunConfig`).
 *
 * Without this, `DumbService.Companion.getInstance` calls `project.getService(DumbService::class.java)`,
 * gets back the relaxed-mock default `Object()`, and throws
 * `ClassCastException: java.lang.Object cannot be cast to DumbService`.
 *
 * Stubs at the `project.getService(DumbService::class.java)` level rather than via
 * `mockkStatic(DumbService::class)`. The static-intercept route fails during mockk's
 * record phase: the real `Companion.getInstance` runs and dereferences `any()`'s
 * fresh Project proxy, which lacks an implementation of the abstract
 * `ComponentManager.getService` method. Stubbing on the test's actual `project` mock
 * matches the proven `every { project.getService(AgentService::class.java) } returns …`
 * pattern used elsewhere in this codebase.
 *
 * Returns the underlying `DumbService` mock so an individual test can flip
 * `every { it.isDumb } returns true` for the dumb-mode error case.
 *
 * Call from `@BeforeEach`. Pair with `unmockkAll()` in `@AfterEach`.
 */
fun installSmartModeShim(project: com.intellij.openapi.project.Project, isDumb: Boolean = false): DumbService {
    val dumbService = mockk<DumbService>(relaxed = true).also {
        every { it.isDumb } returns isDumb
    }
    every { project.getService(DumbService::class.java) } returns dumbService
    return dumbService
}
