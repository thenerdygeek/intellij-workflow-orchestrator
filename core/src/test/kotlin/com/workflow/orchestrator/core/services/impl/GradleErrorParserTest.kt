package com.workflow.orchestrator.core.services.impl

import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleErrorParserTest {

    private val pomPath = "/proj/build.gradle"

    @Test
    fun `Could not resolve dependency yields DEPENDENCY with coords`() {
        val msg = "Could not resolve all dependencies: org.foo:bar:1.2.3"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        val p = problems.single()
        assertEquals(BuildSource.GRADLE_IMPORT, p.source)
        assertEquals(ProblemType.DEPENDENCY, p.type)
        assertEquals("org.foo:bar:1.2.3", p.artifactCoords)
    }

    @Test
    fun `Could not find yields DEPENDENCY with coords`() {
        val msg = "Could not find org.example:lib:2.0"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        assertEquals(ProblemType.DEPENDENCY, problems.single().type)
        assertEquals("org.example:lib:2.0", problems.single().artifactCoords)
    }

    @Test
    fun `plugin not found yields STRUCTURE`() {
        val msg = "Plugin with id 'org.springframework.boot' not found"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        assertEquals(ProblemType.STRUCTURE, problems.single().type)
    }

    @Test
    fun `Build file line yields STRUCTURE with line number`() {
        val msg = "Build file '/proj/build.gradle' line: 42"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        val p = problems.single()
        assertEquals(ProblemType.STRUCTURE, p.type)
        assertEquals(42, p.line)
        assertEquals("/proj/build.gradle", p.projectPath)
    }

    @Test
    fun `401 yields REPOSITORY type`() {
        val msg = "Could not GET 'https://nexus.example.com/path'. Received status code 401 from server."
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertTrue(problems.any { it.type == ProblemType.REPOSITORY })
    }

    @Test
    fun `Unauthorized yields REPOSITORY type`() {
        val msg = "Authentication failed for repository remote-releases"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertTrue(problems.any { it.type == ProblemType.REPOSITORY })
    }

    @Test
    fun `unknown error text yields single OTHER entry with text`() {
        val msg = "Some completely novel Gradle failure"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        assertEquals(ProblemType.OTHER, problems.single().type)
        assertEquals(msg, problems.single().description)
        assertNull(problems.single().artifactCoords)
    }

    @Test
    fun `blank input yields empty list`() {
        assertEquals(emptyList<Any>(), GradleErrorParser.parse(pomPath, ""))
        assertEquals(emptyList<Any>(), GradleErrorParser.parse(pomPath, "   \n\t"))
    }

    @Test
    fun `multiple dependency errors all parsed`() {
        val msg = """
            Could not find org.foo:bar:1.0
            Could not find org.foo:baz:2.0
        """.trimIndent()
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(2, problems.size)
        assertTrue(problems.all { it.type == ProblemType.DEPENDENCY })
        val coords = problems.map { it.artifactCoords }
        assertTrue("org.foo:bar:1.0" in coords)
        assertTrue("org.foo:baz:2.0" in coords)
    }

    @Test
    fun `dependency and repository auth in same text both captured`() {
        val msg = """
            Could not find org.foo:bar:1.0
            401 Unauthorized for https://nexus.example.com/repo
        """.trimIndent()
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertTrue(problems.any { it.type == ProblemType.DEPENDENCY })
        assertTrue(problems.any { it.type == ProblemType.REPOSITORY })
    }

    @Test
    fun `OTHER fallback truncates to first 5 lines`() {
        val msg = (1..10).joinToString("\n") { "line $it" }
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertEquals(1, problems.size)
        val description = problems.single().description
        assertTrue(description.startsWith("line 1"))
        assertTrue(description.contains("line 5"))
        assertTrue(!description.contains("line 6"))
    }

    @Test
    fun `coords regex does not match URL tokens`() {
        // Same URL false-positive case as V1 extractArtifactCoords; ensure parser
        // does not generate a fake DEPENDENCY for a URL with port.
        val msg = "Build failed at https://nexus.example.com:443/path/to/repo"
        val problems = GradleErrorParser.parse(pomPath, msg)
        assertTrue(problems.none { it.artifactCoords?.contains("nexus") == true })
    }
}
