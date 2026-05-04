package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlatformCommandWrapperTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `cmdWrap prepends cmd_exe slash c on Windows and returns argv unchanged on Unix`() {
        val argv = listOf("uv", "sync", "--frozen")
        val wrapped = PlatformCommandWrapper.cmdWrap(argv)
        if (isWindows) {
            assertEquals(listOf("cmd.exe", "/c", "uv", "sync", "--frozen"), wrapped)
        } else {
            assertEquals(argv, wrapped)
        }
    }

    @Test
    fun `cmdWrap on empty list yields wrapper-only on Windows and empty on Unix`() {
        val wrapped = PlatformCommandWrapper.cmdWrap(emptyList())
        if (isWindows) {
            assertEquals(listOf("cmd.exe", "/c"), wrapped)
        } else {
            assertEquals(emptyList<String>(), wrapped)
        }
    }

    @Test
    fun `cmdWrap preserves argument order`() {
        val argv = listOf("python", "-m", "py_compile", "a.py", "b.py", "c.py")
        val wrapped = PlatformCommandWrapper.cmdWrap(argv)
        // The original argv must appear as a contiguous suffix of the result.
        assertEquals(argv, wrapped.takeLast(argv.size))
    }
}
