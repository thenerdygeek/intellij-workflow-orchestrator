package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolContributionRunnerTest {
    private val project = mockk<Project>(relaxed = true)

    private fun toolNamed(n: String) = object : AgentTool {
        override val name = n
        override val description = n
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    // NOTE: AgentToolContributor is a plain `interface` (NOT `fun interface`), so SAM-lambda
    // construction is unavailable — use anonymous objects (mirrors the existing ToolRegistrationContextTest).
    private fun contributor(body: (ToolRegistrationContext) -> Unit) = object : AgentToolContributor {
        override fun registerTools(context: ToolRegistrationContext) = body(context)
    }

    @Test
    fun `a throwing contributor does not block the others (per-contributor isolation)`() {
        val registry = ToolRegistry()
        val good = contributor { ctx -> ctx.registerCore(toolNamed("good_tool")) }
        val bad = contributor { throw IllegalStateException("boom") }
        val good2 = contributor { ctx -> ctx.registerCore(toolNamed("good_tool_2")) }

        val diag = ToolContributionRunner.run(listOf(good, bad, good2),
            ToolRegistrationContext(project, registry), registry)

        assertTrue(registry.has("good_tool"))
        assertTrue(registry.has("good_tool_2"), "a failure mid-list must not abort later contributors")
        assertEquals(3, diag.contributorCount)
        assertEquals(setOf("good_tool", "good_tool_2"), diag.addedToolNames)
        assertEquals(1, diag.failures.size)
        assertTrue(diag.failures.first().error.message!!.contains("boom"))
    }

    @Test
    fun `no contributors yields empty diagnostics`() {
        val registry = ToolRegistry()
        val diag = ToolContributionRunner.run(emptyList(),
            ToolRegistrationContext(project, registry), registry)
        assertEquals(0, diag.contributorCount)
        assertTrue(diag.addedToolNames.isEmpty())
        assertTrue(diag.failures.isEmpty())
    }

    @Test
    fun `a contributor that registers a deferred tool appears in addedToolNames`() {
        // Regression: before the fix, addedToolNames was diffed on getActiveTools() which
        // excludes the deferred tier — so a registerDeferred call produced addedToolNames=[] even
        // though the tool was registered correctly. allToolNames() spans all three tiers.
        val registry = ToolRegistry()
        val c = contributor { ctx -> ctx.registerDeferred(toolNamed("deferred_tool")) }

        val diag = ToolContributionRunner.run(listOf(c),
            ToolRegistrationContext(project, registry), registry)

        assertTrue("deferred_tool" in diag.addedToolNames,
            "deferred tool must appear in diagnostic — addedToolNames was ${diag.addedToolNames}")
        assertEquals(0, diag.failures.size)
    }
}
