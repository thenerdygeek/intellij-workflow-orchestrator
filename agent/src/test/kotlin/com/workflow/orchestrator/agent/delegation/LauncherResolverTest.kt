package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class LauncherResolverTest {

    @Test
    fun `detects Toolbox layout via path segments`() {
        val r = LauncherResolver(
            homePath = "/Users/me/Library/Application Support/JetBrains/Toolbox/apps/IDEA-U/ch-0/251.1234.5",
            osName = "Mac OS X",
        )
        assertTrue(r.isToolboxInstall(), "Toolbox layout should be detected from `Toolbox/apps/.../ch-0/`")
    }

    @Test
    fun `does not flag non-Toolbox layout`() {
        val r = LauncherResolver(
            homePath = "/Applications/IntelliJ IDEA.app/Contents",
            osName = "Mac OS X",
        )
        assertFalse(r.isToolboxInstall())
    }

    @EnabledOnOs(value = [OS.MAC, OS.LINUX])
    @Test
    fun `resolves bin idea on mac and linux`() {
        val r = LauncherResolver(
            homePath = "/Applications/IntelliJ IDEA.app/Contents",
            osName = "Mac OS X",
        )
        val launcher = r.resolveLauncher()
        assertTrue(launcher.toString().endsWith("bin/idea") || launcher.toString().endsWith("bin/idea.sh"),
            "Expected mac/linux launcher suffix; got $launcher")
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `resolves bin idea64 exe on windows`() {
        val r = LauncherResolver(
            homePath = "C:\\Program Files\\JetBrains\\IntelliJ IDEA",
            osName = "Windows 11",
        )
        val launcher = r.resolveLauncher()
        assertTrue(launcher.toString().endsWith("bin\\idea64.exe") || launcher.toString().endsWith("bin/idea64.exe"))
    }
}
