package com.workflow.orchestrator.core.auth

import com.workflow.orchestrator.core.model.ServiceType

/**
 * Base credential resolution: read the per-service token from PasswordSafe via [CredentialStore]
 * and wrap it in the scheme the service expects (Sourcegraph = `token`, everything else = `Bearer`).
 * This preserves the exact pre-Phase-2 behavior. Forks override via the [AuthProvider] EP.
 *
 * Registered as the base `authProvider` extension. Sits at the lowest priority
 * ([order] = [Int.MAX_VALUE]) so any fork-supplied provider wins.
 */
class DefaultAuthProvider(
    private val credentialStore: CredentialStore = CredentialStore()
) : AuthProvider {
    override val order: Int get() = Int.MAX_VALUE

    override fun supports(service: ServiceType): Boolean = true

    override fun credentialFor(service: ServiceType): Credential? {
        val token = credentialStore.getToken(service) ?: return null
        return when (service) {
            ServiceType.SOURCEGRAPH -> Credential.Token(token)
            else -> Credential.Bearer(token)
        }
    }
}
