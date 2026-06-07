package com.workflow.orchestrator.core.auth

import com.workflow.orchestrator.core.model.ServiceType
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultAuthProviderTest {
    private val store = mockk<CredentialStore>()
    private val provider = DefaultAuthProvider(store)

    @Test
    fun `sourcegraph uses token scheme`() {
        every { store.getToken(ServiceType.SOURCEGRAPH) } returns "sg"
        assertEquals(Credential.Token("sg"), provider.credentialFor(ServiceType.SOURCEGRAPH))
    }

    @Test
    fun `jira uses bearer scheme`() {
        every { store.getToken(ServiceType.JIRA) } returns "jt"
        assertEquals(Credential.Bearer("jt"), provider.credentialFor(ServiceType.JIRA))
    }

    @Test
    fun `null token yields null credential`() {
        every { store.getToken(ServiceType.JIRA) } returns null
        assertNull(provider.credentialFor(ServiceType.JIRA))
    }

    @Test
    fun `default supports every service`() {
        ServiceType.entries.forEach { assertTrue(provider.supports(it)) }
    }
}
