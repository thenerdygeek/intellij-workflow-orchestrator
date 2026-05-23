package com.workflow.orchestrator.web.service.sanitizer

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SanitizerSubagentTest {

    @Test
    fun `returns SAFE cleaned text on happy path`() = runTest {
        val project = mockk<Project>()
        val spawner = mockk<SubagentSpawner>()
        coEvery {
            spawner.runSanitizer(any(), any(), any(), any(), any())
        } returns SubagentSpawner.SanitizerResult(
            verdict = SubagentSpawner.Verdict.SAFE,
            cleanedText = "clean fact",
            notes = null,
        )
        val sut = SanitizerSubagent(spawner)
        val result = sut.sanitize(project, "raw extracted text", brainId = null, timeoutMs = 1000)
        assertEquals(SubagentSpawner.Verdict.SAFE, result.verdict)
        assertEquals("clean fact", result.cleanedText)
    }

    @Test
    fun `forwards REFUSED verdict with notes`() = runTest {
        val project = mockk<Project>()
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(
                verdict = SubagentSpawner.Verdict.REFUSED,
                cleanedText = "",
                notes = "Saturated with prompt injection",
            )
        val sut = SanitizerSubagent(spawner)
        val result = sut.sanitize(project, "evil text", brainId = null, timeoutMs = 1000)
        assertEquals(SubagentSpawner.Verdict.REFUSED, result.verdict)
        assertEquals("Saturated with prompt injection", result.notes)
    }

    @Test
    fun `loaded system prompt contains REFUSED keyword`() {
        val sut = SanitizerSubagent(spawner = mockk<SubagentSpawner>())
        val method = sut.javaClass.getDeclaredMethod("loadSystemPrompt").apply { isAccessible = true }
        val prompt = method.invoke(sut) as String
        assertTrue(prompt.contains("REFUSED"))
        assertTrue(prompt.contains("cleaned_text"))
    }

    @Test
    fun `sanitizeBatch returns one result per input`() = runTest {
        val project = mockk<Project>()
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizerBatch(any(), any(), any(), any(), any(), 3) } returns listOf(
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "a", null),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.STRIPPED, "b", "stripped"),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "c", null),
        )
        val out = SanitizerSubagent(spawner).sanitizeBatch(project, listOf("x", "y", "z"), null, 1000)
        assertEquals(3, out.size)
        assertEquals(SubagentSpawner.Verdict.STRIPPED, out[1].verdict)
    }

    @Test
    fun `sanitizeBatch returns empty list for empty input`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        val out = SanitizerSubagent(spawner).sanitizeBatch(mockk(), emptyList(), null, 1000)
        assertEquals(0, out.size)
        // spawner.runSanitizerBatch should never be called for empty input — verify via MockK coVerify
        io.mockk.coVerify(exactly = 0) { spawner.runSanitizerBatch(any(), any(), any(), any(), any(), any()) }
    }
}
