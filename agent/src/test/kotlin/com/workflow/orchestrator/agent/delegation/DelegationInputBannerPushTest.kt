package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationInputBannerPushTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `notifyDelegationQuestionPending invokes the testWebviewPushSink`() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val service = DelegationInboundService(project, cs)

        val pushedStates = mutableListOf<Pair<Boolean, String?>>()
        service.testWebviewPushSink = { active, repo -> pushedStates.add(active to repo) }

        service.notifyDelegationQuestionPending("sess-x", active = true, delegatorRepo = "backend-api")
        service.notifyDelegationQuestionPending("sess-x", active = false, delegatorRepo = null)

        assertEquals(2, pushedStates.size)
        assertEquals(true to "backend-api", pushedStates[0])
        assertEquals(false to null, pushedStates[1])
    }
}
