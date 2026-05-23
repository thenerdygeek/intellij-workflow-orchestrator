package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.delegation.DelegationMessage
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationInboundProjectCloseTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `closeAllForProjectClose writes FAILED with project_closed reason on every channel`() = runBlocking {
        val service = newServiceUnderTest()
        val replyA = mockk<suspend (DelegationMessage) -> Unit>(relaxed = true)
        val replyB = mockk<suspend (DelegationMessage) -> Unit>(relaxed = true)
        service.registerSessionChannel("sess-a", replyA)
        service.registerSessionChannel("sess-b", replyB)

        service.closeAllForProjectClose()

        val sentA = slot<DelegationMessage>()
        val sentB = slot<DelegationMessage>()
        coVerify { replyA.invoke(capture(sentA)) }
        coVerify { replyB.invoke(capture(sentB)) }
        val resultA = sentA.captured as DelegationMessage.Result
        val resultB = sentB.captured as DelegationMessage.Result
        assertEquals(DelegationMessage.ResultStatus.FAILED, resultA.status)
        assertEquals("project_closed", resultA.reason)
        assertEquals(DelegationMessage.ResultStatus.FAILED, resultB.status)
        assertEquals("project_closed", resultB.reason)
    }

    private fun newServiceUnderTest(): DelegationInboundService {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/proj"
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        return DelegationInboundService(project, cs)
    }
}
