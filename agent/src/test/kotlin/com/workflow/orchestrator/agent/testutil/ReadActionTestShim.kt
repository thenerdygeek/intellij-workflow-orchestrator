package com.workflow.orchestrator.agent.testutil

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.application.smartReadAction
import io.mockk.coEvery
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
