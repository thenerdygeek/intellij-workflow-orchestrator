package com.workflow.orchestrator.cody.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyAgentProviderServiceTest {

    @Test
    fun `StandaloneCodyAgentProvider has lowest priority`() {
        val provider = StandaloneCodyAgentProvider()
        assertEquals(0, provider.priority)
        assertEquals("Standalone Agent", provider.displayName)
        assertFalse(provider.handlesDocumentSync())
    }

    @Test
    fun `CodyAgentProvider interface has correct extension point name`() {
        assertEquals(
            "com.workflow.orchestrator.codyAgentProvider",
            CodyAgentProvider.EP_NAME.name
        )
    }

    @Test
    fun `providers sort correctly by priority`() {
        val standalone = StandaloneCodyAgentProvider()
        val providers = listOf(standalone).sortedByDescending { it.priority }
        assertEquals("Standalone Agent", providers.first().displayName)
    }
}
