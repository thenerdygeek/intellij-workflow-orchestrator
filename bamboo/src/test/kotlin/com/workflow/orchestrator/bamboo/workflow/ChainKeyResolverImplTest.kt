package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase A — verifies [ChainKeyResolverImpl] resolves a `(parentPlanKey, branchName)` pair
 * via Bamboo's `/plan/{parent}/branch` endpoint, matched on `shortName` (the field that
 * actually carries the git branch name; the legacy `name`-based comparison silently
 * mismatched and substituted the master plan).
 *
 * **No master fallback** — when no branch chain matches, returns null so the cascade
 * leaves `focusBuild` null instead of substituting the master chain's latest build.
 */
class ChainKeyResolverImplTest {

    private lateinit var server: MockWebServer
    private lateinit var realClient: BambooApiClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        realClient = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Builds a relaxed [Project] mock with `BambooServiceImpl` stubbed via `getService`.
     * The impl no longer touches `PluginSettings` (the `resolveBranchKeyOrNull` path
     * doesn't need it), so we don't stub that here.
     */
    private fun makeProject(client: BambooApiClient?): Project {
        val project = mockk<Project>(relaxed = true)
        if (client == null) {
            every { project.getService(BambooServiceImpl::class.java) } returns null
        } else {
            val bambooService = mockk<BambooServiceImpl>()
            every { project.getService(BambooServiceImpl::class.java) } returns bambooService
            every { bambooService.client } returns client
        }
        return project
    }

    @Test
    fun `returns chain key when branch shortName matches`() = runTest {
        // plan-branches.json has a branch with shortName "feature/PROJ-123-add-login" → key "PROJ-BUILD-7"
        server.enqueue(MockResponse().setBody(fixture("plan-branches.json")))
        val project = makeProject(realClient)

        val key = ChainKeyResolverImpl().resolveChainKey(
            project,
            parentPlanKey = "PROJ-BUILD",
            branchName = "feature/PROJ-123-add-login",
        )

        assertEquals("PROJ-BUILD-7", key)
    }

    @Test
    fun `returns null when no branch matches the git branch name - no master fallback`() = runTest {
        // plan-branches.json's three branches do NOT include "feature/never-built".
        server.enqueue(MockResponse().setBody(fixture("plan-branches.json")))
        val project = makeProject(realClient)

        val key = ChainKeyResolverImpl().resolveChainKey(
            project,
            parentPlanKey = "PROJ-BUILD",
            branchName = "feature/never-built",
        )

        // Crucially null — NOT the parent plan key. Phase A's whole point: no master substitution.
        assertNull(key)
    }

    @Test
    fun `returns null when BambooServiceImpl is unavailable`() = runTest {
        val project = makeProject(client = null)

        val key = ChainKeyResolverImpl().resolveChainKey(
            project,
            parentPlanKey = "PROJ-BUILD",
            branchName = "feature/anything",
        )

        assertNull(key)
    }

    @Test
    fun `returns null when bamboo client is unavailable - bamboo not configured`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()
        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns null

        val key = ChainKeyResolverImpl().resolveChainKey(
            project,
            parentPlanKey = "PROJ-BUILD",
            branchName = "feature/anything",
        )

        assertNull(key)
    }

    @Test
    fun `returns null on blank inputs without invoking the API`() = runTest {
        val project = makeProject(realClient)

        assertNull(ChainKeyResolverImpl().resolveChainKey(project, "", "feature/x"))
        assertNull(ChainKeyResolverImpl().resolveChainKey(project, "PROJ-BUILD", ""))
        // No request was enqueued — confirms the impl short-circuited.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null when parent already looks like a branch chain key`() = runTest {
        val project = makeProject(realClient)

        // Parent key like `PROJ-PLAN-7` is itself a branch chain — caller passed wrong input.
        val key = ChainKeyResolverImpl().resolveChainKey(
            project,
            parentPlanKey = "PROJ-BUILD-7",
            branchName = "feature/anything",
        )

        assertNull(key)
        assertEquals(0, server.requestCount)
    }
}
