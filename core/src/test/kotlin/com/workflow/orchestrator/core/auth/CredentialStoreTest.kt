package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.workflow.orchestrator.core.model.ServiceType
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CredentialStoreTest {

    private lateinit var mockPasswordSafe: PasswordSafe
    private lateinit var store: CredentialStore
    private val storedCredentials = mutableMapOf<String, Credentials?>()

    @BeforeEach
    fun setUp() {
        mockPasswordSafe = mockk(relaxed = true)

        every { mockPasswordSafe.set(any(), any()) } answers {
            val attrs = firstArg<CredentialAttributes>()
            storedCredentials[attrs.serviceName] = secondArg()
        }

        every { mockPasswordSafe.get(any()) } answers {
            val attrs = firstArg<CredentialAttributes>()
            storedCredentials[attrs.serviceName]
        }

        store = CredentialStore(mockPasswordSafe)
    }

    @AfterEach
    fun tearDown() {
        storedCredentials.clear()
        CredentialStore.clearGlobalCache()
    }

    @Test
    fun `store and retrieve token`() {
        store.storeToken(ServiceType.JIRA, "test-token-123")
        assertEquals("test-token-123", store.getToken(ServiceType.JIRA))
    }

    @Test
    fun `retrieve missing token returns null`() {
        assertNull(store.getToken(ServiceType.BAMBOO))
    }

    @Test
    fun `hasToken returns false when no token stored`() {
        assertFalse(store.hasToken(ServiceType.BITBUCKET))
    }

    @Test
    fun `hasToken returns true when token exists`() {
        store.storeToken(ServiceType.BITBUCKET, "some-token")
        assertTrue(store.hasToken(ServiceType.BITBUCKET))
    }
}
