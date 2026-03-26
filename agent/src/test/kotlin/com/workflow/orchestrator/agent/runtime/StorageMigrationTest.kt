package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StorageMigrationTest {

    @Test
    fun `migrates core-memory from old project dir`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "core-memory.json").writeText("""{"key":"value"}""")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        val newFile = File(newRoot, "agent/core-memory.json")
        assertTrue(newFile.exists(), "core-memory.json should be migrated")
        assertEquals("""{"key":"value"}""", newFile.readText())
        assertFalse(File(oldAgentDir, "core-memory.json").exists(), "old file should be moved")
    }

    @Test
    fun `migrates guardrails from old project dir`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "guardrails.md").writeText("- Don't do X")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/guardrails.md").exists())
    }

    @Test
    fun `migrates memory directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "memory").mkdirs()
        File(oldAgentDir, "memory/MEMORY.md").writeText("# Memory")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/memory/MEMORY.md").exists())
    }

    @Test
    fun `migrates archival directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "archival").mkdirs()
        File(oldAgentDir, "archival/store.json").writeText("[]")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/archival/store.json").exists())
    }

    @Test
    fun `migrates metrics directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "metrics").mkdirs()
        File(oldAgentDir, "metrics/scorecard-abc.json").writeText("{}")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/metrics/scorecard-abc.json").exists())
    }

    @Test
    fun `skips migration if old dir does not exist`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        val newRoot = File(tempDir, "new-root")

        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)
        assertFalse(File(newRoot, "agent").exists())
    }

    @Test
    fun `marker file prevents re-migration`(@TempDir tempDir: File) {
        val newRoot = File(tempDir, "new-root")
        newRoot.mkdirs()
        File(newRoot, "migration-v1.marker").createNewFile()

        val oldAgentDir = File(tempDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "core-memory.json").writeText("data")

        StorageMigration.migrateIfNeeded(
            projectBasePath = tempDir.absolutePath,
            newRoot = newRoot,
            oldProjectAgentDir = oldAgentDir,
            oldSystemSessionsDir = File(tempDir, "nonexistent")
        )

        assertTrue(File(oldAgentDir, "core-memory.json").exists(), "old file should NOT be migrated")
    }
}
