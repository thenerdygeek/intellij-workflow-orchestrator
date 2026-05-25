package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProjectIdentifierResearchDirTest {

    @Test
    fun `researchDir is agentDir slash research`() {
        val basePath = "/Users/test/some/project"
        val agentDir = ProjectIdentifier.agentDir(basePath)
        val researchDir = ProjectIdentifier.researchDir(basePath)
        assertEquals(File(agentDir, "research"), researchDir)
    }

    @Test
    fun `researchDir is deterministic for the same project base path`() {
        val basePath = "/Users/test/some/project"
        val a = ProjectIdentifier.researchDir(basePath)
        val b = ProjectIdentifier.researchDir(basePath)
        assertEquals(a, b)
    }

    @Test
    fun `researchDir for different projects produces different paths`() {
        val a = ProjectIdentifier.researchDir("/Users/test/project-a")
        val b = ProjectIdentifier.researchDir("/Users/test/project-b")
        assertTrue(a != b, "researchDir for different projects must differ; got $a vs $b")
    }
}
