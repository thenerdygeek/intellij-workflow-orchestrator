package com.workflow.orchestrator.core.autodetect

import org.junit.jupiter.api.Assertions.assertEquals
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
}
