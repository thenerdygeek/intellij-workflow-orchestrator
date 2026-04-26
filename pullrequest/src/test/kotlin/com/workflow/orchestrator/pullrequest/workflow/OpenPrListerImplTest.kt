package com.workflow.orchestrator.pullrequest.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketPrDetail
import com.workflow.orchestrator.core.bitbucket.BitbucketPrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.pullrequest.service.PrListService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 Task 3 — verifies [OpenPrListerImpl] correctly bridges
 * [PrListService.allRepoPrs] (List<BitbucketPrDetail>) into the cross-module
 * [com.workflow.orchestrator.core.model.workflow.PrRef] DTO consumed by
 * `WorkflowContextService` and the legacy `PrSelected` event mirror.
 */
class OpenPrListerImplTest {

    private fun makePrDetail(
        id: Int,
        fromBranch: String,
        toBranch: String,
        repoName: String,
    ): BitbucketPrDetail {
        val pr = BitbucketPrDetail(
            id = id,
            title = "Test PR $id",
            state = "OPEN",
            fromRef = BitbucketPrRef(id = "refs/heads/$fromBranch", displayId = fromBranch),
            toRef = BitbucketPrRef(id = "refs/heads/$toBranch", displayId = toBranch),
        )
        pr.repoName = repoName
        return pr
    }

    private fun makeRepoConfig(
        name: String,
        bambooPlanKey: String? = null,
        sonarProjectKey: String? = null,
    ): RepoConfig = RepoConfig().also {
        it.name = name
        it.bitbucketProjectKey = "PROJ"
        it.bitbucketRepoSlug = name
        it.bambooPlanKey = bambooPlanKey ?: ""
        it.sonarProjectKey = sonarProjectKey ?: ""
    }

    @Test
    fun `maps single pr with matching repo to PrRef populating bamboo and sonar keys`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()

        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings

        val pr = makePrDetail(id = 42, fromBranch = "feature/x", toBranch = "develop", repoName = "alpha")
        every { prListService.allRepoPrs } returns MutableStateFlow(listOf(pr))
        every { settings.getRepos() } returns listOf(
            makeRepoConfig(name = "alpha", bambooPlanKey = "PLAN-ALPHA", sonarProjectKey = "sonar:alpha"),
        )

        val refs = OpenPrListerImpl().listOpenPrs(project)

        assertEquals(1, refs.size)
        val ref = refs.single()
        assertEquals(42, ref.prId)
        assertEquals("feature/x", ref.fromBranch)
        assertEquals("develop", ref.toBranch)
        assertEquals("alpha", ref.repoName)
        assertEquals("PLAN-ALPHA", ref.bambooPlanKey)
        assertEquals("sonar:alpha", ref.sonarProjectKey)
    }

    @Test
    fun `unconfigured repo keys collapse to null on PrRef`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()

        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings

        val pr = makePrDetail(id = 7, fromBranch = "bugfix/y", toBranch = "main", repoName = "beta")
        every { prListService.allRepoPrs } returns MutableStateFlow(listOf(pr))
        // Repo present but bamboo + sonar are blank → must be null on PrRef.
        every { settings.getRepos() } returns listOf(makeRepoConfig(name = "beta"))

        val refs = OpenPrListerImpl().listOpenPrs(project)

        assertEquals(1, refs.size)
        val ref = refs.single()
        assertEquals(7, ref.prId)
        assertEquals("bugfix/y", ref.fromBranch)
        assertEquals("main", ref.toBranch)
        assertEquals("beta", ref.repoName)
        assertNull(ref.bambooPlanKey)
        assertNull(ref.sonarProjectKey)
    }

    @Test
    fun `pr with unknown repoName still maps but with null keys`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()

        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings

        val pr = makePrDetail(id = 9, fromBranch = "feature/z", toBranch = "develop", repoName = "ghost")
        every { prListService.allRepoPrs } returns MutableStateFlow(listOf(pr))
        every { settings.getRepos() } returns listOf(
            makeRepoConfig(name = "alpha", bambooPlanKey = "PLAN-ALPHA", sonarProjectKey = "sonar:alpha"),
        )

        val refs = OpenPrListerImpl().listOpenPrs(project)

        assertEquals(1, refs.size)
        val ref = refs.single()
        assertEquals(9, ref.prId)
        assertEquals("ghost", ref.repoName)
        assertNull(ref.bambooPlanKey)
        assertNull(ref.sonarProjectKey)
    }

    @Test
    fun `pr with null fromRef or toRef is skipped`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()

        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings

        val noFromRef = BitbucketPrDetail(
            id = 1,
            title = "no fromRef",
            state = "OPEN",
            fromRef = null,
            toRef = BitbucketPrRef(displayId = "develop"),
        ).also { it.repoName = "alpha" }
        val noToRef = BitbucketPrDetail(
            id = 2,
            title = "no toRef",
            state = "OPEN",
            fromRef = BitbucketPrRef(displayId = "feature/q"),
            toRef = null,
        ).also { it.repoName = "alpha" }
        val ok = makePrDetail(id = 3, fromBranch = "feature/r", toBranch = "develop", repoName = "alpha")

        every { prListService.allRepoPrs } returns MutableStateFlow(listOf(noFromRef, noToRef, ok))
        every { settings.getRepos() } returns listOf(makeRepoConfig(name = "alpha"))

        val refs = OpenPrListerImpl().listOpenPrs(project)

        assertEquals(1, refs.size)
        assertEquals(3, refs.single().prId)
    }

    @Test
    fun `empty allRepoPrs yields empty list`() {
        val project = mockk<Project>(relaxed = true)
        val prListService = mockk<PrListService>()
        val settings = mockk<PluginSettings>()

        every { project.getService(PrListService::class.java) } returns prListService
        every { project.getService(PluginSettings::class.java) } returns settings
        every { prListService.allRepoPrs } returns MutableStateFlow(emptyList())
        every { settings.getRepos() } returns emptyList()

        val refs = OpenPrListerImpl().listOpenPrs(project)
        assertTrue(refs.isEmpty())
    }
}
