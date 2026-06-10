package com.workflow.orchestrator.agent.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AtomicFileWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes content atomically — file appears only after completion`() {
        val target = File(tempDir.toFile(), "test.json")
        AtomicFileWriter.write(target, """{"key":"value"}""")
        assertTrue(target.exists())
        assertEquals("""{"key":"value"}""", target.readText())
    }

    @Test
    fun `no temp files left after successful write`() {
        val target = File(tempDir.toFile(), "test.json")
        AtomicFileWriter.write(target, "content")
        val remainingFiles = tempDir.toFile().listFiles()!!
        assertEquals(1, remainingFiles.size)
        assertEquals("test.json", remainingFiles[0].name)
    }

    @Test
    fun `overwrites existing file atomically`() {
        val target = File(tempDir.toFile(), "test.json")
        target.writeText("old content")
        AtomicFileWriter.write(target, "new content")
        assertEquals("new content", target.readText())
    }

    @Test
    fun `creates parent directories if needed`() {
        val target = File(tempDir.toFile(), "sub/dir/test.json")
        AtomicFileWriter.write(target, "deep")
        assertEquals("deep", target.readText())
    }

    @Test
    fun `multi-line unicode content round-trips byte-exact through the channel write`() {
        // B18: the write path moved from Files.newOutputStream to FileChannel + force(true)
        // so bytes hit the device before the atomic rename. fsync itself isn't unit-assertable;
        // this pins the content round-trip through the new channel-based path.
        val target = File(tempDir.toFile(), "test.json")
        val content = "line one\nümlaut — émoji 🚀\n{\"key\":\"value\"}\n"
        AtomicFileWriter.write(target, content)
        assertEquals(content, target.readText(Charsets.UTF_8))
    }
}
