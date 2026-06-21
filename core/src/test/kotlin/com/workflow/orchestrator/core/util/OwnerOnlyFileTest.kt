package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class OwnerOnlyFileTest {

    private fun isPosix(dir: Path) =
        Files.getFileAttributeView(dir, PosixFileAttributeView::class.java) != null

    @Test
    fun `restrictFile makes a dump file owner-only on POSIX`(@TempDir dir: Path) {
        assumeTrue(isPosix(dir), "POSIX-only assertion")
        val f = dir.resolve("call-001-request.txt").toFile()
        f.writeText("entire conversation + file contents")
        OwnerOnlyFile.restrictFile(f)
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(f.toPath()),
        )
    }

    @Test
    fun `restrictDir makes a dump dir non-traversable by others on POSIX`(@TempDir dir: Path) {
        assumeTrue(isPosix(dir), "POSIX-only assertion")
        val sub = dir.resolve("api-debug").toFile().also { it.mkdirs() }
        OwnerOnlyFile.restrictDir(sub)
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(sub.toPath()),
        )
    }

    @Test
    fun `restrict is best-effort and never throws on a missing target`(@TempDir dir: Path) {
        OwnerOnlyFile.restrictFile(dir.resolve("nope.txt").toFile())   // must not throw
        OwnerOnlyFile.restrictDir(dir.resolve("nope-dir").toFile())    // must not throw
    }

    // A4 wiring pin: the three diagnostic dump writers must restrict their output. They write full
    // request bodies and are deep in HTTP/interceptor plumbing (not unit-instantiable headless), so
    // this is a source-contract pin (cwd = :core module dir under Gradle).
    @Test
    fun `dump writers restrict their output to owner-only`() {
        val sources = listOf(
            "src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt",
            "src/main/kotlin/com/workflow/orchestrator/core/http/RawApiTraceInterceptor.kt",
            "src/main/kotlin/com/workflow/orchestrator/core/http/PreSanitizeDumper.kt",
        )
        sources.forEach { rel ->
            val text = locate(rel).readText()
            assertTrue(
                text.contains("OwnerOnlyFile.restrict"),
                "$rel must restrict its dump output via OwnerOnlyFile.restrict* (A4)",
            )
        }
    }

    private fun locate(rel: String): File {
        val cwd = File(System.getProperty("user.dir"))
        listOf(File(cwd, rel), File(cwd, "core/$rel"), File(cwd.parentFile ?: cwd, "core/$rel"))
            .forEach { if (it.isFile) return it }
        throw IllegalStateException("Cannot locate $rel from cwd=${cwd.absolutePath}")
    }
}
