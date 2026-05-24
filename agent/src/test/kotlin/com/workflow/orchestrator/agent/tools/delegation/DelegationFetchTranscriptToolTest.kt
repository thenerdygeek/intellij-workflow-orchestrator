package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.FetchTranscriptResult
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DelegationFetchTranscriptToolTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `returns path plus head when outbound service resolves ok`(@TempDir tmp: Path) = runBlocking {
        val transcript = Files.writeString(
            tmp.resolve("transcript-export.json"),
            """[{"role":"user","content":"hello"}]"""
        )
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.fetchTranscript(any())
        } returns FetchTranscriptResult.Ok(transcriptPath = transcript.toString())

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound

        val tool = DelegationTool()
        val result = tool.executeFetchTranscriptRaw(project = project, handleId = "h-xyz")

        assertFalse(result.isError, "expected success: ${result.summary}")
        assertTrue(result.summary.contains(transcript.toString()))
        assertTrue(result.summary.contains("hello"))
    }

    @Test
    fun `returns DelegationExpired when not_found`() = runBlocking {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.fetchTranscript(any())
        } returns FetchTranscriptResult.NotFound("pruned")

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound

        val tool = DelegationTool()
        val result = tool.executeFetchTranscriptRaw(project = project, handleId = "h-gone")

        assertTrue(result.isError)
        assertTrue(result.summary.contains("expired") || result.summary.contains("not_found") ||
                   result.summary.contains("Expired"))
    }
}
