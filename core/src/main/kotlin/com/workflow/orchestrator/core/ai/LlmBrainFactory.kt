package com.workflow.orchestrator.core.ai

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

object LlmBrainFactory {

    fun create(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val aiSettings = AiSettings.getInstance(project)
        return OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = aiSettings.state.sourcegraphChatModel
                ?: "anthropic::2024-10-22::claude-sonnet-4-20250514"
        )
    }

    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
