package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

class DelegationCascadeCancelTest {

    @BeforeEach fun setup() { installReadActionInlineShim() }
    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `cancelAllForSession closes only handles owned by that session`() {
        val project = mockk<Project>(relaxed = true)
        every { project.messageBus } returns mockk(relaxed = true)
        val cs = kotlinx.coroutines.CoroutineScope(SupervisorJob())
        val service = DelegationOutboundService(project, cs)

        injectHandle(service, handleId = "h1", sessionId = "sess-A")
        injectHandle(service, handleId = "h2", sessionId = "sess-A")
        injectHandle(service, handleId = "h3", sessionId = "sess-B")

        val closed = service.cancelAllForSession("sess-A", reason = "parent_canceled")
        assertEquals(setOf("h1", "h2"), closed.toSet())

        val survivingMap = handleToSessionIdMap(service)
        assertEquals(setOf("h3"), survivingMap.keys)
    }

    private fun injectHandle(service: DelegationOutboundService, handleId: String, sessionId: String) {
        val htsField: Field = DelegationOutboundService::class.java
            .getDeclaredField("handleToSessionId").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = htsField.get(service) as java.util.concurrent.ConcurrentHashMap<String, String>
        map[handleId] = sessionId

        val acField: Field = DelegationOutboundService::class.java
            .getDeclaredField("activeChannels").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val ac = acField.get(service) as java.util.concurrent.ConcurrentHashMap<String, java.nio.channels.SocketChannel>
        ac[handleId] = mockk(relaxed = true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleToSessionIdMap(service: DelegationOutboundService): java.util.concurrent.ConcurrentHashMap<String, String> {
        val f: Field = DelegationOutboundService::class.java
            .getDeclaredField("handleToSessionId").apply { isAccessible = true }
        return f.get(service) as java.util.concurrent.ConcurrentHashMap<String, String>
    }
}
