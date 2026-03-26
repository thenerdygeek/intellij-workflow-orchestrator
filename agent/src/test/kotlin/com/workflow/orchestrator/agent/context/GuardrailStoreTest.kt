package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.core.util.ProjectIdentifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GuardrailStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: GuardrailStore

    @BeforeEach
    fun setup() {
        store = GuardrailStore(tempDir)
    }

    @Test
    fun `record adds constraint to store`() {
        store.record("Always re-read build.gradle.kts before editing — whitespace-sensitive syntax")
        assertEquals(1, store.size)
    }

    @Test
    fun `save persists to guardrails md`() {
        store.record("Avoid calling run_command with gradlew test — use module-specific test instead")
        store.save()

        val file = File(ProjectIdentifier.agentDir(tempDir.absolutePath), "guardrails.md")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("Avoid calling run_command"))
    }

    @Test
    fun `load reads from guardrails md`() {
        val dir = ProjectIdentifier.agentDir(tempDir.absolutePath)
        dir.mkdirs()
        File(dir, "guardrails.md").writeText(
            "# Agent Guardrails\n\n- Constraint one\n- Constraint two\n"
        )

        val loaded = GuardrailStore(tempDir)
        loaded.load()
        assertEquals(2, loaded.size)
    }

    @Test
    fun `toContextString renders guardrails tag`() {
        store.record("Rule one")
        store.record("Rule two")
        val ctx = store.toContextString()
        assertTrue(ctx.contains("<guardrails>"))
        assertTrue(ctx.contains("Rule one"))
        assertTrue(ctx.contains("Rule two"))
        assertTrue(ctx.contains("</guardrails>"))
    }

    @Test
    fun `FIFO eviction when max exceeded`() {
        val small = GuardrailStore(tempDir, maxConstraints = 3)
        small.record("First")
        small.record("Second")
        small.record("Third")
        small.record("Fourth")
        assertEquals(3, small.size)
        assertFalse(small.toContextString().contains("First"))
        assertTrue(small.toContextString().contains("Fourth"))
    }

    @Test
    fun `estimateTokens returns non-zero when constraints exist`() {
        store.record("A constraint")
        assertTrue(store.estimateTokens() > 0)
    }

    @Test
    fun `estimateTokens returns zero when empty`() {
        assertEquals(0, store.estimateTokens())
    }

    @Test
    fun `duplicate constraints are not added`() {
        store.record("Same constraint")
        store.record("Same constraint")
        assertEquals(1, store.size)
    }

    @Test
    fun `load from non-existent file returns empty`() {
        store.load()
        assertEquals(0, store.size)
    }

    @Test
    fun `save creates directory if needed`() {
        val nestedBase = File(tempDir, "deep/nested")
        val nested = GuardrailStore(nestedBase)
        nested.record("A rule")
        nested.save()
        assertTrue(File(ProjectIdentifier.agentDir(nestedBase.absolutePath), "guardrails.md").exists())
    }
}
