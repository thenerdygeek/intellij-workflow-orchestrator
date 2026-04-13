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
}
