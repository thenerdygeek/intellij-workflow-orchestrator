package com.workflow.orchestrator.core.healthcheck.checks

import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarGateCheckTest {

    private val project = mockk<Project>()

    @Test
    fun `id is sonar-gate`() {
        assertEquals("sonar-gate", SonarGateCheck().id)
    }

    @Test
    fun `order is 40`() {
        assertEquals(40, SonarGateCheck().order)
    }

    @Test
    fun `returns passed when no cached data`() = runTest {
        val check = SonarGateCheck()
        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)
        assertTrue(result.passed)
        assertTrue(result.message.contains("no cached data"))
    }

    @Test
    fun `returns passed when gate passed`() = runTest {
        val check = SonarGateCheck()
        check.setLastKnownGateStatus(true)
        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)
        assertTrue(result.passed)
    }

    @Test
    fun `returns failed when gate failed`() = runTest {
        val check = SonarGateCheck()
        check.setLastKnownGateStatus(false)
        val context = HealthCheckContext(project, emptyList(), "msg", "main")
        val result = check.execute(context)
        assertFalse(result.passed)
    }
}
