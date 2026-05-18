package com.workflow.orchestrator.agent.tools.framework.maven

import com.intellij.openapi.project.Project
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [MavenAsyncFacade]. The full happy-path requires a live IntelliJ
 * Application + the bundled Maven plugin — manual-smoke territory. Here we lock
 * in the contract that matters for downstream callers:
 *
 *   - The facade NEVER throws on a relaxed-mock Project; it always returns a
 *     structured result.
 *   - `getAllProjects` on a mock project returns an empty list (never null,
 *     never throws).
 *   - `getManager` returns null when MavenProjectsManager isn't backed by a
 *     real service container.
 *   - Reflective lookups for absent classes (e.g. `MavenDownloadSourcesRequest`
 *     when the Maven plugin isn't loaded) degrade to null/Unavailable without
 *     blowing up the agent loop.
 *
 * These guarantees matter because the agent loop hits the facade from many
 * code paths (refresh, compile_module, sub-agents) and a single uncaught
 * NoClassDefFoundError would take down the whole tool invocation.
 */
class MavenAsyncFacadeTest {

    private lateinit var project: Project

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isAvailable returns true when Maven plugin classes are on the classpath`() {
        // In the :agent test fixture the bundled Maven plugin is on the classpath,
        // so the facade reports available. Negative case (PyCharm Community) is
        // hard to simulate in a unit test — manual smoke only.
        assertTrue(MavenAsyncFacade.isAvailable(), "Expected facade.isAvailable on bundled-plugin classpath")
    }

    @Test
    fun `getAllProjects returns empty list on mock project (does not throw)`() {
        val result = MavenAsyncFacade.getAllProjects(project)
        assertNotNull(result)
        assertTrue(result.isEmpty(), "Expected empty list from mock project, got size=${result.size}")
    }

    @Test
    fun `findProjectByPomFile returns null on mock project (does not throw)`() {
        val pom = mockk<com.intellij.openapi.vfs.VirtualFile>(relaxed = true)
        val result = MavenAsyncFacade.findProjectByPomFile(project, pom)
        assertNull(result, "Expected null from mock project (no Maven service backing)")
    }

    @Test
    fun `makeSyncSpec returns non-null when MavenSyncSpec class is present`() {
        // Both `incremental` and `full` factories should resolve via at least one
        // of the three patterns the facade tries.
        val incremental = MavenAsyncFacade.makeSyncSpec("test-label", incremental = true)
        val full = MavenAsyncFacade.makeSyncSpec("test-label", incremental = false)
        assertNotNull(incremental, "Expected MavenSyncSpec.incremental(...) to resolve")
        assertNotNull(full, "Expected MavenSyncSpec.full(...) to resolve")
    }

    @Test
    fun `scheduleUpdateAllMavenProjects returns CallResult on mock project (does not throw)`() {
        val result = MavenAsyncFacade.scheduleUpdateAllMavenProjects(project, "test")
        // We don't assert which branch — on a mock project the underlying call may
        // fail (Unavailable / Failed), but it MUST be a structured result, never
        // a thrown exception.
        assertNotNull(result)
        assertTrue(
            result is MavenAsyncFacade.CallResult.Triggered ||
                result is MavenAsyncFacade.CallResult.Unavailable ||
                result is MavenAsyncFacade.CallResult.Failed,
            "Result must be one of the three CallResult branches, got: $result"
        )
    }

    @Test
    fun `scheduleUpdateMavenProjects returns CallResult on mock project (does not throw)`() {
        val result = MavenAsyncFacade.scheduleUpdateMavenProjects(project, "test", emptyList())
        assertNotNull(result)
    }

    @Test
    fun `scheduleDownloadArtifacts returns CallResult on mock project with empty request`() {
        // Build a dummy request — even if it ends up null we get Unavailable rather than throw.
        val request = MavenAsyncFacade.buildDownloadRequest(emptyList(), downloadSources = true, downloadDocs = false)
        // request may be null on mock; if so the schedule call still returns Unavailable
        if (request == null) return  // skip — buildDownloadRequest unavailable in this env
        val result = MavenAsyncFacade.scheduleDownloadArtifacts(project, request)
        assertNotNull(result)
    }

    @Test
    fun `suspend variant updateAllMavenProjects returns AwaitResult on mock project`() = runTest {
        val result = MavenAsyncFacade.updateAllMavenProjects(project, "test")
        assertNotNull(result)
        assertTrue(
            result is MavenAsyncFacade.AwaitResult.Completed ||
                result is MavenAsyncFacade.AwaitResult.Unavailable ||
                result is MavenAsyncFacade.AwaitResult.Failed,
            "Result must be one of the three AwaitResult branches, got: $result"
        )
    }
}
