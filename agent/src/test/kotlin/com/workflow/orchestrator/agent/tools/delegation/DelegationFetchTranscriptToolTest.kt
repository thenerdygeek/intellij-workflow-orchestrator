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
    fun `returns DelegationHandleNotFound for an unknown handle`() = runBlocking {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.fetchTranscript(any())
        } returns FetchTranscriptResult.NotFound("handle_not_found", FetchTranscriptResult.NotFoundKind.HANDLE_UNKNOWN)

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound

        val tool = DelegationTool()
        val result = tool.executeFetchTranscriptRaw(project = project, handleId = "h-gone")

        assertTrue(result.isError)
        // Fix (2026-06-01): an unknown/pruned handle is now consistently DelegationHandleNotFound
        // across all five handle-reading actions (was DelegationExpired only for fetch_transcript).
        assertTrue(
            (result.content + result.summary).contains("DelegationHandleNotFound"),
            "unknown handle must map to DelegationHandleNotFound; got: ${result.summary}",
        )
    }

    @Test
    fun `returns DelegationExpired for a genuine transcript-unreachable condition`() = runBlocking {
        val outbound = mockk<DelegationOutboundService>(relaxed = true)
        coEvery {
            outbound.fetchTranscript(any())
        } returns FetchTranscriptResult.NotFound(
            "no conversation history on disk for session sess-b",
            FetchTranscriptResult.NotFoundKind.TRANSCRIPT_UNREACHABLE,
        )

        val project = mockk<Project>(relaxed = true)
        every { project.getService(DelegationOutboundService::class.java) } returns outbound

        val tool = DelegationTool()
        val result = tool.executeFetchTranscriptRaw(project = project, handleId = "h-known")

        assertTrue(result.isError)
        assertTrue(
            (result.content + result.summary).contains("DelegationExpired"),
            "a genuine transcript-unreachable condition stays DelegationExpired; got: ${result.summary}",
        )
    }
}
