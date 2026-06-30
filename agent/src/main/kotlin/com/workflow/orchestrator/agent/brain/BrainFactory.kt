package com.workflow.orchestrator.agent.brain

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ai.AnthropicDirectBrain
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tool.SessionAttachmentAccess
import com.workflow.orchestrator.core.ai.AnthropicModelCatalog
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.ai.anthropic.AnthropicHttpClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Constructs the per-task LLM brain — extracted from `AgentService.createBrain` (Phase 3 cut D).
 *
 * The branchy model-selection precedence is carved into the pure, unit-testable [resolveModel];
 * [create] does the surrounding settings/credential I/O and brain construction. Forks customize
 * model/brain selection here without touching `AgentService` (Phase 2/3 fork seam).
 *
 * The tool/param name sets are injected as providers (rather than a direct `ToolRegistry`
 * reference) so the factory depends only on what it uses and stays trivially constructible.
 */
class BrainFactory(
    private val project: Project,
    private val toolNames: () -> Set<String>,
    private val paramNames: () -> Set<String>,
    /**
     * Provider for the session-scoped [SessionAttachmentAccess] (wraps the per-session
     * [AttachmentStore]). Used by [AnthropicDirectBrain] for image hydration on the native
     * Anthropic path. Returns null outside a live session or when Sourcegraph is the provider.
     * [AgentService] wires this to `{ activeAttachmentStore?.let { SessionAttachmentAccess(it) } }`.
     */
    private val attachmentAccess: () -> SessionAttachmentAccess? = { null },
    /**
     * Provider for the raw session directory (e.g. `sessions/{sid}`). The factory uses this
     * to derive the `api-debug/` sub-directory for [AnthropicHttpClient] debug dumps when
     * [AgentSettings.State.writeApiDebugDumps] is true. Null outside a live session.
     */
    private val sessionDebugDir: () -> java.io.File? = { null },
) {

    private val log = Logger.getInstance(BrainFactory::class.java)

    /**
     * Create a fresh [LlmBrain] for a task execution. Never cached — always picks up the latest
     * settings (model, URL, token).
     */
    suspend fun create(modelOverride: String? = null): LlmBrain {
        val agentSettings = AgentSettings.getInstance(project)

        // ── Native Anthropic branch — evaluated FIRST, before the blank-Sourcegraph-URL guard ──
        // This ensures the Anthropic path never requires a Sourcegraph URL to be configured.
        // Model-override precedence (essential for sub-agent + recycle/escalation callers):
        //   modelOverride ?: agentSettings.anthropicModel ?: AnthropicModelCatalog.defaultModel()
        if (agentSettings.state.llmProvider == "anthropic") {
            val modelId = modelOverride?.takeIf { it.isNotBlank() }
                ?: agentSettings.state.anthropicModel?.takeIf { it.isNotBlank() }
                ?: AnthropicModelCatalog.defaultModel()
            val apiKey = CredentialStore().getToken(ServiceType.ANTHROPIC) ?: ""
            val baseUrl = ConnectionSettings.getInstance().state.anthropicApiUrl
            // Debug dumps gated on writeApiDebugDumps; placed in api-debug/ sub-dir so the
            // dump layout mirrors the Sourcegraph path (sessionDir/api-debug/NNN.request.json).
            val debugDir = sessionDebugDir()
                ?.let { java.io.File(it, "api-debug") }
                ?.takeIf { agentSettings.state.writeApiDebugDumps }
            val http = AnthropicHttpClient(
                baseUrl = baseUrl,
                apiKey = apiKey,
                debugDir = debugDir,
            )
            // Resolve the session attachment store. In production AgentService initialises
            // activeAttachmentStore before createBrain() for the native path, so this lambda
            // returns non-null. The temp-dir fallback is a safety net for tests that do not
            // provide a store (image bytes will simply not resolve, which is acceptable for
            // text-only test scenarios).
            val access = attachmentAccess()
                ?: run {
                    log.debug(
                        "[Agent][BrainFactory] No SessionAttachmentAccess in scope; " +
                            "creating temporary store for native Anthropic brain (text-only safe)"
                    )
                    SessionAttachmentAccess(
                        AttachmentStore(java.nio.file.Files.createTempDirectory("wf-anthropic-nostore-"))
                    )
                }
            val allToolNames = toolNames()
            val allParamNames = paramNames()
            log.info(
                "[Agent] Creating AnthropicDirectBrain: model=$modelId, baseUrl=$baseUrl " +
                    "(tools=${allToolNames.size}, params=${allParamNames.size})"
            )
            return AnthropicDirectBrain(
                modelId = modelId,
                http = http,
                attachmentAccess = access,
                thinkingEnabled = { agentSettings.state.anthropicThinkingEnabled },
                effort = { agentSettings.state.anthropicEffort ?: "high" },
                toolNameSet = allToolNames,
                paramNameSet = allParamNames,
            )
        }

        // ── Sourcegraph path (existing) ──
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val credentialStore = CredentialStore()
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        if (sgUrl.isBlank()) {
            error("No Sourcegraph URL configured. Set one in Settings > AI & Advanced.")
        }

        // Honour the user's saved selection FIRST; auto-pick only on first-launch / blank settings.
        // resolveModel encodes that precedence purely; fetchBest (the expensive ModelCache fetch +
        // pickBest) runs only when neither an override nor a saved selection resolves the model.
        val savedModel = AgentSettings.getInstance(project).state.sourcegraphChatModel
        val resolution = resolveModel(
            modelOverride = modelOverride,
            savedModel = savedModel,
            fetchBest = {
                val client = SourcegraphChatClient(baseUrl = sgUrl, tokenProvider = tokenProvider, model = "")
                val models = try {
                    ModelCache.getModels(client)
                } catch (e: Exception) {
                    log.warn("[Agent] Failed to fetch models from Sourcegraph: ${e.message}")
                    emptyList()
                }
                ModelCache.pickBest(models)?.id
            },
        )

        val modelId = when (resolution) {
            is ModelResolution.Use -> {
                log.info("[Agent] Resolved model: ${resolution.modelId}")
                resolution.modelId
            }
            ModelResolution.FactoryFallback -> {
                // Last resort — try the factory which may have cached models.
                log.warn("[Agent] No models available and no model configured. Trying factory auto-resolution.")
                return try {
                    LlmBrainFactory.create(project)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Cannot start agent: failed to fetch models from Sourcegraph ($sgUrl) " +
                            "and no model is configured in settings. " +
                            "Please check your Sourcegraph URL and token in Settings > AI & Advanced. " +
                            "Error: ${e.message}",
                        e,
                    )
                }
            }
        }

        val allToolNames = toolNames()
        val allParamNames = paramNames()
        log.info(
            "[Agent] Creating brain with model: $modelId at $sgUrl " +
                "(tools=${allToolNames.size}, params=${allParamNames.size})",
        )

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = modelId,
            toolNameSet = allToolNames,
            paramNameSet = allParamNames,
        )
    }

    /** Outcome of [resolveModel]. */
    sealed interface ModelResolution {
        /** Build the brain with [modelId]. */
        data class Use(val modelId: String) : ModelResolution

        /** Nothing configured and auto-pick found nothing — fall back to [LlmBrainFactory]. */
        object FactoryFallback : ModelResolution
    }

    companion object {
        /**
         * Pure model-selection precedence (no I/O): caller override > saved user selection >
         * auto-picked best > factory fallback. [fetchBest] (the expensive ModelCache fetch +
         * pickBest, returning `best.id` or null) is invoked ONLY when neither an override nor a
         * saved selection resolves the model — preserving the 2026-05-06 fix that the user's saved
         * chip selection wins over `pickBest` (which prefers Opus-thinking → ~5x over-billing for a
         * Sonnet user). Pinned by `BrainModelResolutionTest`.
         */
        suspend fun resolveModel(
            modelOverride: String?,
            savedModel: String?,
            fetchBest: suspend () -> String?,
        ): ModelResolution {
            if (!modelOverride.isNullOrBlank()) return ModelResolution.Use(modelOverride)
            if (!savedModel.isNullOrBlank()) return ModelResolution.Use(savedModel)
            val best = fetchBest()
            return if (best != null) ModelResolution.Use(best) else ModelResolution.FactoryFallback
        }
    }
}
