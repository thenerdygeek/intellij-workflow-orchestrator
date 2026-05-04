package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentLoopAttachmentAccessTest {

    @Test
    fun `tool invocation runs with SessionAttachmentAccess installed`() = runTest {
        val store = mockk<AttachmentStore>()
        var observed: AttachmentStore? = null

        AgentLoopAttachmentScope.runWithStore(store) {
            observed = SessionAttachmentAccess.current()
        }

        assertSame(store, observed)
    }
}
