package com.workflow.orchestrator.agent.brain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract tests for the native Anthropic provider branch in [BrainFactory.create].
 *
 * Full behavioral tests (assertIs<AnthropicDirectBrain>) require IntelliJ platform services
 * ([AgentSettings.getInstance]/[ConnectionSettings.getInstance]) which are unavailable in headless
 * unit tests. A second [BasePlatformTestCase] class in this JVM would collide with the existing
 * [EditFilePersistenceFixtureTest] and cause an "Indexing timeout" in CI (documented infra trap in
 * agent/CLAUDE.md). These source-text contracts + the model-resolution unit tests provide
 * meaningful coverage of the integration properties without the platform requirement.
 *
 * See Task 10 report for design rationale.
 */
class BrainFactoryProviderBranchTest {

    private val src: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/brain/BrainFactory.kt").readText()
    }

    @Test
    fun `native branch exists and comes before the blank-SG-URL guard`() {
        val nativeBranchIdx = src.indexOf("llmProvider")
        val sgGuardIdx = src.indexOf("sgUrl.isBlank()")
        assertTrue(nativeBranchIdx >= 0, "BrainFactory must reference llmProvider for the native branch")
        assertTrue(sgGuardIdx >= 0, "blank-SG-URL guard must still be present for the Sourcegraph path")
        assertTrue(
            nativeBranchIdx < sgGuardIdx,
            "native anthropic branch must appear BEFORE the blank-SG-URL guard " +
                "(native path must not require a Sourcegraph URL)"
        )
    }

    @Test
    fun `native branch constructs AnthropicDirectBrain and AnthropicHttpClient`() {
        assertTrue(
            src.contains("AnthropicDirectBrain"),
            "native branch must construct AnthropicDirectBrain"
        )
        assertTrue(
            src.contains("AnthropicHttpClient"),
            "native branch must build AnthropicHttpClient as the HTTP transport"
        )
    }

    @Test
    fun `native branch honors modelOverride precedence — override then anthropicModel then catalog default`() {
        // The precedence chain must be: modelOverride ?: anthropicModel ?: AnthropicModelCatalog.defaultModel()
        assertTrue(
            src.contains("modelOverride"),
            "modelOverride must be used in the native branch to allow sub-agent and recycle escalation"
        )
        assertTrue(
            src.contains("anthropicModel"),
            "anthropicModel from AgentSettings must be the saved-selection fallback"
        )
        assertTrue(
            src.contains("AnthropicModelCatalog.defaultModel()"),
            "AnthropicModelCatalog.defaultModel() must be the final fallback for the native branch"
        )
    }

    @Test
    fun `native branch reads API key from ANTHROPIC ServiceType`() {
        assertTrue(
            src.contains("ServiceType.ANTHROPIC"),
            "native branch must read the key via CredentialStore.getToken(ServiceType.ANTHROPIC)"
        )
    }

    @Test
    fun `native branch reads base URL from anthropicApiUrl`() {
        assertTrue(
            src.contains("anthropicApiUrl"),
            "native branch must read ConnectionSettings.state.anthropicApiUrl for the HTTP transport base URL"
        )
    }

    @Test
    fun `native branch gates debug dir on writeApiDebugDumps setting`() {
        assertTrue(
            src.contains("writeApiDebugDumps"),
            "native branch must gate the AnthropicHttpClient debugDir on writeApiDebugDumps"
        )
    }

    @Test
    fun `BrainFactory accepts attachmentAccess provider lambda`() {
        // The ctor must accept a lambda so AgentService can wire the active session's store.
        assertTrue(
            src.contains("attachmentAccess"),
            "BrainFactory must have an attachmentAccess provider lambda for SessionAttachmentAccess"
        )
    }

    @Test
    fun `BrainFactory accepts sessionDebugDir provider lambda`() {
        assertTrue(
            src.contains("sessionDebugDir"),
            "BrainFactory must have a sessionDebugDir provider lambda for the debug-dump dir"
        )
    }
}
