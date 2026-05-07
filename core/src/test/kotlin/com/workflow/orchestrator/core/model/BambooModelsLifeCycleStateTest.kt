package com.workflow.orchestrator.core.model

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A-P1-1 regression test: BuildResultData.lifeCycleState field is exposed and survives
 * serialisation round-trips.
 *
 * The Bamboo probe bundle-repo.unpacked/raw/result_full.json reports:
 *   state: "Successful", lifeCycleState: "Finished"
 * Both fields must be independently accessible so consumers can use the richer
 * lifecycle signal (e.g. "NotBuilt" is terminal; "InProgress" is not) without
 * relying on the lossy state.ifBlank{lifeCycleState} collapse.
 */
class BambooModelsLifeCycleStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `BuildResultData exposes lifeCycleState field`() {
        val data = BuildResultData(
            planKey = "FXZV-A0CL",
            buildNumber = 390,
            state = "Successful",
            durationSeconds = 473,
            buildResultKey = "FXZV-A0CLVIKGLYVHCUI-ULVZ-390",
            lifeCycleState = "Finished"
        )

        assertEquals("Successful", data.state)
        assertEquals("Finished", data.lifeCycleState)
    }

    @Test
    fun `BuildResultData serialises and deserialises lifeCycleState`() {
        val original = BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = 42,
            state = "Unknown",
            durationSeconds = 0,
            buildResultKey = "PROJ-AUTO-42",
            lifeCycleState = "NotBuilt"
        )

        val serialised = json.encodeToString(original)
        val restored = json.decodeFromString<BuildResultData>(serialised)

        assertEquals("Unknown", restored.state)
        assertEquals("NotBuilt", restored.lifeCycleState)
    }

    @Test
    fun `BuildResultData lifeCycleState defaults to empty string when absent`() {
        val data = BuildResultData(
            planKey = "PROJ-AUTO",
            buildNumber = 1,
            state = "Successful",
            durationSeconds = 100
        )
        assertEquals("", data.lifeCycleState)
    }

    @Test
    fun `BuildResultData parsed from JSON with lifeCycleState field (A-P1-1 probe shape)`() {
        // Mirrors the shape from tools/atlassian-probe/Result_Bamboo/bundle-repo.unpacked/raw/result_full.json
        val probeJson = """
            {
              "planKey": "FXZV-A0CL",
              "buildNumber": 390,
              "state": "Successful",
              "durationSeconds": 473,
              "buildResultKey": "FXZV-A0CLVIKGLYVHCUI-ULVZ-390",
              "lifeCycleState": "Finished"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<BuildResultData>(probeJson)

        assertEquals("Successful", parsed.state)
        assertEquals("Finished", parsed.lifeCycleState,
            "lifeCycleState must be 'Finished' matching probe result_full.json")
    }
}
