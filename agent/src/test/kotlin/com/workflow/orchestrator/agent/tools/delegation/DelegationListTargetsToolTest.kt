package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.testutil.installReadActionInlineShim
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DelegationListTargetsToolTest {

    @BeforeEach fun setup() {
        installReadActionInlineShim()
        // Gate must be on so the tool's runtime check passes.
        mockkObject(PluginSettings.Companion)
        val settings = mockk<PluginSettings>(relaxed = true)
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns true
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings
    }

    @AfterEach fun tearDown() { unmockkAll() }

    @Test
    fun `returns empty targets array when no recents and no discovered`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationListTargetsTool(
            recentsProvider = { emptyList() },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(buildJsonObject {} as JsonObject, project)
        assertFalse(result.isError, "expected success: ${result.summary}")
        assertTrue(
            result.summary.contains("\"targets\":[]") || result.summary.contains("\"targets\": []"),
            "expected empty targets array; got: ${result.summary}",
        )
    }

    @Test
    fun `returns targets with mixed statuses`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationListTargetsTool(
            recentsProvider = {
                listOf(
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = "/repo/running",
                        repoName = "running-app",
                        status = "running",
                        lastOpened = 1_700_000_000_000L,
                    ),
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = "/repo/closed",
                        repoName = "closed-app",
                        status = "closed",
                        lastOpened = 1_700_000_100_000L,
                    ),
                )
            },
            discoveredProvider = {
                listOf(
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = "/repo/discovered",
                        repoName = "discovered-app",
                        status = "discovered",
                        lastOpened = null,
                    )
                )
            },
        )
        val result = tool.execute(buildJsonObject {} as JsonObject, project)
        assertFalse(result.isError)
        assertTrue(result.summary.contains("\"repoName\":\"running-app\""))
        assertTrue(result.summary.contains("\"status\":\"running\""))
        assertTrue(result.summary.contains("\"status\":\"closed\""))
        assertTrue(result.summary.contains("\"status\":\"discovered\""))
    }

    @Test
    fun `respects outbound gate — returns DelegationOutboundDisabled when off`() = runBlocking {
        val state = mockk<PluginSettings.State>(relaxed = true)
        every { state.enableOutboundCrossIdeDelegation } returns false
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state } returns state
        every { PluginSettings.getInstance(any()) } returns settings

        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationListTargetsTool(
            recentsProvider = { emptyList() },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(buildJsonObject {} as JsonObject, project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("DelegationOutboundDisabled"))
    }

    @Test
    fun `discovered entries that overlap with recents are deduplicated`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val sharedPath = "/repo/overlap"
        val tool = DelegationListTargetsTool(
            recentsProvider = {
                listOf(
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = sharedPath,
                        repoName = "overlap-app",
                        status = "running",
                        lastOpened = null,
                    )
                )
            },
            discoveredProvider = {
                listOf(
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = sharedPath,
                        repoName = "overlap-app",
                        status = "discovered",
                        lastOpened = null,
                    )
                )
            },
        )
        val result = tool.execute(buildJsonObject {} as JsonObject, project)
        assertFalse(result.isError)
        // Should appear once (from recents), not twice
        val countRunning = result.summary.split("\"status\":\"running\"").size - 1
        val countDiscovered = result.summary.split("\"status\":\"discovered\"").size - 1
        assertEquals(1, countRunning, "running-status overlap entry should appear once")
        assertEquals(0, countDiscovered, "discovered duplicate should be filtered out")
    }

    @Test
    fun `null lastOpened is serialised as null not the string null`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationListTargetsTool(
            recentsProvider = {
                listOf(
                    DelegationListTargetsTool.RecentEntry(
                        projectPath = "/repo/a",
                        repoName = "app-a",
                        status = "closed",
                        lastOpened = null,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(buildJsonObject {} as JsonObject, project)
        assertFalse(result.isError)
        assertTrue(
            result.summary.contains("\"lastOpened\":null"),
            "null lastOpened should serialize as JSON null, got: ${result.summary}",
        )
    }
}
