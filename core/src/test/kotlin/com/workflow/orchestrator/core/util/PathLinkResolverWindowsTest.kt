package com.workflow.orchestrator.core.util

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathLinkResolverWindowsTest {

    @TempDir lateinit var projectDir: Path
    private lateinit var resolver: PathLinkResolver

    @BeforeEach
    fun setUp() {
        mockkStatic(ModuleManager::class, ModuleRootManager::class)
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns projectDir.toString()
        val module = mockk<com.intellij.openapi.module.Module>(relaxed = true)
        val rm = mockk<ModuleRootManager>(relaxed = true)
        val contentRoot = mockk<VirtualFile>(relaxed = true)
        every { contentRoot.path } returns projectDir.toString()
        every { rm.contentRoots } returns arrayOf(contentRoot)
        val mm = mockk<ModuleManager>(relaxed = true)
        every { mm.modules } returns arrayOf(module)
        every { ModuleManager.getInstance(project) } returns mm
        every { ModuleRootManager.getInstance(module) } returns rm
        resolver = PathLinkResolver(project)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `rejects UNC path`() {
        assertNull(resolver.resolveForOpen("\\\\server\\share\\file.kt"))
    }

    @Test
    fun `rejects extended length prefix`() {
        assertNull(resolver.resolveForOpen("\\\\?\\C:\\project\\foo.kt"))
    }

    @Test
    fun `rejects device namespace`() {
        assertNull(resolver.resolveForOpen("\\\\.\\CON"))
    }

    @Test
    fun `rejects reserved DOS name bare`() {
        assertNull(resolver.resolveForOpen("CON"))
        assertNull(resolver.resolveForOpen("NUL"))
        assertNull(resolver.resolveForOpen("LPT9"))
    }

    @Test
    fun `rejects reserved DOS name with extension`() {
        assertNull(resolver.resolveForOpen("con.txt"))
        assertNull(resolver.resolveForOpen("CON.kt"))
        assertNull(resolver.resolveForOpen("lpt9.log"))
    }

    @Test
    fun `rejects drive-relative path`() {
        assertNull(resolver.resolveForOpen("C:foo.kt"))
    }

    @Test
    fun `rejects trailing dot in segment`() {
        Files.createDirectories(projectDir.resolve("src"))
        assertNull(resolver.resolveForOpen("src\\foo.kt."))
    }

    @Test
    fun `rejects trailing space in segment`() {
        assertNull(resolver.resolveForOpen("src\\foo.kt "))
    }

    @Test
    fun `rejects alternate data stream syntax`() {
        // foo.kt:stream is not a plausible line-col suffix (non-numeric), so parser leaves it.
        // toRealPath() then fails because no such file exists, returning null.
        assertNull(resolver.resolveForOpen("src\\foo.kt:stream"))
    }

    @Test
    fun `rejects 8-3 short name segment`() {
        assertNull(resolver.resolveForOpen("src\\PROGRA~1\\foo.kt"))
    }

    @Test
    fun `rejects reserved basename chars`() {
        assertNull(resolver.resolveForOpen("src/foo<script>.kt"))
        assertNull(resolver.resolveForOpen("src/foo|bar.kt"))
        assertNull(resolver.resolveForOpen("src/x?.kt"))
        assertNull(resolver.resolveForOpen("src/x*.kt"))
    }

    @Test
    fun `accepts forward slashes on windows`() {
        assumeTrue(SystemInfo.isWindows)
        Files.createDirectories(projectDir.resolve("src"))
        Files.createFile(projectDir.resolve("src/foo.kt"))
        assertNotNull(resolver.resolveForOpen("src/foo.kt"))
    }

    @Test
    fun `case-insensitive allowlist on windows`() {
        assumeTrue(SystemInfo.isWindows)
        Files.createDirectories(projectDir.resolve("src"))
        val file = Files.createFile(projectDir.resolve("src/foo.kt"))
        val upper = file.toRealPath().toString().uppercase()
        val result = resolver.resolveForOpen(upper)
        assertNotNull(result)
    }
}
