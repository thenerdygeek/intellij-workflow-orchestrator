package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunCommandToolSubmoduleHintTest {

    @Test
    fun `RunCommandTool source mentions submodule hint for git not-a-repo errors`() {
        val candidates = listOf(
            java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt"),
            java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.exists() }
            ?: error("RunCommandTool.kt not found in either ${candidates.joinToString()}")

        val source = sourceFile.readText()
        assertTrue(
            source.contains("not a git repository"),
            "Expected RunCommandTool to detect 'not a git repository' in output"
        )
        assertTrue(
            source.contains("submodule") && source.contains("working_dir"),
            "Expected hint text to mention 'submodule' and 'working_dir'"
        )
        assertTrue(
            source.contains("startsWith(\"git \")") || source.contains("startsWith(\"git\")"),
            "Expected guard so the hint only fires for git commands"
        )
    }
}
