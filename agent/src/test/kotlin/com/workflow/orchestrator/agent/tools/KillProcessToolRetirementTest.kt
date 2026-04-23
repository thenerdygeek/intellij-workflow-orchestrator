package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue

class KillProcessToolRetirementTest {
    private val forbiddenInSource = listOf(
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt",
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt",
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt",
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SendStdinTool.kt",
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ProcessToolHelpers.kt",
        "agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt",
    )

    @Test
    fun `kill_process does not appear in production source`() {
        val root = locateRepoRoot()
        forbiddenInSource.forEach { rel ->
            val p = root.resolve(rel)
            assertTrue(Files.exists(p), "missing source file: $rel")
            val text = Files.readString(p)
            assertTrue(!text.contains("kill_process"),
                "'$rel' still contains kill_process string")
            assertTrue(!text.contains("KillProcessTool"),
                "'$rel' still contains KillProcessTool identifier")
        }
    }

    @Test
    fun `kill_process does not appear in any persona YAML`() {
        val root = locateRepoRoot()
        val personasDir = root.resolve("agent/src/main/resources/agents")
        assertTrue(Files.exists(personasDir), "personas dir missing: $personasDir")
        Files.list(personasDir).use { stream ->
            stream.filter { it.toString().endsWith(".md") }.forEach { p ->
                val text = Files.readString(p)
                assertTrue(!text.contains("kill_process"),
                    "persona YAML '${p.fileName}' still contains kill_process")
            }
        }
    }

    private fun locateRepoRoot(): Path {
        // Walk up from CWD until we see "gradle.properties" as a sibling. Gradle runs
        // tests from the module directory, so repo root is typically 1 level up.
        var p: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            if (Files.exists(p.resolve("gradle.properties")) && Files.exists(p.resolve("agent"))) {
                return p
            }
            p = p.parent ?: error("cannot locate repo root")
        }
        error("cannot locate repo root from ${Path.of(System.getProperty("user.dir")).toAbsolutePath()}")
    }
}
