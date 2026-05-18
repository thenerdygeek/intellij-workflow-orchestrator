package com.workflow.orchestrator.agent.tools.framework.build

import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Regression test for feedback #6 (v0.85.31 follow-up):
 * `build(action="project_modules")` was throwing
 * "Read access is allowed from inside read-action only" because
 * `executeProjectModules` accessed `ModuleManager.getInstance(project).modules`
 * and `ModuleRootManager.getInstance(module)` outside a read action.
 *
 * Source-text contract: the implementation must funnel IntelliJ model access
 * through `readAction { ... }`. Pinning this here so a future refactor that
 * inlines the snapshot helper or drops the wrapper trips the test.
 */
class BuildProjectModulesReadActionTest {

    @BeforeEach
    fun setUp() {
        installReadActionInlineShim()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `executeProjectModules source wraps module access in readAction`() {
        val source = sourceFile().readText()

        assertTrue(
            source.contains("import com.intellij.openapi.application.readAction"),
            "BuildProjectModulesAction.kt must import readAction"
        )
        assertTrue(
            Regex("""readAction\s*\{\s*collectProjectModulesSnapshot\(project\)\s*\}""").containsMatchIn(source),
            "executeProjectModules must call collectProjectModulesSnapshot(project) inside readAction { }"
        )
    }

    @Test
    fun `executeProjectModules is suspend so it can call readAction`() = runTest {
        // Compile-time check: if executeProjectModules is not suspend, this won't compile.
        // Runtime call would require a fully-stubbed Project; the source-text test above
        // covers the read-action contract.
        val fn: suspend (kotlinx.serialization.json.JsonObject, com.intellij.openapi.project.Project) -> Any =
            ::executeProjectModules
        assertTrue(fn.toString().isNotEmpty())
    }

    private fun sourceFile(): Path {
        // tests run from agent/ working directory under Gradle
        val candidates = listOf(
            Path.of("src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/BuildProjectModulesAction.kt"),
            Path.of("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/BuildProjectModulesAction.kt")
        )
        return candidates.firstOrNull { java.nio.file.Files.exists(it) }
            ?: error("BuildProjectModulesAction.kt source not found in: $candidates")
    }
}
