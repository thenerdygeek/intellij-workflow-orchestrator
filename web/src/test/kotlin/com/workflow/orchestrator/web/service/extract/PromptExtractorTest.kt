package com.workflow.orchestrator.web.service.extract

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PromptExtractorTest {

    @Test
    fun `SAFE verdict yields completed answer`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "Version 3.4.1", null)

        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        val result = extractor.extract(mockk<Project>(), "X v3.4.1 was released yesterday.", "What version of X is current?")
        assertEquals(PromptExtractor.Result.Complete("Version 3.4.1"), result)
    }

    @Test
    fun `STRIPPED verdict yields partial answer with note`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(
                SubagentSpawner.Verdict.STRIPPED,
                "Version 3.4.1 (release date [NOT IN SOURCE])",
                "release date missing from source",
            )
        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        val result = extractor.extract(mockk<Project>(), "X v3.4.1 is current.", "What version and release date for X?")
        assertTrue(result is PromptExtractor.Result.Partial)
        result as PromptExtractor.Result.Partial
        assertEquals("Version 3.4.1 (release date [NOT IN SOURCE])", result.answer)
        assertEquals("release date missing from source", result.note)
    }

    @Test
    fun `REFUSED verdict yields NoAnswer with reason`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.REFUSED, "", "page unrelated to question")
        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        val result = extractor.extract(mockk<Project>(), "Cooking recipes...", "What version of X?")
        assertEquals(PromptExtractor.Result.NoAnswer("page unrelated to question"), result)
    }

    @Test
    fun `TIMEOUT verdict yields NoAnswer with explicit reason`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.TIMEOUT, "", null)
        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        val result = extractor.extract(mockk<Project>(), "...", "...")
        assertEquals(PromptExtractor.Result.NoAnswer("extractor timed out"), result)
    }

    @Test
    fun `UNRECOGNISED verdict yields NoAnswer (fail-closed)`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        coEvery { spawner.runSanitizer(any(), any(), any(), any(), any()) } returns
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.UNRECOGNISED, "garbage", null)
        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        val result = extractor.extract(mockk<Project>(), "...", "...")
        assertTrue(result is PromptExtractor.Result.NoAnswer)
    }

    @Test
    fun `extractor wraps question and source in distinct per-call delimited tags`() = runTest {
        val spawner = mockk<SubagentSpawner>()
        val promptSlot = slot<String>()
        coEvery { spawner.runSanitizer(any(), any(), any(), capture(promptSlot), any()) } returns
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "answer", null)
        val extractor = PromptExtractor(spawner = spawner, brainId = null, timeoutMs = 10_000)
        extractor.extract(mockk<Project>(), "source text", "question text")
        val prompt = promptSlot.captured
        assertTrue(Regex("<question-[a-f0-9]{8}>").containsMatchIn(prompt),
            "prompt must wrap the question in a per-call delimited tag: $prompt")
        assertTrue(Regex("<source-[a-f0-9]{8}>").containsMatchIn(prompt),
            "prompt must wrap the source in a per-call delimited tag: $prompt")
    }
}
