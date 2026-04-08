package com.workflow.orchestrator.core.autodetect

import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AutoDetectOrchestratorTest {

    @Test
    fun `fillIfEmpty fills blank with detected value`() {
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty("", "detected"))
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty(null, "detected"))
        assertEquals("detected", AutoDetectOrchestrator.fillIfEmpty("   ", "detected"))
    }

    @Test
    fun `fillIfEmpty preserves existing value when detected non-blank`() {
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", "detected"))
    }

    @Test
    fun `fillIfEmpty returns current when detected is null or blank`() {
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", null))
        assertEquals("existing", AutoDetectOrchestrator.fillIfEmpty("existing", ""))
        assertEquals(null, AutoDetectOrchestrator.fillIfEmpty(null, null))
        assertEquals("", AutoDetectOrchestrator.fillIfEmpty("", null))
    }

    @Nested
    inner class ApplyBambooSpecsToStateTests {

        @Test
        fun `fills empty state fields from constants map`() {
            val state = PluginSettings.State()
            val constants = mapOf(
                "DOCKER_TAG_NAME" to "MyServiceDockerTag",
                "PLAN_KEY" to "MYSERVICE"
            )
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

            assertEquals("MyServiceDockerTag", state.dockerTagKey)
            assertEquals("MYSERVICE", state.bambooPlanKey)
            assertTrue(filled.contains("global.dockerTagKey"))
            assertTrue(filled.contains("global.bambooPlanKey"))
        }

        @Test
        fun `does not overwrite user-set state fields`() {
            val state = PluginSettings.State().apply {
                dockerTagKey = "UserSetTag"
                bambooPlanKey = "USERPLAN"
            }
            val constants = mapOf("DOCKER_TAG_NAME" to "DetectedTag", "PLAN_KEY" to "DETECTED")
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.applyBambooSpecsToState(state, constants, "global", filled)

            assertEquals("UserSetTag", state.dockerTagKey)
            assertEquals("USERPLAN", state.bambooPlanKey)
            assertTrue(filled.isEmpty())
        }
    }

    @Nested
    inner class ApplyBambooSpecsToRepoTests {

        @Test
        fun `fills empty repo fields from constants map`() {
            val repo = RepoConfig().apply { name = "service-a" }
            val constants = mapOf(
                "DOCKER_TAG_NAME" to "ServiceADockerTag",
                "PLAN_KEY" to "SERVICEA"
            )
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)

            assertEquals("ServiceADockerTag", repo.dockerTagKey)
            assertEquals("SERVICEA", repo.bambooPlanKey)
            assertTrue(filled.contains("service-a.dockerTagKey"))
            assertTrue(filled.contains("service-a.bambooPlanKey"))
        }

        @Test
        fun `does not overwrite user-set repo fields`() {
            val repo = RepoConfig().apply {
                name = "service-a"
                dockerTagKey = "UserSetTag"
                bambooPlanKey = "USERPLAN"
            }
            val constants = mapOf("DOCKER_TAG_NAME" to "DetectedTag", "PLAN_KEY" to "DETECTED")
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.applyBambooSpecsToRepo(repo, constants, filled)

            assertEquals("UserSetTag", repo.dockerTagKey)
            assertEquals("USERPLAN", repo.bambooPlanKey)
            assertTrue(filled.isEmpty())
        }
    }

    @Nested
    inner class MirrorPrimaryToGlobalTests {

        @Test
        fun `mirrors primary repo values to global state when global is empty`() {
            val state = PluginSettings.State()
            val primary = RepoConfig().apply {
                isPrimary = true
                sonarProjectKey = "primary-sonar"
                bambooPlanKey = "PRIMARY"
                dockerTagKey = "PrimaryDockerTag"
            }
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

            assertEquals("primary-sonar", state.sonarProjectKey)
            assertEquals("PRIMARY", state.bambooPlanKey)
            assertEquals("PrimaryDockerTag", state.dockerTagKey)
        }

        @Test
        fun `does not mirror when global already has values`() {
            val state = PluginSettings.State().apply {
                sonarProjectKey = "global-sonar"
                bambooPlanKey = "GLOBAL"
            }
            val primary = RepoConfig().apply {
                sonarProjectKey = "primary-sonar"
                bambooPlanKey = "PRIMARY"
            }
            val filled = mutableListOf<String>()
            AutoDetectOrchestrator.mirrorPrimaryToGlobal(state, primary, filled)

            assertEquals("global-sonar", state.sonarProjectKey)
            assertEquals("GLOBAL", state.bambooPlanKey)
        }
    }
}
