package com.workflow.orchestrator.agent.tools.framework.maven

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for the filesystem walk that powers Maven auto-detect in feedback #5
 * (refresh_external_project on a fresh-clone Maven project) and #7
 * (compile_module with refresh_maven_first=true).
 *
 * The reflective MavenProjectsManager calls in detectAndRegisterMaven require
 * a live IntelliJ Application and the bundled Maven plugin, so end-to-end
 * verification is manual-smoke only. We pin the walk behaviour here — it's the
 * piece most likely to regress under refactoring.
 */
class MavenImportHelperTest {

    @Test
    fun `finds pom at project root`(@TempDir tmp: Path) {
        File(tmp.toFile(), "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertEquals(1, result.size)
        assertEquals("pom.xml", result.first().name)
    }

    @Test
    fun `finds pom at depth 1 subdirectory`(@TempDir tmp: Path) {
        val sub = File(tmp.toFile(), "moduleA").also { it.mkdir() }
        File(sub, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertEquals(1, result.size)
        assertTrue(result.first().path.contains("moduleA"))
    }

    @Test
    fun `finds pom at depth 2 subdirectory`(@TempDir tmp: Path) {
        val sub = File(tmp.toFile(), "modules/inner").apply { mkdirs() }
        File(sub, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertEquals(1, result.size)
    }

    @Test
    fun `finds pom at depth 3 subdirectory (raised cap)`(@TempDir tmp: Path) {
        // MAX_DEPTH raised from 2→3 on 2026-05-18 to catch micro-service layouts
        // like apps/<svc>/services/<endpoint>/pom.xml. Walk excludes (target/build/...)
        // keep the false-positive cost low.
        val deep = File(tmp.toFile(), "a/b/c").apply { mkdirs() }
        File(deep, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertEquals(1, result.size, "pom.xml at depth 3 should be discovered after MAX_DEPTH=3")
    }

    @Test
    fun `does NOT recurse past depth 3 (current cap)`(@TempDir tmp: Path) {
        val deeper = File(tmp.toFile(), "a/b/c/d").apply { mkdirs() }
        File(deeper, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertTrue(result.isEmpty(), "pom.xml at depth 4 must NOT be discovered (current perf cap)")
    }

    @Test
    fun `finds multiple poms in multi-module layout`(@TempDir tmp: Path) {
        File(tmp.toFile(), "pom.xml").writeText("<project/>")
        File(tmp.toFile(), "moduleA").mkdir().also {
            File(tmp.toFile(), "moduleA/pom.xml").writeText("<project/>")
        }
        File(tmp.toFile(), "moduleB").mkdir().also {
            File(tmp.toFile(), "moduleB/pom.xml").writeText("<project/>")
        }

        val result = findPomFiles(tmp.toString())

        assertEquals(3, result.size, "Should find root + moduleA + moduleB poms")
    }

    @Test
    fun `excludes node_modules subtree`(@TempDir tmp: Path) {
        val nm = File(tmp.toFile(), "node_modules/somepkg").apply { mkdirs() }
        File(nm, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertTrue(result.isEmpty(), "pom under node_modules must be skipped")
    }

    @Test
    fun `excludes target subtree`(@TempDir tmp: Path) {
        val t = File(tmp.toFile(), "target/dependency").apply { mkdirs() }
        File(t, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertTrue(result.isEmpty(), "pom under target/ must be skipped (Maven build output)")
    }

    @Test
    fun `excludes build subtree`(@TempDir tmp: Path) {
        val b = File(tmp.toFile(), "build/dependency").apply { mkdirs() }
        File(b, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertTrue(result.isEmpty(), "pom under build/ must be skipped (Gradle build output)")
    }

    @Test
    fun `excludes hidden directories`(@TempDir tmp: Path) {
        val hidden = File(tmp.toFile(), ".cache/somepkg").apply { mkdirs() }
        File(hidden, "pom.xml").writeText("<project/>")

        val result = findPomFiles(tmp.toString())

        assertFalse(result.any { it.path.contains(".cache") })
    }

    @Test
    fun `empty directory returns empty list`(@TempDir tmp: Path) {
        val result = findPomFiles(tmp.toString())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `non-existent directory returns empty list`() {
        val result = findPomFiles("/totally/made/up/path/that/does/not/exist")

        assertTrue(result.isEmpty())
    }
}
