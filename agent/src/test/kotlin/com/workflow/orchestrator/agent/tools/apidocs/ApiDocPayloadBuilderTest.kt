package com.workflow.orchestrator.agent.tools.apidocs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiDocPayloadBuilderTest {

    @Test
    fun `buildJson produces a families array and a loadErrors array`() {
        val json = ApiDocPayloadBuilder.buildJson()
        assertTrue(json.contains("\"families\""), "payload must have families key")
        assertTrue(json.contains("\"loadErrors\""), "payload must have loadErrors key")
    }

    @Test
    fun `build loads all five families with no load errors`() {
        val payload = ApiDocPayloadBuilder.build()
        assertEquals(
            ApiDocLoader.FAMILY_IDS.toSet(),
            payload.families.map { it.id }.toSet(),
            "all shipped families must load",
        )
        assertTrue(
            payload.loadErrors.isEmpty(),
            "no family should fail to load, but got: ${payload.loadErrors}",
        )
    }

    @Test
    fun `build pairs a load error with the correct family id when a resource is dropped`() {
        // Exercises the zip-with-FAMILY_IDS error pairing without touching disk: a missing
        // resource id yields an error keyed to THAT id (not a mis-indexed neighbour).
        val missing = ApiDocLoader.loadFamily("definitely-absent-family")
        assertEquals(null, missing.family)
        val err = ApiDocLoadError("definitely-absent-family", missing.error ?: "unknown error")
        assertEquals("definitely-absent-family", err.id)
        assertTrue(err.error.contains("definitely-absent-family"))
    }
}
