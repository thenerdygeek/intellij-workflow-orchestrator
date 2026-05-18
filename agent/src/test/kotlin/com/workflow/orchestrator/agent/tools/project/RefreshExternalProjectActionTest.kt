package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
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
import kotlinx.serialization.json.JsonPrimitive
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

    // ────────────────────────────────────────────────────────────────────────
    // Helper: stub a single Maven (or Gradle) root into ExternalSystemApiUtil
    // ────────────────────────────────────────────────────────────────────────

    private fun stubSingleRoot(systemId: ProjectSystemId, rootPath: String = "/fake/project") {
        val fakeSettings = mockk<ExternalProjectSettings>(relaxed = true)
        every { fakeSettings.externalProjectPath } returns rootPath
        val fakeSystemSettings = mockk<AbstractExternalSystemSettings<*, *, *>>(relaxed = true)
        @Suppress("UNCHECKED_CAST")
        every { fakeSystemSettings.linkedProjectsSettings } returns
            setOf(fakeSettings) as Collection<ExternalProjectSettings>
        val fakeManager = mockk<ExternalSystemManager<*, *, *, *, *>>(relaxed = true)
        every { fakeManager.getSystemId() } returns systemId

        mockkStatic(ExternalSystemApiUtil::class)
        @Suppress("UNCHECKED_CAST")
        every { ExternalSystemApiUtil.getAllManagers() } returns
            listOf(fakeManager) as List<ExternalSystemManager<*, *, *, *, *>>
        every { ExternalSystemApiUtil.getSettings(project, any()) } returns fakeSystemSettings
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — Invalid mode → isError=true with "Unknown mode" + valid-mode list
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testInvalidModeReturnsError() = runTest {
        stubSingleRoot(ProjectSystemId("Maven"))

        val params = buildJsonObject { put("mode", JsonPrimitive("bogus_mode")) }
        val result = executeRefreshExternalProject(params, project, tool)

        assertTrue(result.isError, "Expected isError=true for unknown mode, got: ${result.content}")
        assertTrue(
            result.content.contains("Unknown mode 'bogus_mode'"),
            "Expected 'Unknown mode' diagnostic, got: ${result.content}"
        )
        // Error message should enumerate valid modes so the LLM can recover.
        assertTrue(result.content.contains("reload"), "Expected 'reload' in valid-mode list")
        assertTrue(result.content.contains("generate_sources"), "Expected 'generate_sources' in valid-mode list")
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4 — Non-Maven root + non-reload mode is skipped with a Maven-only warning
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testNonReloadOnGradleIsSkippedWithWarning() = runTest {
        stubSingleRoot(ProjectSystemId("GRADLE", "Gradle"))

        val params = buildJsonObject { put("mode", JsonPrimitive("generate_sources")) }
        val result = executeRefreshExternalProject(params, project, tool)

        // Non-error overall (this is a warning, not a hard failure for mixed builds)
        // and the warning text should call out the Maven-only restriction.
        assertTrue(
            result.content.contains("Maven-only", ignoreCase = true),
            "Expected 'Maven-only' warning for Gradle + non-reload, got: ${result.content}"
        )
        assertTrue(
            result.content.contains("Skipped"),
            "Expected 'Skipped' note for Gradle + non-reload, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5 — Maven generate_sources with no imported Maven projects → warning
    //
    // 2026-05-18 migration: action-ID dispatch (Maven.UpdateAllFolders) was
    // replaced with direct MavenAsyncFacade calls. The equivalent failure
    // surface is "no Maven projects to operate on" — emitted as a non-error
    // warning so mixed-build projects can still partially complete.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testMavenGenerateSourcesNoProjectsProducesWarning() = runTest {
        stubSingleRoot(ProjectSystemId("Maven"))

        val params = buildJsonObject { put("mode", JsonPrimitive("generate_sources")) }
        val result = executeRefreshExternalProject(params, project, tool)

        // Overall non-error so mixed-build projects don't hard-fail.
        assertFalse(result.isError, "Expected non-error result with warning, got: ${result.content}")
        // Warning text mentions the action couldn't run for this Maven setup.
        assertTrue(
            result.content.contains("no Maven projects", ignoreCase = true) ||
                result.content.contains("not available", ignoreCase = true) ||
                result.content.contains("unavailable", ignoreCase = true),
            "Expected diagnostic warning about missing Maven projects, got: ${result.content}"
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6 — module_paths parameter parsing: an empty/unresolvable array
    //          produces an isError=true result with the unresolved paths listed.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun testModulePathsUnresolvedReturnsError() = runTest {
        stubSingleRoot(ProjectSystemId("Maven"))
        every { project.basePath } returns "/fake/project"

        // VFS access requires a live IntelliJ Application — stub LocalFileSystem
        // statically to return null for every io-file lookup so we exercise the
        // "module_paths did not resolve" path without needing the platform.
        mockkStatic(com.intellij.openapi.vfs.LocalFileSystem::class)
        val vfs = mockk<com.intellij.openapi.vfs.LocalFileSystem>(relaxed = true)
        every { com.intellij.openapi.vfs.LocalFileSystem.getInstance() } returns vfs
        every { vfs.findFileByIoFile(any()) } returns null

        val params = buildJsonObject {
            put("mode", JsonPrimitive("reload"))
            put("module_paths", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("nonexistent/pom.xml"))
                add(JsonPrimitive("also/missing/pom.xml"))
            })
        }
        val result = executeRefreshExternalProject(params, project, tool)

        assertTrue(result.isError, "Expected isError=true when module_paths don't resolve")
        assertTrue(
            result.content.contains("nonexistent/pom.xml") && result.content.contains("also/missing/pom.xml"),
            "Expected both unresolved paths listed in the error, got: ${result.content}"
        )
    }
}
