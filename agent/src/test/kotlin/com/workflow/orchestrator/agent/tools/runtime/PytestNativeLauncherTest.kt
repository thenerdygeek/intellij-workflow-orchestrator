package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PytestNativeLauncher].
 *
 * Two categories:
 * 1. Pure JVM tests — exercise [PytestNativeLauncher.findMethod] with local class hierarchies.
 *    These run without any IntelliJ platform runtime.
 * 2. Graceful-null tests — verify [PytestNativeLauncher.createSettings] returns null
 *    (rather than throwing) when the IntelliJ platform EP is unavailable. In a pure JVM
 *    test context, [ConfigurationType.CONFIGURATION_TYPE_EP] access will throw; the
 *    outer `try/catch(_: Exception)` in [createSettings] must catch it and return null.
 */
class PytestNativeLauncherTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Fixture classes — local hierarchy for findMethod tests
    // ──────────────────────────────────────────────────────────────────────────

    open class GrandParentConfig {
        fun setWorkingDirectory(@Suppress("UNUSED_PARAMETER") path: String) {}
    }

    open class ParentConfig : GrandParentConfig() {
        fun setTarget(@Suppress("UNUSED_PARAMETER") target: String) {}
        fun setKeyword(@Suppress("UNUSED_PARAMETER") keyword: String) {}
    }

    class ConcreteConfig : ParentConfig() {
        fun setMarker(@Suppress("UNUSED_PARAMETER") marker: String) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findMethod: pure JVM tests
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `findMethod finds method declared directly on the class`() {
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "setMarker", String::class.java)
        assertNotNull(method, "setMarker is declared on ConcreteConfig and should be found")
        assertEquals("setMarker", method!!.name)
    }

    @Test
    fun `findMethod finds method inherited from immediate parent`() {
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "setTarget", String::class.java)
        assertNotNull(method, "setTarget is declared on ParentConfig and should be found via hierarchy walk")
        assertEquals("setTarget", method!!.name)
    }

    @Test
    fun `findMethod finds method inherited from grandparent`() {
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "setWorkingDirectory", String::class.java)
        assertNotNull(method, "setWorkingDirectory is on GrandParentConfig and should be found via hierarchy walk")
        assertEquals("setWorkingDirectory", method!!.name)
    }

    @Test
    fun `findMethod returns null for non-existent method name`() {
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "nonExistentMethod", String::class.java)
        assertNull(method, "A method that does not exist anywhere in the hierarchy should return null")
    }

    @Test
    fun `findMethod returns null when parameter types do not match`() {
        // setTarget(String) exists, but setTarget(Int) does not
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "setTarget", Int::class.java)
        assertNull(method, "findMethod with wrong parameter types should return null")
    }

    @Test
    fun `findMethod marks found methods as accessible`() {
        // Verify the returned method can be invoked without IllegalAccessException
        val method = PytestNativeLauncher.findMethod(ConcreteConfig::class.java, "setTarget", String::class.java)
        assertNotNull(method)
        assertTrue(method!!.canAccess(ConcreteConfig()), "found method should have isAccessible=true")
    }

    @Test
    fun `findMethod works on a class with no superclass beyond Object`() {
        // String has no user-defined superclass — confirm the walker terminates cleanly
        val method = PytestNativeLauncher.findMethod(String::class.java, "absolutelyNotThere", String::class.java)
        assertNull(method)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // findPyTestConfigurationType / createSettings: graceful-null coverage
    //
    // In a pure JVM test the CONFIGURATION_TYPE_EP access throws (no IntelliJ
    // application context). createSettings() wraps everything in try/catch so the
    // exception must be absorbed and null returned — not propagated to the caller.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `createSettings returns null instead of throwing when platform EP is unavailable`() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/my-project"

        val launcher = PytestNativeLauncher(project)

        // In a pure JVM environment the EP access inside findPyTestConfigurationType throws.
        // The outer try/catch in createSettings must absorb it and return null.
        val result = launcher.createSettings(
            pytestPath = "tests/test_api.py",
            keywordExpr = "login",
            markerExpr = "slow",
        )

        assertNull(result, "createSettings should return null (not throw) when the IntelliJ platform is not available")
    }

    @Test
    fun `createSettings returns null when all parameters are null and platform EP is unavailable`() {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns null

        val launcher = PytestNativeLauncher(project)
        val result = launcher.createSettings(null, null, null)

        assertNull(result)
    }
}
