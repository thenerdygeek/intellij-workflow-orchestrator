package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DbQueryToolOutputFileTest {

    @Test
    fun `DbQueryTool reads output_file param and forwards forceSpill`() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt"),
            java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
            ?: error("DbQueryTool.kt not found in either ${candidates.joinToString()}")
        val source = sourceFile.readText()

        assertTrue(
            source.contains("output_file"),
            "Expected DbQueryTool to read params[\"output_file\"]"
        )
        assertTrue(
            source.contains("forceSpill = true") || source.contains("forceSpill=true") ||
                source.contains("forceSpill = outputFileRequested") || source.contains("forceSpill=outputFileRequested"),
            "Expected DbQueryTool to pass forceSpill to spillOrFormat driven by output_file param"
        )
    }

    @Test
    fun `AgentTool spillOrFormat exposes forceSpill parameter`() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt"),
            java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
            ?: error("AgentTool.kt not found")
        val source = sourceFile.readText()

        assertTrue(
            source.contains("forceSpill: Boolean") || source.contains("forceSpill:Boolean"),
            "Expected spillOrFormat to expose a forceSpill: Boolean parameter"
        )
    }
}
