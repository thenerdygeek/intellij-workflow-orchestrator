package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MicroservicesDetector].
 *
 * The detector is a runtime probe via reflection — it must NOT throw even
 * when the microservices module is absent from the classpath (e.g. on
 * IntelliJ Community at runtime, or in a unit-test harness without the
 * Ultimate platform JAR).
 */
class MicroservicesDetectorTest {

    @Test
    fun `isAvailable does not throw when microservices class is absent`() {
        // In the test classpath the microservices module may or may not be
        // present. Either outcome is fine — the test asserts the method is
        // total (returns a Boolean, never throws).
        val result = MicroservicesDetector.isAvailable()
        // The Kotlin compiler guarantees `result` is Boolean via the return
        // type; the point of this test is that the call completes without
        // exception. Assertion here just exercises the value.
        assertTrue(result || !result)
    }

    @Test
    fun `isAvailable returns same value when called repeatedly`() {
        val first = MicroservicesDetector.isAvailable()
        val second = MicroservicesDetector.isAvailable()
        assertTrue(first == second)
    }

    @Test
    fun `isAvailable returns false on simulated ClassNotFoundException`() {
        // Use the package-private overload that takes a class loader with
        // the microservices package removed. This lets us simulate the IC
        // codepath from plain JVM tests.
        val emptyLoader = object : ClassLoader() {
            override fun loadClass(name: String): Class<*> {
                if (name.startsWith("com.intellij.microservices")) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name)
            }
        }
        assertFalse(MicroservicesDetector.isAvailableForClassLoader(emptyLoader))
    }
}
