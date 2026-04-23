package com.workflow.orchestrator.core.util

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathLinkResolverTest {

    @TempDir
    lateinit var projectDir: Path

    private lateinit var project: Project
    private lateinit var resolver: PathLinkResolver

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns projectDir.toString()
        val module = mockk<com.intellij.openapi.module.Module>(relaxed = true)
        val rootManager = mockk<ModuleRootManager>(relaxed = true)
        every { rootManager.contentRoots } returns arrayOf(
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(projectDir.toString())
                ?: error("temp dir not visible to LocalFileSystem")
        )
        val moduleManager = mockk<ModuleManager>(relaxed = true)
        every { moduleManager.modules } returns arrayOf(module)
        every { project.getService(ModuleManager::class.java) } returns moduleManager
        every { ModuleRootManager.getInstance(module) } returns rootManager
        resolver = PathLinkResolver(project)
    }

    @Test
    fun `rejects path longer than 4096 chars`() {
        val long = "a/".repeat(3000) + "foo.kt"
        assertNull(resolver.resolveForOpen(long))
    }

    @Test
    fun `rejects null byte`() {
        assertNull(resolver.resolveForOpen("foo .kt"))
    }

    @Test
    fun `rejects scheme prefix javascript`() {
        assertNull(resolver.resolveForOpen("javascript:alert(1)"))
    }

    @Test
    fun `rejects scheme prefix file`() {
        assertNull(resolver.resolveForOpen("file:///etc/passwd"))
    }

    @Test
    fun `rejects traversal escape`() {
        Files.createFile(projectDir.resolve("inside.kt"))
        assertNull(resolver.resolveForOpen("../../../../../../etc/passwd"))
    }

    @Test
    fun `accepts regular file inside project root`() {
        val target = Files.createFile(projectDir.resolve("inside.kt"))
        val result = resolver.resolveForOpen("inside.kt")
        assertNotNull(result)
        assertEquals(target.toRealPath().toString(), result!!.canonicalPath)
    }

    @Test
    fun `rejects directory`() {
        Files.createDirectory(projectDir.resolve("subdir"))
        assertNull(resolver.resolveForOpen("subdir"))
    }

    @Test
    fun `parses line suffix`() {
        Files.createDirectories(projectDir.resolve("src"))
        Files.createFile(projectDir.resolve("src/foo.kt"))
        val result = resolver.resolveForOpen("src/foo.kt:42")
        assertNotNull(result)
        assertEquals(41, result!!.line)
        assertEquals(0, result.column)
    }

    @Test
    fun `parses line and column suffix`() {
        Files.createDirectories(projectDir.resolve("src"))
        Files.createFile(projectDir.resolve("src/foo.kt"))
        val result = resolver.resolveForOpen("src/foo.kt:42:10")
        assertNotNull(result)
        assertEquals(41, result!!.line)
        assertEquals(9, result.column)
    }

    @Test
    fun `clamps line overflow`() {
        Files.createDirectories(projectDir.resolve("src"))
        Files.createFile(projectDir.resolve("src/foo.kt"))
        val result = resolver.resolveForOpen("src/foo.kt:99999999999999")
        // 14 digits exceeds the {1,7} capture bound in the suffix regex — suffix is not parsed.
        // The whole literal including colons is treated as a path, which doesn't exist → null.
        assertNull(result)
    }

    @Test
    fun `batch validate drops rejects and keeps valid`() {
        Files.createFile(projectDir.resolve("good.kt"))
        val result = resolver.validate(listOf("good.kt", "javascript:x", "../../bad"))
        assertEquals(1, result.size)
        assertEquals("good.kt", result[0].input)
    }
}
