package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [executeRefreshExternalProject].
 *
 * Strategy: all IntelliJ External System APIs (ExternalSystemApiUtil, ExternalSystemUtil)
 * are static and mocked via mockkStatic. The [AgentTool] is mocked via MockK coEvery so
 * requestApproval returns a controlled value. No real IntelliJ services are used.
 *
 * Test 1 — testApprovalDeniedReturnsError:
 *   mock ExternalSystemApiUtil.getAllManagers() → one manager with one fake root
 *   mock tool.requestApproval(...) → ApprovalResult.DENIED
 *   assert isError=true, content contains "denied" (case-insensitive)
 *
 * Test 2 — testNoExternalProjectsReturnsNonError:
 *   mock ExternalSystemApiUtil.getAllManagers() → empty list (or throw)
 *   assert isError=false, content contains "nothing to refresh" (case-insensitive)
 */
class RefreshExternalProjectActionTest {

    private lateinit var project: Project
    private lateinit var tool: AgentTool

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        tool = mockk(relaxed = true)
        // Default: approve (overridden per-test where needed)
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.APPROVED
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Approval denied → isError=true with "denied" in content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testApprovalDeniedReturnsError() = runTest {
        // -- Fake ExternalProjectSettings with one root --
        val fakeSettings = mockk<ExternalProjectSettings>(relaxed = true)
        every { fakeSettings.externalProjectPath } returns "/fake/project"

        val fakeSystemSettings = mockk<AbstractExternalSystemSettings<*, *, *>>(relaxed = true)
        @Suppress("UNCHECKED_CAST")
        every { fakeSystemSettings.linkedProjectsSettings } returns
            setOf(fakeSettings) as Collection<ExternalProjectSettings>

        val fakeManager = mockk<ExternalSystemManager<*, *, *, *, *>>(relaxed = true)

        mockkStatic(ExternalSystemApiUtil::class)
        @Suppress("UNCHECKED_CAST")
        every { ExternalSystemApiUtil.getAllManagers() } returns
            listOf(fakeManager) as List<ExternalSystemManager<*, *, *, *, *>>
        every { ExternalSystemApiUtil.getSettings(project, any()) } returns
            fakeSystemSettings

        // -- Approval denied --
        coEvery {
            tool.requestApproval(any(), any(), any(), any())
        } returns ApprovalResult.DENIED

        val params = buildJsonObject { }
        val result = executeRefreshExternalProject(params, project, tool)

        assertTrue(result.isError, "Expected isError=true when approval is denied, but got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("denied"),
            "Expected 'denied' in content (case-insensitive), got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — No external project roots → isError=false, "nothing to refresh"
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testNoExternalProjectsReturnsNonError() = runTest {
        mockkStatic(ExternalSystemApiUtil::class)
        // Return empty list → no managers registered
        every { ExternalSystemApiUtil.getAllManagers() } returns emptyList()

        val params = buildJsonObject { }
        val result = executeRefreshExternalProject(params, project, tool)

        assertFalse(result.isError, "Expected isError=false when no external roots, but got: ${result.content}")
        assertTrue(
            result.content.lowercase().contains("nothing to refresh"),
            "Expected 'nothing to refresh' in content (case-insensitive), got: ${result.content}"
        )
    }
}
