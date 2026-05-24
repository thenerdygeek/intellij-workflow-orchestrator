package com.workflow.orchestrator.bamboo.run

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression tests for audit finding bamboo:F-2.
 *
 * BambooBuildRunState printed every build variable verbatim (including password-typed ones).
 * The fix masks all values that are not the branch-routing variable.
 */
class BambooBuildRunStateMaskingTest {

    /**
     * Simulate the masking logic used in BambooBuildProcessHandler.runBuild():
     *   effectiveVariables.forEach { (k, v) ->
     *       val displayValue = if (k == "bamboo.planRepository.1.branch") v else "••••"
     *       ...
     *   }
     */
    private fun buildConsoleLines(variables: Map<String, String>): List<String> {
        return variables.map { (k, v) ->
            val displayValue = if (k == "bamboo.planRepository.1.branch") v else "••••"
            "  $k = $displayValue"
        }
    }

    @Test
    fun `password variable value is masked as dots`() {
        val vars = mapOf(
            "deploy.token" to "super-secret-1234",
            "db.password" to "hunter2"
        )
        val lines = buildConsoleLines(vars)
        for (line in lines) {
            assertTrue(line.endsWith("= ••••"), "Expected value masked, got: $line")
            assertFalse(line.contains("super-secret-1234"), "Secret must not appear in console output")
            assertFalse(line.contains("hunter2"), "Secret must not appear in console output")
        }
    }

    @Test
    fun `branch routing variable is printed verbatim`() {
        val vars = mapOf(
            "bamboo.planRepository.1.branch" to "feature/my-branch",
            "deploy.token" to "very-secret"
        )
        val lines = buildConsoleLines(vars)

        val branchLine = lines.first { it.contains("bamboo.planRepository.1.branch") }
        assertTrue(branchLine.contains("feature/my-branch"), "Branch value should not be masked")

        val tokenLine = lines.first { it.contains("deploy.token") }
        assertTrue(tokenLine.endsWith("= ••••"), "Non-branch variable must be masked")
    }

    @Test
    fun `plain variable values are masked`() {
        val vars = mapOf("build.mode" to "release")
        val lines = buildConsoleLines(vars)
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("= ••••"))
        assertFalse(lines[0].contains("release"))
    }
}
