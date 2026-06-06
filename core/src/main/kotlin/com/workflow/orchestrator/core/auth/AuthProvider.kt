package com.workflow.orchestrator.core.auth

import com.intellij.openapi.extensions.ExtensionPointName
import com.workflow.orchestrator.core.model.ServiceType

/**
 * STABLE, fork-facing extension point. The base ships [DefaultAuthProvider] (PAT/bearer + token).
 * Company forks register their own implementation (SSO/SAML/OAuth2/licensing) in their plugin.xml
 * overlay — the lowest-[order] provider that [supports] a service wins. Forks never edit :core.
 */
interface AuthProvider {
    /** Lower runs first; a fork overrides the base (which sits at the lowest priority). */
    val order: Int get() = 0

    /** Whether this provider supplies credentials for [service]. */
    fun supports(service: ServiceType): Boolean

    /** The credential for [service], or null if unconfigured (caller sends no auth header). */
    fun credentialFor(service: ServiceType): Credential?

    companion object {
        val EP_NAME: ExtensionPointName<AuthProvider> =
            ExtensionPointName.create("com.workflow.orchestrator.authProvider")

        /**
         * The active provider for [service]: the lowest-[order] registered provider that
         * supports it, falling back to a fresh [DefaultAuthProvider] if none is registered.
         */
        fun resolve(service: ServiceType): AuthProvider =
            EP_NAME.extensionList
                .filter { it.supports(service) }
                .minByOrNull { it.order }
                ?: DefaultAuthProvider()
    }
}
