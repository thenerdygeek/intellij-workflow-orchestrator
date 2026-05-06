package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.services.SessionDownloadDir
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentLoopAttachmentAccessTest {

    @Test
    fun `tool invocation runs with SessionAttachmentAccess installed`(@TempDir sessionDir: Path) = runTest {
        val store = AttachmentStore(sessionDir)
        var observed: AttachmentStore? = null

        AgentLoopAttachmentScope.runWithStore(store) {
            observed = SessionAttachmentAccess.current()
        }

        assertSame(store, observed)
    }

    @Test
    fun `tool invocation runs with SessionDownloadDir pointing at sessionDir-downloads`(@TempDir sessionDir: Path) = runTest {
        val store = AttachmentStore(sessionDir)
        var observed: Path? = null

        AgentLoopAttachmentScope.runWithStore(store) {
            observed = SessionDownloadDir.current()
        }

        assertEquals(sessionDir.resolve("downloads"), observed)
    }
}
