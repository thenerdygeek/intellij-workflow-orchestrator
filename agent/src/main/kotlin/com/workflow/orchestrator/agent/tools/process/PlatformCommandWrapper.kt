package com.workflow.orchestrator.agent.tools.process

/**
 * Wraps a process argv with `cmd.exe /c` on Windows so PATHEXT (which
 * resolves `python`, `pip`, `poetry`, `uv`, `pytest`, etc. to their
 * `.cmd` / `.bat` / `.exe` script counterparts) is engaged. On Unix the
 * argv is returned unchanged — the bare command name is looked up
 * normally via PATH.
 *
 * Java's `ProcessBuilder` calls `CreateProcess` on Windows, which
 * auto-appends `.exe` for missing extensions but does NOT consult
 * PATHEXT for `.cmd` / `.bat`. So `ProcessBuilder("uv", "sync").start()`
 * fails with `error=2 The system cannot find the file specified` on
 * Windows even when `uv.cmd` is correctly installed and on PATH.
 *
 * ## Where to use this
 *
 * **For Maven/Gradle build invocations:** use [BuildToolExecutableResolver]
 * instead. It picks the Windows-correct executable name (`mvn.cmd` /
 * `gradle.bat`) directly and prefers project-local wrappers
 * (`mvnw.cmd` / `gradlew.bat`).
 *
 * **For arbitrary user shell commands:** use [ShellResolver] instead.
 * It picks the user's preferred shell (Git Bash → PowerShell → cmd.exe
 * on Windows; bash on Unix) and is what `RunCommandTool` uses.
 *
 * **For everything else** (Python ecosystem CLIs, framework CLIs that
 * lack a wrapper script): use this helper. It's the lowest-overhead
 * way to make a small set of known commands Windows-portable.
 *
 * ## Spawn-site contract
 *
 * Every `ProcessBuilder(...)` / `Runtime.exec(...)` call in `:agent`
 * for a non-platform executable should route through one of:
 *   - [BuildToolExecutableResolver]
 *   - [ShellResolver]
 *   - [PlatformCommandWrapper.cmdWrap]
 *
 * Pinned by `ProcessSpawnContractTest`.
 */
object PlatformCommandWrapper {

    private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Returns [argv] unchanged on Unix; on Windows, prepends `["cmd.exe", "/c"]`
     * so PATHEXT resolves `.cmd` / `.bat` script extensions.
     *
     * The wrapper is unconditional on Windows — even when [argv] starts with
     * a binary that happens to ship as `.exe` (e.g. `python.exe`), the
     * `cmd.exe /c` wrapper is harmless and keeps call sites uniform.
     */
    fun cmdWrap(argv: List<String>): List<String> =
        if (IS_WINDOWS) listOf("cmd.exe", "/c") + argv else argv
}
