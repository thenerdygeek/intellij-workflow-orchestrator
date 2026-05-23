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
    object Started : SpawnResult()
    data class Failed(val message: String) : SpawnResult()
}

object DefaultProcessSpawner : ProcessSpawner {
    override fun spawn(launcher: Path, projectPath: Path): SpawnResult =
        try {
            ProcessBuilder(launcher.toString(), projectPath.toString())
                .redirectErrorStream(true)
                .start()
            SpawnResult.Started
        } catch (e: Exception) {
            SpawnResult.Failed(e.message ?: e.javaClass.simpleName)
        }
}
