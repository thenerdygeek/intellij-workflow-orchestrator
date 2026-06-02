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

    /**
     * Create an LlmBrain using the latest non-thinking Sonnet — for text generation tasks
     * (commit messages, PR descriptions). Non-thinking Sonnet keeps the same 200K input
     * window as the thinking variant but skips the reserved thinking-token budget, leaving
     * the full window available for prompt data. Single-shot generation doesn't need the
     * deliberation thinking provides. Falls back to the user's configured model if Sonnet
     * is not available at all.
     */
    suspend fun createForTextGeneration(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val client = SourcegraphChatClient(
            baseUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = ""
        )
        val models = ModelCache.getModels(client)
        val sonnet = ModelCache.pickSonnetNonThinking(models)
        if (sonnet != null) {
            LOG.info("LlmBrainFactory: using ${sonnet.id} (non-thinking) for text generation")
            return OpenAiCompatBrain(
                sourcegraphUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = sonnet.id
            )
        }
        // Fall back to the user's configured model
        LOG.info("LlmBrainFactory: Sonnet not available, falling back to configured model")
        return create(project)
    }

    /**
     * Create an LlmBrain using the cheapest available model (Haiku if available, then
     * non-thinking Sonnet) for single-shot web content sanitization tasks. Using a cheaper
     * model reduces cost for the high-frequency sanitizer sub-agent. Falls back to the
     * user's configured model if no Haiku/Sonnet is available.
     */
    suspend fun createForSanitization(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val client = SourcegraphChatClient(
            baseUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = ""
        )
        val models = ModelCache.getModels(client)

        // Prefer Haiku, then non-thinking Sonnet. As a last resort use pickCheapest
        // (Haiku > Sonnet > anything) — NOT create(project): the configured model is
        // typically the heavy Opus-thinking orchestrator model, whose latency + thinking
        // overhead blow the sanitizer's 60s budget and whose reasoning tokens crowd out the
        // verbatim echo, surfacing as SANITIZER_TIMEOUT. Only when no model is resolvable at
        // all do we fall through to the configured model.
        val pick = ModelCache.pickHaiku(models)
            ?: ModelCache.pickSonnetNonThinking(models)
            ?: ModelCache.pickCheapest(models)
        if (pick != null) {
            LOG.info("LlmBrainFactory: using ${pick.id} for web content sanitization")
            return OpenAiCompatBrain(
                sourcegraphUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = pick.id
            )
        }
        // No model resolvable from the catalog — fall back to the user's configured model.
        LOG.info("LlmBrainFactory: no catalog model available, falling back to configured model for sanitization")
        return create(project)
    }

    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
