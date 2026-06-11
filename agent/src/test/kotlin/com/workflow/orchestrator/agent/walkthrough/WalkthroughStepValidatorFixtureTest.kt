package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class WalkthroughStepValidatorFixtureTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    // ONE test method per BasePlatformTestCase class (indexing-timeout gotcha).
    fun `test defaultStepValidator rejects missing files and out-of-bounds lines individually`() {
        // configureByText files live at temp:///src/ (in-memory VFS) — LocalFileSystem cannot
        // see them via findFileByPath. Write to a real disk file and refresh the VFS so that
        // resolveStepFile (which calls LocalFileSystem.findFileByPath) can locate it.
        val diskFile = Files.createTempFile("WalkthroughStep", ".kt").also {
            Files.writeString(it, "a\nb\nc\n")
        }
        val realPath = diskFile.toAbsolutePath().toString()

        // Refresh the VFS so the platform sees the file we just wrote.
        LocalFileSystem.getInstance().refreshAndFindFileByPath(realPath)

        val steps = listOf(
            WalkthroughStep(realPath, 1, 3, null, "ok"), // valid (absolute path)
            WalkthroughStep("does/not/Exist.kt", 1, 1, null, "missing"), // file not found
            WalkthroughStep(realPath, 99, 100, null, "oob"), // start_line beyond EOF
        )
        try {
            val result = runBlocking { defaultStepValidator(project, steps) }
            assertEquals(1, result.valid.size)
            assertEquals(2, result.errors.size)
            assertTrue(result.errors[0].contains("file not found"))
            assertTrue(result.errors[1].contains("start_line 99 exceeds"))
        } finally {
            Files.deleteIfExists(diskFile)
        }
    }
}
