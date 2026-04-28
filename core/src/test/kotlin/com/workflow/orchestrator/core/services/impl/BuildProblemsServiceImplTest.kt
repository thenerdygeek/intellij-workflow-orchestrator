package com.workflow.orchestrator.core.services.impl

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildProblemsServiceImplTest {

    private val project = mockk<Project>(relaxed = true)

    private fun service(probe: MavenProblemsProbe) = BuildProblemsServiceImpl(project, probe)

    private fun probe(vararg raws: MavenProblemsProbe.RawProblem) = object : MavenProblemsProbe {
        override fun read(project: Project) = raws.toList()
    }

    @Test
    fun `empty probe yields empty problems and no-error summary`() = runTest {
        val result = service(probe()).getRecentBuildProblems()
        assertFalse(result.isError)
        assertTrue(result.data.isEmpty())
        assertEquals("No build/import problems.", result.summary)
    }

    @Test
    fun `single dependency problem maps type and extracts coordinates`() = runTest {
        val raw = MavenProblemsProbe.RawProblem(
            path = "/proj/pom.xml",
            description = "Could not transfer artifact org.foo:bar:jar:1.2.3 from nexus.example.com (401)",
            typeName = "DEPENDENCY",
        )
        val result = service(probe(raw)).getRecentBuildProblems()
        assertEquals(1, result.data.size)
        val p = result.data.first()
        assertEquals(BuildSource.MAVEN_IMPORT, p.source)
        assertEquals(ProblemType.DEPENDENCY, p.type)
        assertEquals(Severity.ERROR, p.severity)
        assertEquals("/proj/pom.xml", p.projectPath)
        assertEquals("org.foo:bar:jar:1.2.3", p.artifactCoords)
    }

    @Test
    fun `non-dependency problems do not get artifact coordinates`() = runTest {
        val raw = MavenProblemsProbe.RawProblem(
            path = "/proj/pom.xml",
            description = "Parent POM not found: org.foo:parent:1.0.0",
            typeName = "PARENT",
        )
        val result = service(probe(raw)).getRecentBuildProblems()
        val p = result.data.single()
        assertEquals(ProblemType.PARENT, p.type)
        assertNull(p.artifactCoords)
    }

    @Test
    fun `unknown type name maps to OTHER`() = runTest {
        val raw = MavenProblemsProbe.RawProblem(
            path = "/proj/pom.xml",
            description = "Some new error type",
            typeName = "NEW_FUTURE_TYPE",
        )
        val result = service(probe(raw)).getRecentBuildProblems()
        assertEquals(ProblemType.OTHER, result.data.single().type)
    }

    @Test
    fun `null type name maps to OTHER`() = runTest {
        val raw = MavenProblemsProbe.RawProblem(
            path = "/proj/pom.xml",
            description = "no type name available",
            typeName = null,
        )
        val result = service(probe(raw)).getRecentBuildProblems()
        assertEquals(ProblemType.OTHER, result.data.single().type)
    }

    @Test
    fun `multiple problems aggregated and counted in summary`() = runTest {
        val raws = arrayOf(
            MavenProblemsProbe.RawProblem("/a/pom.xml", "x", "DEPENDENCY"),
            MavenProblemsProbe.RawProblem("/a/pom.xml", "y", "REPOSITORY"),
            MavenProblemsProbe.RawProblem("/a/pom.xml", "z", "PARENT"),
        )
        val result = service(probe(*raws)).getRecentBuildProblems()
        assertEquals(3, result.data.size)
        assertTrue(result.summary.startsWith("3 build/import problem(s)"), "summary was: ${result.summary}")
        assertTrue(result.summary.contains("MAVEN_IMPORT"))
    }

    @Test
    fun `SETTINGS_OR_PROFILES type name maps to SETTINGS`() = runTest {
        val raw = MavenProblemsProbe.RawProblem(
            path = "/proj/pom.xml",
            description = "Profile activation failed",
            typeName = "SETTINGS_OR_PROFILES",
        )
        val result = service(probe(raw)).getRecentBuildProblems()
        assertEquals(ProblemType.SETTINGS, result.data.single().type)
    }

    @Test
    fun `extractArtifactCoords returns null when no coordinate-shape token`() = runTest {
        val impl = BuildProblemsServiceImpl(project, probe())
        assertNull(impl.extractArtifactCoords("plain English message with no colons"))
        assertNull(impl.extractArtifactCoords("only one:colon here"))
    }

    @Test
    fun `extractArtifactCoords trims trailing punctuation`() = runTest {
        val impl = BuildProblemsServiceImpl(project, probe())
        assertEquals("org.foo:bar:1.0", impl.extractArtifactCoords("missing org.foo:bar:1.0."))
    }
}
