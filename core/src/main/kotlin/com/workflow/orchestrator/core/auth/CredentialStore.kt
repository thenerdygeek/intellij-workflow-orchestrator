package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ServiceType

class CredentialStore(
    private val passwordSafe: PasswordSafe? = null
) {

    private val log = Logger.getInstance(CredentialStore::class.java)

    private fun safe(): PasswordSafe = passwordSafe ?: PasswordSafe.instance

    fun storeToken(service: ServiceType, token: String) {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        safe().set(attributes, credentials)
        log.info("[Core:Credentials] Stored credential for ${service.name}")
    }

    fun getToken(service: ServiceType): String? {
        val attributes = credentialAttributes(service)
        val result = safe().get(attributes)?.getPasswordAsString()
        if (result != null) {
            log.debug("[Core:Credentials] Retrieved credential for ${service.name}")
        } else {
            log.debug("[Core:Credentials] No credential found for ${service.name}")
        }
        return result
    }

    fun removeToken(service: ServiceType) {
        val attributes = credentialAttributes(service)
        safe().set(attributes, null)
        log.info("[Core:Credentials] Removed credential for ${service.name}")
    }

    fun hasToken(service: ServiceType): Boolean {
        return getToken(service) != null
    }

    private fun credentialAttributes(service: ServiceType): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("WorkflowOrchestrator", service.name)
        )
    }
}
