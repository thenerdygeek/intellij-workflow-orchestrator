package com.workflow.orchestrator.core.services.impl

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the storage and ring-buffer behaviour of
 * [BuildEventCaptureServiceImpl]. Listener-installation paths
 * (`installCaptureListeners`) are deliberately NOT exercised here — they wire
 * IntelliJ Platform listeners that need a real Application/Project. The
 * end-to-end characterisation lives in `BuildProblemsEndToEndTest`
 * (BasePlatformTestCase, separate file).
 */
class BuildEventCaptureServiceImplTest {

    private val project = mockk<Project>(relaxed = true)
    private fun service() = BuildEventCaptureServiceImpl(project)

    private fun problem(
        source: BuildSource = BuildSource.GRADLE_IMPORT,
        path: String = "/proj/build.gradle",
        description: String = "test",
        type: ProblemType = ProblemType.OTHER,
    ) = BuildProblem(
        source = source,
        projectPath = path,
        description = description,
        type = type,
        severity = Severity.ERROR,
    )

    @Test
    fun `snapshot returns empty when nothing recorded`() {
        val s = service()
        assertEquals(emptyList<BuildProblem>(), s.snapshot(BuildSource.GRADLE_IMPORT))
        assertEquals(emptyList<BuildProblem>(), s.snapshot(BuildSource.COMPILE))
    }

    @Test
    fun `record then snapshot returns the same problem`() {
        val s = service()
        val p = problem()
        s.record(p)
        assertEquals(listOf(p), s.snapshot(BuildSource.GRADLE_IMPORT))
    }

    @Test
    fun `records are partitioned by source`() {
        val s = service()
        val gradle = problem(source = BuildSource.GRADLE_IMPORT)
        val compile = problem(source = BuildSource.COMPILE)
        s.record(gradle)
        s.record(compile)
        assertEquals(listOf(gradle), s.snapshot(BuildSource.GRADLE_IMPORT))
        assertEquals(listOf(compile), s.snapshot(BuildSource.COMPILE))
    }

    @Test
    fun `clear removes only the named source`() {
        val s = service()
        s.record(problem(source = BuildSource.GRADLE_IMPORT))
        s.record(problem(source = BuildSource.COMPILE))
        s.clear(BuildSource.GRADLE_IMPORT)
        assertEquals(emptyList<BuildProblem>(), s.snapshot(BuildSource.GRADLE_IMPORT))
        assertEquals(1, s.snapshot(BuildSource.COMPILE).size)
    }

    @Test
    fun `ring buffer caps at MAX_PER_SOURCE and evicts oldest`() {
        val s = service()
        val cap = BuildEventCaptureServiceImpl.MAX_PER_SOURCE
        repeat(cap + 5) { i ->
            s.record(problem(description = "p$i"))
        }
        val snap = s.snapshot(BuildSource.GRADLE_IMPORT)
        assertEquals(cap, snap.size)
        // Oldest 5 evicted; descriptions in [5, cap+5) survive.
        assertEquals("p5", snap.first().description)
        assertEquals("p${cap + 4}", snap.last().description)
    }

    @Test
    fun `snapshot is a defensive copy — old snapshots do not see later records`() {
        val s = service()
        s.record(problem(description = "first"))
        val snap1 = s.snapshot(BuildSource.GRADLE_IMPORT)
        s.record(problem(description = "second"))
        val snap2 = s.snapshot(BuildSource.GRADLE_IMPORT)
        assertEquals(1, snap1.size)
        assertEquals(2, snap2.size)
    }

    @Test
    fun `dispose clears all sources`() {
        val s = service()
        s.record(problem(source = BuildSource.GRADLE_IMPORT))
        s.record(problem(source = BuildSource.COMPILE))
        s.dispose()
        assertTrue(s.snapshot(BuildSource.GRADLE_IMPORT).isEmpty())
        assertTrue(s.snapshot(BuildSource.COMPILE).isEmpty())
    }
}
