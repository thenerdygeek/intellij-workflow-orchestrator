package com.workflow.orchestrator.mockserver.bamboo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BambooDataFactoryTest {

    @Test
    fun `default state has divergent lifecycle states`() {
        val state = BambooDataFactory.createDefaultState()
        val runningBuild = state.builds["PROJ-TEST-50"]
        assertNotNull(runningBuild)
        assertEquals("Running", runningBuild!!.lifeCycleState, "Should use 'Running' not 'InProgress'")
    }

    @Test
    fun `default state includes PartiallySuccessful build`() {
        val state = BambooDataFactory.createDefaultState()
        val sonarBuild = state.builds["PROJ-SONAR-25"]
        assertEquals("PartiallySuccessful", sonarBuild?.state)
    }

    @Test
    fun `default state has non-standard stage names`() {
        val state = BambooDataFactory.createDefaultState()
        val successBuild = state.builds["PROJ-BUILD-99"]!!
        val stageNames = successBuild.stages.map { it.name }
        assertTrue("Compile & Package" in stageNames)
        assertTrue("Security Scan" in stageNames)
        assertTrue("Integration Tests" in stageNames)
    }

    @Test
    fun `trigger build starts as Queued`() {
        val state = BambooDataFactory.createDefaultState()
        val build = state.triggerBuild("PROJ-BUILD")
        assertEquals("Queued", build.lifeCycleState)
        assertNull(build.state)
        state.shutdown()
    }

    @Test
    fun `cancel build changes state to Cancelled`() {
        val state = BambooDataFactory.createDefaultState()
        val build = state.triggerBuild("PROJ-BUILD")
        assertTrue(state.cancelBuild(build.buildResultKey))
        val cancelled = state.builds[build.buildResultKey]
        assertEquals("Cancelled", cancelled?.lifeCycleState)
        assertEquals("Cancelled", cancelled?.state)
        state.shutdown()
    }

    @Test
    fun `all-failing state has all builds failed`() {
        val state = BambooDataFactory.createAllFailingState()
        state.builds.values.forEach {
            assertEquals("Failed", it.state)
        }
    }
}
