package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.delegation.DelegationStatusResult
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tool-level tests for goal G: `delegation(action="status")` includes
 * `retention_expires_in_seconds` for closed-retained handles, omits it for
 * active handles, and omits it for unknown handles.
 *
 * The service is mocked with pre-built [DelegationStatusResult] values so
 * the tool-layer rendering is exercised in isolation.
 */
class DelegationStatusRetentionTtlToolTest {

    private val outbound = mockk<DelegationOutboundService>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = DelegationTool()

    @BeforeEach
    fun setup() {
        installReadActionInlineShim()
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
        every { project.getService(DelegationOutboundService::class.java) } returns outbound
    }

    @AfterEach
    fun tearDown() { unmockkAll() }

    private fun statusParams(handle: String): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("status"))
        put("handle", JsonPrimitive(handle))
    }

    // ── Closed-retained handle: TTL present ───────────────────────────────────

    @Test
    fun `status JSON includes retention_expires_in_seconds for closed-retained handle`() = runBlocking {
        every { outbound.statusOf("h-closed") } returns DelegationStatusResult.Closed(
            lastState = "COMPLETED",
            repoName = "backend",
            closedAtMillis = 0L,
            retentionExpiresInSeconds = 1750L,
        )
        val result = tool.execute(statusParams("h-closed"), project)
        assertFalse(result.isError, result.content)
        assertTrue(
            result.content.contains("\"retention_expires_in_seconds\":1750"),
            "Expected retention_expires_in_seconds in JSON, got: ${result.content}",
        )
    }

    @Test
    fun `status summary mentions retention seconds for closed-retained handle`() = runBlocking {
        every { outbound.statusOf("h-closed-sum") } returns DelegationStatusResult.Closed(
            lastState = "COMPLETED",
            repoName = "myrepo",
            closedAtMillis = 0L,
            retentionExpiresInSeconds = 900L,
        )
        val result = tool.execute(statusParams("h-closed-sum"), project)
        assertFalse(result.isError, result.content)
        assertTrue(
            result.summary.contains("900") || result.content.contains("900"),
            "Expected retention seconds in summary or content, got summary=${result.summary}, content=${result.content}",
        )
    }

    @Test
    fun `status JSON omits retention_expires_in_seconds when service returns null TTL (old entry)`() = runBlocking {
        every { outbound.statusOf("h-old") } returns DelegationStatusResult.Closed(
            lastState = "COMPLETED",
            repoName = "legacy",
            closedAtMillis = 0L,
            retentionExpiresInSeconds = null,
        )
        val result = tool.execute(statusParams("h-old"), project)
        assertFalse(result.isError, result.content)
        assertFalse(
            result.content.contains("retention_expires_in_seconds"),
            "Old entries without TTL should NOT include the field. Got: ${result.content}",
        )
    }

    // ── Active handle: no TTL ─────────────────────────────────────────────────

    @Test
    fun `status JSON does NOT include retention_expires_in_seconds for an active handle`() = runBlocking {
        every { outbound.statusOf("h-active") } returns DelegationStatusResult.Active(
            state = "RUNNING",
            repoName = "frontend",
        )
        val result = tool.execute(statusParams("h-active"), project)
        assertFalse(result.isError, result.content)
        assertFalse(
            result.content.contains("retention_expires_in_seconds"),
            "Active handles must NOT have retention_expires_in_seconds. Got: ${result.content}",
        )
    }

    // ── Unknown handle: error, no TTL ─────────────────────────────────────────

    @Test
    fun `status errors for unknown handle and does not include retention_expires_in_seconds`() = runBlocking {
        every { outbound.statusOf("ghost") } returns DelegationStatusResult.Unknown
        val result = tool.execute(statusParams("ghost"), project)
        assertTrue(result.isError)
        assertFalse(
            result.content.contains("retention_expires_in_seconds"),
            "Unknown handles must NOT have retention_expires_in_seconds. Got: ${result.content}",
        )
    }
}
