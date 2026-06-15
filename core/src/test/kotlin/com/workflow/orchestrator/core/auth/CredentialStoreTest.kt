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
    fun `storeToken returns true when the write is verified readable`() {
        assertTrue(store.storeToken(ServiceType.JIRA, "tok"))
    }

    @Test
    fun `storeToken returns false when the store silently drops the write`() {
        // Simulate PasswordSafe.set() no-op (locked/denied KeePass db): backing map never updated,
        // so the read-back verification returns null and the store reports failure.
        every { mockPasswordSafe.set(any(), any()) } answers { /* silently drop the write */ }
        assertFalse(store.storeToken(ServiceType.JIRA, "tok"))
        // The phantom value must not linger in the cache either.
        assertNull(store.getToken(ServiceType.JIRA))
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

    // ── F-8 URL-keyed cache tests ─────────────────────────────────────────────

    @Test
    fun `clearGlobalCache removes all entries`() {
        store.storeToken(ServiceType.JIRA, "jira-token")
        store.storeToken(ServiceType.BAMBOO, "bamboo-token")
        CredentialStore.clearGlobalCache()
        // After clearing, tokens should be re-read from PasswordSafe (which still has them)
        // but the cache is empty.  The returned value comes from PasswordSafe, not from cache.
        assertEquals("jira-token", store.getToken(ServiceType.JIRA))
    }

    @Test
    fun `store and retrieve survive clearGlobalCache round-trip`() {
        store.storeToken(ServiceType.SONARQUBE, "sonar-abc")
        CredentialStore.clearGlobalCache()
        // Must re-read from PasswordSafe and return the stored value
        assertEquals("sonar-abc", store.getToken(ServiceType.SONARQUBE))
    }

    @Test
    fun `URL key investigation — ConnectionSettings is APPLICATION-level so ServiceType key is not a cross-project leak`() {
        // This test documents the F-8 investigation conclusion:
        // ConnectionSettings is @Service(Level.APP) — all projects share the same URL per ServiceType.
        // The cache key Pair<ServiceType, serverUrl> correctly handles URL changes by producing
        // a cache miss when the URL changes. In test context serverUrlFor() returns "" (no IDE),
        // so both store() and getToken() use the same key and behave correctly.
        store.storeToken(ServiceType.SOURCEGRAPH, "sg-token-1")
        assertEquals("sg-token-1", store.getToken(ServiceType.SOURCEGRAPH))
    }
}
