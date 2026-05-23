package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/**
 * Resolves the path to the running IDE's launcher binary and detects
 * JetBrains Toolbox installation layout. Injected into the auto-launch
 * path so tests can drive deterministic paths without binding to the
 * real IntelliJ install.
 *
 * Plan 3 spec §5.6.
 */
class LauncherResolver(
    private val homePath: String = PathManager.getHomePath(),
    private val osName: String = System.getProperty("os.name", ""),
) {
    fun isWindows(): Boolean = osName.startsWith("Windows", ignoreCase = true)

    /**
     * True when the IDE is installed under a JetBrains Toolbox layout. Detected
     * by the presence of `Toolbox/apps/.../ch-0/` in the install root path.
     * When true, callers should consult [ToolboxFlavorReader] before auto-launching
     * a project that may have been opened with a different IDE flavor.
     */
    fun isToolboxInstall(): Boolean =
        homePath.contains("/Toolbox/apps/") && homePath.contains("/ch-0/")

    /**
     * Returns the absolute path to the launcher binary for this IDE. Mac and
     * Linux use `bin/idea.sh` (preferred) or `bin/idea` if `.sh` is absent;
     * Windows uses `bin\idea64.exe`.
     */
    fun resolveLauncher(): Path {
        val home = Path.of(homePath)
        return if (isWindows()) {
            home.resolve("bin").resolve("idea64.exe")
        } else {
            val sh = home.resolve("bin").resolve("idea.sh")
            if (sh.toFile().exists()) sh else home.resolve("bin").resolve("idea")
        }
    }
}
