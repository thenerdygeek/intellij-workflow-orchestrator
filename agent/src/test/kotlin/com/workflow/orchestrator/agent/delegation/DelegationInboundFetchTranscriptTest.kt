package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
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

class DelegationInboundFetchTranscriptTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `handleFetchTranscript replies ok with transcript path when session exists`(@TempDir tmp: Path) = runBlocking {
        val sessionId = "sess-tx-1"
        val sessionDir = Files.createDirectories(tmp.resolve("sessions").resolve(sessionId))
        Files.writeString(
            sessionDir.resolve("api_conversation_history.json"),
            """[{"role":"user","content":"hello"}]""",
        )

        val replies = mutableListOf<DelegationMessage>()
        val replyWith: suspend (DelegationMessage) -> Unit = { replies.add(it) }

        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationInboundService(project, cs)

        service.testSessionDirResolver = { sid -> if (sid == sessionId) sessionDir else null }

        service.handleFetchTranscript(
            sessionId = sessionId,
            requestId = "req-1",
            replyWith = replyWith,
        )

        assertEquals(1, replies.size)
        val reply = replies[0] as DelegationMessage.FetchTranscriptReply
        assertEquals("req-1", reply.requestId)
        assertEquals("ok", reply.status)
        assertNotNull(reply.transcriptPath)
        assertTrue(Files.exists(Path.of(reply.transcriptPath!!)),
            "Exported transcript file should exist at ${reply.transcriptPath}")
    }

    @Test
    fun `replies not_found when session resolver returns null`() = runBlocking {
        val replies = mutableListOf<DelegationMessage>()
        val replyWith: suspend (DelegationMessage) -> Unit = { replies.add(it) }

        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationInboundService(project, cs)
        service.testSessionDirResolver = { null }

        service.handleFetchTranscript(
            sessionId = "sess-missing",
            requestId = "req-2",
            replyWith = replyWith,
        )

        assertEquals(1, replies.size)
        val reply = replies[0] as DelegationMessage.FetchTranscriptReply
        assertEquals("not_found", reply.status)
        assertEquals("req-2", reply.requestId)
        assertNull(reply.transcriptPath)
        assertNotNull(reply.error)
    }
}
