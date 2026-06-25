package com.workflow.orchestrator.core.config

import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * Pins the plugin-split resolution contract `WorkflowConfig.resolve()` relies on:
 *  - the lowest-[order] provider wins regardless of its position in the EP list, so a fork's
 *    order=0 override beats the base DefaultWorkflowConfig (order=Int.MAX_VALUE);
 *  - with no platform extension-point system present (plain unit test), [WorkflowConfig.resolve]
 *    degrades to a fresh DefaultWorkflowConfig via its runCatching fallback.
 * Mirrors WorkflowConfigOverrideTest's anonymous-provider style.
 */
class WorkflowConfigResolutionTest {

    /** order=0 override returning a distinct JIRA url so we can tell which provider won. */
    private val override = object : WorkflowConfig {
        override val order: Int get() = 0
        override fun baseUrl(service: ServiceType): String =
            if (service == ServiceType.JIRA) "https://jira.override.example/" else ""
    }

    /** The base impl at the lowest priority (order=Int.MAX_VALUE). */
    private val default = DefaultWorkflowConfig {
        ConnectionSettings.State(jiraUrl = "https://jira.default.example/")
    }

    @Test
    fun `order-0 override beats DefaultWorkflowConfig when override is listed last`() {
        val winner = WorkflowConfig.lowestOrderOf(listOf(default, override))
        assertEquals("https://jira.override.example/", winner!!.baseUrl(ServiceType.JIRA))
    }

    @Test
    fun `order-0 override beats DefaultWorkflowConfig when override is listed first`() {
        // Same providers, reversed order — position must not change the winner.
        val winner = WorkflowConfig.lowestOrderOf(listOf(override, default))
        assertEquals("https://jira.override.example/", winner!!.baseUrl(ServiceType.JIRA))
    }

    @Test
    fun `resolve falls back to DefaultWorkflowConfig with no platform EP system`() {
        // No Application / extension-point registry in a plain unit test — the runCatching guard in
        // resolve() must degrade to a fresh DefaultWorkflowConfig instead of throwing.
        assertIs<DefaultWorkflowConfig>(WorkflowConfig.resolve())
    }
}
