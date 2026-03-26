package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

object LlmBrainFactory {

    private val LOG = Logger.getInstance(LlmBrainFactory::class.java)

    /**
     * Create an LlmBrain, resolving the model dynamically if not configured.
     * Suspend because it may need to fetch models from the API on first use.
     */
    suspend fun create(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val aiSettings = AiSettings.getInstance(project)
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val model = aiSettings.state.sourcegraphChatModel
            ?: resolveAndSaveModel(sgUrl, tokenProvider, aiSettings)

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = model
        )
    }

    private suspend fun resolveAndSaveModel(
        sgUrl: String,
        tokenProvider: () -> String?,
        aiSettings: AiSettings
    ): String {
        LOG.info("LlmBrainFactory: no model configured, auto-resolving from API")
        val client = SourcegraphChatClient(
            baseUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = "" // not needed for listModels
        )
        val models = ModelCache.getModels(client)
        val best = ModelCache.pickBest(models)
            ?: throw IllegalStateException(
                "No models available from Sourcegraph. Check your connection and token in Settings > Workflow Orchestrator > General."
            )
        LOG.info("LlmBrainFactory: auto-selected model: ${best.id}")
        aiSettings.state.sourcegraphChatModel = best.id
        return best.id
    }

    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
