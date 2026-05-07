package com.workflow.orchestrator.sonar.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonarPathResolverTest {

    @Test
    fun `vcs root prefix is stripped`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/repo/src/Foo.kt",
            vcsRootPath = "/repo",
            projectBasePath = null
        )
        assertEquals("src/Foo.kt", result)
    }

    @Test
    fun `vcs root wins over project base path when both prefix the file`() {
        // Submodule case: project base is /projA, repo root is /projA/sub.
        // Relative path should be against the deeper (vcs) root.
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/projA/sub/file.kt",
            vcsRootPath = "/projA/sub",
            projectBasePath = "/projA"
        )
        assertEquals("file.kt", result)
    }

    @Test
    fun `project base path is fallback when vcs root is null`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/projA/file.kt",
            vcsRootPath = null,
            projectBasePath = "/projA"
        )
        assertEquals("file.kt", result)
    }

    @Test
    fun `original path is returned when no base matches`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/elsewhere/file.kt",
            vcsRootPath = "/somewhere",
            projectBasePath = "/another"
        )
        assertEquals("/elsewhere/file.kt", result)
    }

    @Test
    fun `trailing slash on base is tolerated`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/repo/src/Foo.kt",
            vcsRootPath = "/repo/",
            projectBasePath = null
        )
        assertEquals("src/Foo.kt", result)
    }

    @Test
    fun `blank vcs root is treated as null`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/projA/file.kt",
            vcsRootPath = "",
            projectBasePath = "/projA"
        )
        assertEquals("file.kt", result)
    }

    @Test
    fun `file equal to base path returns empty string`() {
        val result = SonarPathResolver.computeRelativePath(
            filePath = "/repo",
            vcsRootPath = "/repo",
            projectBasePath = null
        )
        assertEquals("", result)
    }
}
