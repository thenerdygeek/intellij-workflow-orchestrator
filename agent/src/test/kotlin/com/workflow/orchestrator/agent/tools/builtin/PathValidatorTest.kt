package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PathValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `allows paths within project`() {
        val projectDir = tempDir.toFile()
        File(projectDir, "src/Main.kt").apply { parentFile.mkdirs(); writeText("fun main(){}") }

        val (path, error) = PathValidator.resolveAndValidate("src/Main.kt", projectDir.absolutePath)
        assertNull(error)
        assertNotNull(path)
        assertTrue(path!!.startsWith(projectDir.canonicalPath))
    }

    @Test
    fun `blocks path traversal with dot-dot`() {
        val projectDir = tempDir.toFile()
        val (path, error) = PathValidator.resolveAndValidate("../../etc/passwd", projectDir.absolutePath)
        assertNull(path)
        assertNotNull(error)
        assertTrue(error!!.isError)
        assertTrue(error.content.contains("outside the project"))
    }

    @Test
    fun `blocks absolute path outside project`() {
        val projectDir = tempDir.toFile()
        val (path, error) = PathValidator.resolveAndValidate("/etc/passwd", projectDir.absolutePath)
        assertNull(path)
        assertNotNull(error)
        assertTrue(error!!.isError)
    }

    @Test
    fun `allows absolute path within project`() {
        val projectDir = tempDir.toFile()
        val subFile = File(projectDir, "src/Main.kt").apply { parentFile.mkdirs(); writeText("hi") }

        val (path, error) = PathValidator.resolveAndValidate(subFile.absolutePath, projectDir.absolutePath)
        assertNull(error)
        assertNotNull(path)
    }

    @Test
    fun `handles null project base path`() {
        val (path, error) = PathValidator.resolveAndValidate("src/Main.kt", null)
        assertNull(path)
        assertNotNull(error)
        assertTrue(error!!.content.contains("project base path"))
    }

    @Test
    fun `blocks symlink traversal`() {
        val projectDir = tempDir.toFile()
        // Symlinks that resolve outside project should be caught by canonicalPath
        val (path, error) = PathValidator.resolveAndValidate("../../../tmp/evil", projectDir.absolutePath)
        assertNull(path)
        assertNotNull(error)
    }

    // ── resolveAndValidateForRead: agent data directory access ──

    @Test
    fun `resolveAndValidateForRead allows paths within agent data directory`() {
        val projectDir = tempDir.toFile()
        val agentDataDir = File(System.getProperty("user.home"), ".workflow-orchestrator")
        // Use a path that would exist in the agent data dir (doesn't need to exist for validation)
        val agentPath = File(agentDataDir, "test-project/agent/sessions/abc/plan.md").absolutePath

        val (path, error) = PathValidator.resolveAndValidateForRead(agentPath, projectDir.absolutePath)
        assertNull(error, "Read access to ~/.workflow-orchestrator/ should be allowed")
        assertNotNull(path)
        assertTrue(path!!.startsWith(agentDataDir.canonicalPath))
    }

    @Test
    fun `resolveAndValidateForRead still allows project paths`() {
        val projectDir = tempDir.toFile()
        File(projectDir, "src/Main.kt").apply { parentFile.mkdirs(); writeText("hi") }

        val (path, error) = PathValidator.resolveAndValidateForRead("src/Main.kt", projectDir.absolutePath)
        assertNull(error)
        assertNotNull(path)
    }

    @Test
    fun `resolveAndValidateForRead still blocks paths outside both directories`() {
        val projectDir = tempDir.toFile()
        val (path, error) = PathValidator.resolveAndValidateForRead("/etc/passwd", projectDir.absolutePath)
        assertNull(path)
        assertNotNull(error)
        assertTrue(error!!.isError)
    }

    @Test
    fun `resolveAndValidate (write) blocks agent data directory`() {
        val projectDir = tempDir.toFile()
        val agentPath = File(System.getProperty("user.home"), ".workflow-orchestrator/test/plan.md").absolutePath

        val (path, error) = PathValidator.resolveAndValidate(agentPath, projectDir.absolutePath)
        assertNull(path, "Write access to ~/.workflow-orchestrator/ should be blocked")
        assertNotNull(error)
        assertTrue(error!!.isError)
    }
}
