package com.workflow.orchestrator.mockserver.bamboo

import java.util.concurrent.ConcurrentHashMap

object BambooDataFactory {

    fun createDefaultState(): BambooState {
        val state = BambooState()
        state.plans = mutableListOf(
            BambooPlan(
                key = "PROJ-BUILD",
                shortName = "Build",
                name = "Project - Artifact Build",
                branches = listOf(
                    BambooBranch("PROJ-BUILD0", "main"),
                    BambooBranch("PROJ-BUILD1", "feature/PROJ-101"),
                ),
                variables = mapOf("dockerTagsAsJson" to "{}", "skipTests" to "false"),
            ),
            BambooPlan(
                key = "PROJ-TEST",
                shortName = "Test",
                name = "Project - Integration Tests",
                branches = listOf(BambooBranch("PROJ-TEST0", "main")),
            ),
            BambooPlan(
                key = "PROJ-SONAR",
                shortName = "Sonar",
                name = "Project - SonarQube Analysis",
            ),
        )

        val builds = ConcurrentHashMap<String, BambooBuildResult>()
        builds["PROJ-BUILD-99"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-99",
            planKey = "PROJ-BUILD",
            buildNumber = 99,
            lifeCycleState = "Finished",
            state = "Successful",
            stages = listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Successful", "Finished"),
                BambooStage("Integration Tests", "Successful", "Finished"),
            ),
            logEntries = listOf("[INFO] BUILD SUCCESS"),
        )
        builds["PROJ-TEST-50"] = BambooBuildResult(
            buildResultKey = "PROJ-TEST-50",
            planKey = "PROJ-TEST",
            buildNumber = 50,
            lifeCycleState = "Running",  // Divergent: "Running" not "InProgress"
            state = null,
        )
        builds["PROJ-SONAR-25"] = BambooBuildResult(
            buildResultKey = "PROJ-SONAR-25",
            planKey = "PROJ-SONAR",
            buildNumber = 25,
            lifeCycleState = "Finished",
            state = "PartiallySuccessful",  // Divergent: new state
            stages = listOf(
                BambooStage("Compile & Package", "Successful", "Finished"),
                BambooStage("Security Scan", "Failed", "Finished"),
            ),
        )
        state.builds = builds
        return state
    }

    fun createAllFailingState(): BambooState {
        val state = createDefaultState()
        state.builds.replaceAll { _, build ->
            build.copy(lifeCycleState = "Finished", state = "Failed")
        }
        return state
    }

    fun createBuildProgressionState(): BambooState {
        val state = createDefaultState()
        state.triggerBuild("PROJ-BUILD")
        state.triggerBuild("PROJ-TEST")
        state.triggerBuild("PROJ-SONAR")
        return state
    }

    fun createHappyPathState(): BambooState {
        val state = BambooState()
        state.plans = mutableListOf(
            BambooPlan("PROJ-BUILD", "Build", "Project - Artifact Build"),
        )
        val builds = ConcurrentHashMap<String, BambooBuildResult>()
        builds["PROJ-BUILD-99"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-99", planKey = "PROJ-BUILD", buildNumber = 99,
            lifeCycleState = "InProgress",  // Standard — plugin expects this
            state = null,
        )
        builds["PROJ-BUILD-98"] = BambooBuildResult(
            buildResultKey = "PROJ-BUILD-98", planKey = "PROJ-BUILD", buildNumber = 98,
            lifeCycleState = "Finished", state = "Successful",
        )
        state.builds = builds
        return state
    }
}
