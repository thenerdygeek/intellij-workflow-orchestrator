package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class FindReferencesToolSmartModeTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `find_references does not eagerly bail when indexer is busy`() = runTest {
        val project = mockk<Project>(relaxed = true)

        mockkStatic(DumbService::class)
        every { DumbService.isDumb(project) } returns true

        val registry = LanguageProviderRegistry()
        val tool = FindReferencesTool(registry)

        val params = buildJsonObject {
            put("symbol", "NonExistentSymbol")
            put("path", "Foo.java")
        }

        val result = try {
            tool.execute(params, project)
        } catch (e: Exception) {
            // Any exception other than the dumb-mode short-circuit is acceptable —
            // it proves we got past the guard.
            return@runTest
        }

        val text = result.content
        assertFalse(
            text.contains("IDE is still indexing", ignoreCase = true),
            "Expected guard to be removed, but got: $text"
        )
    }
}
