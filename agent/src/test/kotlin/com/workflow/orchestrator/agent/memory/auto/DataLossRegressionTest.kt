package com.workflow.orchestrator.agent.memory.auto

import com.workflow.orchestrator.agent.memory.CoreMemory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Regression test for C1 — stale memory instance data loss.
 *
 * Scenario: Agent holds a long-lived CoreMemory instance. Settings page creates
 * its own instance, user edits via settings, saves to disk. Without reload(),
 * the next agent memory tool call would overwrite disk with the agent's stale snapshot.
 */
class DataLossRegressionTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `reload prevents agent instance from overwriting settings-page edits`() {
        val storageFile = tempDir.resolve("core-memory.json").toFile()

        // Agent's long-lived instance
        val agentInstance = CoreMemory(storageFile)
        agentInstance.append("user", "Agent-written fact")

        // Settings page creates fresh instance, user edits, saves
        val settingsInstance = CoreMemory(storageFile)
        settingsInstance.setBlock("user", "User-edited via settings")

        // Without reload, agent still sees its stale value
        assertEquals("Agent-written fact", agentInstance.read("user"))

        // After reload (simulating AgentService.reloadMemoryFromDisk)
        agentInstance.reload()
        assertEquals("User-edited via settings", agentInstance.read("user"))

        // Agent can now append without destroying the user edit
        agentInstance.append("user", "Additional agent fact")
        val reread = CoreMemory(storageFile)
        assertTrue(reread.read("user")!!.contains("User-edited via settings"))
        assertTrue(reread.read("user")!!.contains("Additional agent fact"))
    }
}
