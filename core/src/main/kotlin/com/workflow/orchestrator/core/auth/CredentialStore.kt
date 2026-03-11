package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.workflow.orchestrator.core.model.ServiceType

class CredentialStore(
    private val passwordSafe: PasswordSafe? = null
) {

    private fun safe(): PasswordSafe = passwordSafe ?: PasswordSafe.instance

    fun storeToken(service: ServiceType, token: String) {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        safe().set(attributes, credentials)
    }

    fun getToken(service: ServiceType): String? {
        val attributes = credentialAttributes(service)
        return safe().get(attributes)?.getPasswordAsString()
    }

    fun removeToken(service: ServiceType) {
        val attributes = credentialAttributes(service)
        safe().set(attributes, null)
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
