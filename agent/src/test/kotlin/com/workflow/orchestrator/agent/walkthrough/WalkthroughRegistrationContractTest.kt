package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WalkthroughRegistrationContractTest {

    private fun source(rel: String): String {
        val root = Path.of("src/main/kotlin/com/workflow/orchestrator/agent")
        return Files.readString(root.resolve(rel))
    }

    @Test
    fun `walkthrough is registered as a deferred Code Intelligence tool`() {
        val text = source("AgentService.kt")
        val matched = Regex("""safeRegisterDeferred\("Code Intelligence"\)\s*\{\s*WalkthroughTool\(\)\s*\}""")
            .containsMatchIn(text)
        assertTrue(
            matched,
            "AgentService.registerAllTools must register WalkthroughTool deferred under Code Intelligence",
        )
    }

    @Test
    fun `walkthrough is hard-filtered from sub-agent tool sets by NAME (allowedWorkers gates nothing)`() {
        val text = source("tools/builtin/SpawnAgentTool.kt")
        val filterLines = text.lines().filter { it.contains("""it != "render_artifact"""") }
        assertTrue(
            filterLines.size >= 2,
            "expected the two name-filter chains in resolveConfigToolsTiered",
        )
        filterLines.forEach { line ->
            assertTrue(
                line.contains("""it != "walkthrough""""),
                "sub-agent name filter must exclude walkthrough: $line",
            )
        }
    }

    @Test
    fun `walkthrough must NOT be a mutating tool (plan-mode legal by design)`() {
        val walkthroughSrc = source("tools/ide/WalkthroughTool.kt")
        assertFalse(
            walkthroughSrc.contains("override val isMutating = true"),
            "walkthrough must not be a mutating tool (read-only -> plan-mode legal); " +
                "declaring isMutating = true would block plan-mode tours",
        )
    }
}
