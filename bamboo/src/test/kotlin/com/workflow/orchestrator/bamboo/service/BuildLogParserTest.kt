package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.model.ErrorSeverity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BuildLogParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @Test
    fun `parses ERROR lines with file path and line number`() {
        val log = fixture("build-log.txt")

        val errors = BuildLogParser.parse(log)

        val compileErrors = errors.filter { it.severity == ErrorSeverity.ERROR }
        assertTrue(compileErrors.isNotEmpty())

        val first = compileErrors[0]
        assertEquals(ErrorSeverity.ERROR, first.severity)
        assertEquals("/src/main/java/com/example/UserService.java", first.filePath)
        assertEquals(45, first.lineNumber)
        assertTrue(first.message.contains("cannot find symbol"))
    }

    @Test
    fun `parses WARNING lines`() {
        val log = fixture("build-log.txt")

        val errors = BuildLogParser.parse(log)

        val warnings = errors.filter { it.severity == ErrorSeverity.WARNING }
        assertTrue(warnings.isNotEmpty())
        assertEquals("/src/main/java/com/example/Config.java", warnings[0].filePath)
        assertEquals(12, warnings[0].lineNumber)
    }

    @Test
    fun `returns empty list for clean log`() {
        val log = """
            build    11-Mar-2026 10:23:45    [INFO] BUILD SUCCESS
            build    11-Mar-2026 10:23:45    [INFO] Total time: 10 s
        """.trimIndent()

        val errors = BuildLogParser.parse(log)

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `handles ERROR lines without file path`() {
        val log = """
            build    11-Mar-2026 10:23:45    [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin
        """.trimIndent()

        val errors = BuildLogParser.parse(log)

        assertEquals(1, errors.size)
        assertNull(errors[0].filePath)
        assertNull(errors[0].lineNumber)
        assertTrue(errors[0].message.contains("Failed to execute goal"))
    }
}
