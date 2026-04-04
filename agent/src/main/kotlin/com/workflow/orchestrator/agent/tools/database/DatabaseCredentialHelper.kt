package com.workflow.orchestrator.agent.tools.database

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * PasswordSafe wrapper for database profile passwords.
 * Uses a dynamic key per profile: "WorkflowOrchestrator.DB.{profileId}"
 * — bypasses the fixed ServiceType enum because profiles are user-defined.
 */
object DatabaseCredentialHelper {

    fun storePassword(profileId: String, password: String) {
        val attrs = attributes(profileId)
        PasswordSafe.instance.set(attrs, Credentials(profileId, password))
    }

    fun getPassword(profileId: String): String? {
        val attrs = attributes(profileId)
        return PasswordSafe.instance.get(attrs)?.getPasswordAsString()
    }

    fun removePassword(profileId: String) {
        PasswordSafe.instance.set(attributes(profileId), null)
    }

    private fun attributes(profileId: String) = CredentialAttributes(
        generateServiceName("WorkflowOrchestrator", "DB.$profileId")
    )
}
