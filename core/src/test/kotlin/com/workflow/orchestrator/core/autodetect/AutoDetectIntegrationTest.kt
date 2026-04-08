package com.workflow.orchestrator.core.autodetect

import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure integration test against the fixture project tree. Does NOT spin up an
 * IntelliJ project — just exercises the parts of the orchestrator that are
 * project-independent (BambooSpecsParser + companion helpers).
 *
 * Full project-aware integration testing is covered manually in runIde.
 */
class AutoDetectIntegrationTest {

    private fun findFixturePath(relativePath: String): Path {
        // Start from current directory and search upward for core/src/test/testData
        var current = File(".").absoluteFile
        while (current != null) {
            val candidate = File(current, "core/src/test/testData/$relativePath")
            if (candidate.exists()) {
                return candidate.toPath()
            }
            current = current.parentFile
        }
        // Fallback to direct path
        return Paths.get("core/src/test/testData/$relativePath")
    }

    private val fixtureRoot: Path
        get() = findFixturePath("auto-detect-project")

    @Test
    fun `bamboo-specs parser extracts all expected constants from fixture`() {
        val constants = BambooSpecsParser.parseConstants(fixtureRoot)

        assertEquals("my-sample-service", constants["REPOSITORY_NAME"])
        assertEquals("MYSAMPLESERVICE", constants["PLAN_KEY"])
        assertEquals("MySampleServiceDockerTag", constants["DOCKER_TAG_NAME"])
        assertEquals("MYPROJ", constants["GIT_PROJECT_ID"])
    }

    @Test
    fun `applyBambooSpecsToState fills all empty fields from single-repo fixture`() {
        val constants = BambooSpecsParser.parseConstants(fixtureRoot)
        val state = PluginSettings.State()
        val filled = mutableListOf<String>()

        AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

        assertEquals("MySampleServiceDockerTag", state.dockerTagKey)
        assertEquals("MYSAMPLESERVICE", state.bambooPlanKey)
        assertTrue(filled.contains("global.dockerTagKey"))
        assertTrue(filled.contains("global.bambooPlanKey"))
    }

    @Test
    fun `multi-repo fixture fills each repo with its own constants`() {
        val multiRoot = findFixturePath("auto-detect-multi-repo")
        val repoA = RepoConfig().apply {
            name = "service-a"
            localVcsRootPath = multiRoot.resolve("service-a").toString()
        }
        val repoB = RepoConfig().apply {
            name = "service-b"
            localVcsRootPath = multiRoot.resolve("service-b").toString()
        }
        val filled = mutableListOf<String>()

        for (repo in listOf(repoA, repoB)) {
            val constants = BambooSpecsParser.parseConstants(Paths.get(repo.localVcsRootPath ?: ""))
            AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)
        }

        assertEquals("ServiceADockerTag", repoA.dockerTagKey)
        assertEquals("SERVICEA", repoA.bambooPlanKey)
        assertEquals("ServiceBDockerTag", repoB.dockerTagKey)
        assertEquals("SERVICEB", repoB.bambooPlanKey)
        assertTrue(filled.contains("service-a.dockerTagKey"))
        assertTrue(filled.contains("service-a.bambooPlanKey"))
        assertTrue(filled.contains("service-b.dockerTagKey"))
        assertTrue(filled.contains("service-b.bambooPlanKey"))
    }

    @Test
    fun `multi-repo mirror copies primary repo values to global state`() {
        val state = PluginSettings.State()
        val primary = RepoConfig().apply {
            name = "service-a"
            isPrimary = true
            sonarProjectKey = "service-a"
            bambooPlanKey = "SERVICEA"
            dockerTagKey = "ServiceADockerTag"
        }
        val filled = mutableListOf<String>()

        AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

        assertEquals("service-a", state.sonarProjectKey)
        assertEquals("SERVICEA", state.bambooPlanKey)
        assertEquals("ServiceADockerTag", state.dockerTagKey)
    }
}
