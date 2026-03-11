package com.workflow.orchestrator.core.maven

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MavenOutputPatternsTest {

    @Test
    fun `FILE_ERROR_PATTERN matches error with line and column`() {
        val line = "[ERROR] /src/main/java/Foo.java:42,10 error: ';' expected"
        val match = MavenOutputPatterns.FILE_ERROR_PATTERN.find(line)
        assertNotNull(match)
        assertEquals("/src/main/java/Foo.java", match!!.groupValues[1])
        assertEquals("42", match.groupValues[2])
        assertEquals("10", match.groupValues[3])
    }

    @Test
    fun `FILE_ERROR_PATTERN matches error with line only`() {
        val line = "[ERROR] /src/main/java/Foo.java:42 error: something"
        val match = MavenOutputPatterns.FILE_ERROR_PATTERN.find(line)
        assertNotNull(match)
        assertEquals("/src/main/java/Foo.java", match!!.groupValues[1])
        assertEquals("42", match.groupValues[2])
    }

    @Test
    fun `FILE_WARNING_PATTERN matches warning`() {
        val line = "[WARNING] /src/main/java/Bar.java:10 unchecked cast"
        val match = MavenOutputPatterns.FILE_WARNING_PATTERN.find(line)
        assertNotNull(match)
        assertEquals("/src/main/java/Bar.java", match!!.groupValues[1])
    }

    @Test
    fun `TEST_FAILURE_PATTERN matches test failure`() {
        val line = "<<< FAILURE! - in com.example.FooTest"
        val match = MavenOutputPatterns.TEST_FAILURE_PATTERN.find(line)
        assertNotNull(match)
        assertEquals("com.example.FooTest", match!!.groupValues[2])
    }

    @Test
    fun `FILE_ERROR_PATTERN does not match non-error lines`() {
        val line = "[INFO] BUILD SUCCESS"
        assertNull(MavenOutputPatterns.FILE_ERROR_PATTERN.find(line))
    }
}
