package com.workflow.orchestrator.agent.delegation

import java.nio.file.Path

/**
 * Subprocess spawn interface. Injected into the auto-launch path so unit tests
 * can drive spawn failure / success without touching the real OS.
 *
 * Plan 3 spec §5.6.
 */
interface ProcessSpawner {
    /** Spawns [launcher] with [projectPath] as the lone argument. */
    fun spawn(launcher: Path, projectPath: Path): SpawnResult
}

sealed class SpawnResult {
    /**
     * Spawn succeeded. [process] is the launcher's [Process] handle — null when
     * the spawner is a test fake. Used by callers to apply a launcher-lifetime
     * heuristic: when an IntelliJ is already running on the host and the user
     * runs `idea.sh /path/repo` (or `idea64.exe`), the launcher signals the
     * running JVM via single-instance IPC and exits within ~1s. When the IDE is
     * NOT running, the launcher starts a fresh JVM and stays alive (or execs
     * into it). So `process.waitFor(2s) && exitValue() == 0` ≈ "handed off to
     * an already-running IDE" — useful diagnostic when AutoLaunchPoller later
     * times out (most likely cause: target IDE has inbound disabled).
     *
     * Plan 5.4 — inbound-off scenario diagnostics.
     */
    data class Started(val process: Process? = null) : SpawnResult()
    data class Failed(val message: String) : SpawnResult()
}

object DefaultProcessSpawner : ProcessSpawner {
    override fun spawn(launcher: Path, projectPath: Path): SpawnResult =
        try {
            val process = ProcessBuilder(launcher.toString(), projectPath.toString())
                .redirectErrorStream(true)
                .start()
            SpawnResult.Started(process)
        } catch (e: Exception) {
            SpawnResult.Failed(e.message ?: e.javaClass.simpleName)
        }
}
