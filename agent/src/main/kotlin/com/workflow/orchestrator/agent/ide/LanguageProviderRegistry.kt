package com.workflow.orchestrator.agent.ide

import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the appropriate [LanguageIntelligenceProvider] for a given file or language.
 * Thread-safe — providers can be registered from any thread.
 */
class LanguageProviderRegistry {

    private val providers = ConcurrentHashMap<String, LanguageIntelligenceProvider>()

    fun register(provider: LanguageIntelligenceProvider) {
        for (langId in provider.supportedLanguageIds) {
            providers[langId] = provider
        }
    }

    fun forFile(file: PsiFile): LanguageIntelligenceProvider? =
        providers[file.language.id]

    fun forLanguageId(languageId: String): LanguageIntelligenceProvider? =
        providers[languageId]

    fun hasProvider(languageId: String): Boolean =
        providers.containsKey(languageId)

    /** Return all distinct registered providers (deduplicated since one provider may handle multiple language IDs). */
    fun allProviders(): List<LanguageIntelligenceProvider> =
        providers.values.distinct()
}
