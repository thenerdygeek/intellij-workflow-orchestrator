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
import kotlinx.serialization.json.JsonPrimitive
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

    // ── resolveTargetStatus: doorbell-aware status (Bug A) ─────────────────────

    @Test
    fun `status is missing when the path does not exist`() {
        assertEquals("missing", DelegationTool.resolveTargetStatus(exists = false, delegationReachable = false, doorbellReachable = false))
    }

    @Test
    fun `status is running when the delegation socket is reachable`() {
        assertEquals("running", DelegationTool.resolveTargetStatus(exists = true, delegationReachable = true, doorbellReachable = false))
    }

    @Test
    fun `status is available when only the doorbell is reachable (running, inbound off)`() {
        // The reported bug: an open IDE with inbound delegation OFF has its delegation socket
        // unbound but its doorbell always bound. It must NOT be reported as "closed" (= dead) —
        // a send can ring the doorbell and prompt the user for consent.
        assertEquals("available", DelegationTool.resolveTargetStatus(exists = true, delegationReachable = false, doorbellReachable = true))
    }

    @Test
    fun `status is closed when neither socket is reachable but the path exists`() {
        assertEquals("closed", DelegationTool.resolveTargetStatus(exists = true, delegationReachable = false, doorbellReachable = false))
    }

    private fun listTargetsParams(): JsonObject = buildJsonObject {
        put("action", JsonPrimitive("list_targets"))
    }

    @Test
    fun `returns empty targets array when no recents and no discovered`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = { emptyList() },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
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

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/running",
                        repoName = "running-app",
                        status = "running",
                        lastOpened = 1_700_000_000_000L,
                    ),
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/closed",
                        repoName = "closed-app",
                        status = "closed",
                        lastOpened = 1_700_000_100_000L,
                    ),
                )
            },
            discoveredProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/discovered",
                        repoName = "discovered-app",
                        status = "discovered",
                        lastOpened = null,
                    )
                )
            },
        )
        val result = tool.execute(listTargetsParams(), project)
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

        val tool = DelegationTool(
            recentsProvider = { emptyList() },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertTrue(result.isError)
        assertTrue(result.summary.contains("DelegationOutboundDisabled"))
    }

    @Test
    fun `discovered entries that overlap with recents are deduplicated`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val sharedPath = "/repo/overlap"
        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = sharedPath,
                        repoName = "overlap-app",
                        status = "running",
                        lastOpened = null,
                    )
                )
            },
            discoveredProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = sharedPath,
                        repoName = "overlap-app",
                        status = "discovered",
                        lastOpened = null,
                    )
                )
            },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        // Should appear once (from recents), not twice
        val countRunning = result.summary.split("\"status\":\"running\"").size - 1
        val countDiscovered = result.summary.split("\"status\":\"discovered\"").size - 1
        assertEquals(1, countRunning, "running-status overlap entry should appear once")
        assertEquals(0, countDiscovered, "discovered duplicate should be filtered out")
    }

    // ── advisory busy hint: status_label + machine busy field (feedback .23 #2) ──

    @Test
    fun `running target with busy true renders running (busy) label and busy true field`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/busy",
                        repoName = "busy-app",
                        status = "running",
                        lastOpened = null,
                        busy = true,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        assertTrue(result.summary.contains("\"status_label\":\"running (busy)\""), result.summary)
        assertTrue(result.summary.contains("\"busy\":true"), result.summary)
    }

    @Test
    fun `running target with busy false renders running (idle) label and busy false field`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/idle",
                        repoName = "idle-app",
                        status = "running",
                        lastOpened = null,
                        busy = false,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        assertTrue(result.summary.contains("\"status_label\":\"running (idle)\""), result.summary)
        assertTrue(result.summary.contains("\"busy\":false"), result.summary)
    }

    @Test
    fun `running target with null busy renders plain running label and busy null (old peer)`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/legacy",
                        repoName = "legacy-app",
                        status = "running",
                        lastOpened = null,
                        busy = null,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        assertTrue(result.summary.contains("\"status_label\":\"running\""), result.summary)
        // The plain label must NOT be the busy/idle variant.
        assertFalse(result.summary.contains("running (busy)"))
        assertFalse(result.summary.contains("running (idle)"))
        assertTrue(result.summary.contains("\"busy\":null"), result.summary)
    }

    @Test
    fun `non-running statuses keep their plain label regardless of busy`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/avail",
                        repoName = "avail-app",
                        status = "available",
                        lastOpened = null,
                        busy = null,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        assertTrue(result.summary.contains("\"status_label\":\"available\""), result.summary)
    }

    @Test
    fun `null lastOpened is serialised as null not the string null`() = runBlocking {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/proj"

        val tool = DelegationTool(
            recentsProvider = {
                listOf(
                    DelegationTool.RecentEntry(
                        projectPath = "/repo/a",
                        repoName = "app-a",
                        status = "closed",
                        lastOpened = null,
                    )
                )
            },
            discoveredProvider = { emptyList() },
        )
        val result = tool.execute(listTargetsParams(), project)
        assertFalse(result.isError)
        assertTrue(
            result.summary.contains("\"lastOpened\":null"),
            "null lastOpened should serialize as JSON null, got: ${result.summary}",
        )
    }
}
