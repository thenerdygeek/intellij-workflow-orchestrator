package com.workflow.orchestrator.core.services

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource

/**
 * Retains build problems captured from event-time sources (Gradle import,
 * compile) so [BuildProblemsService] can read them snapshot-style later.
 *
 * Maven errors are NOT captured here — V1's `BuildProblemsServiceImpl`
 * reads them snapshot-style from `MavenProjectsManager.problems`.
 *
 * Implementation owns ring buffers (default cap 50 per source); listener
 * installation is performed by a separate post-startup activity to keep
 * the service constructor side-effect-free and unit-testable.
 */
interface BuildEventCaptureService {
    /** Returns a defensive copy of currently retained problems for [source]. */
    fun snapshot(source: BuildSource): List<BuildProblem>

    /** Records a captured problem. Thread-safe; called from listener callbacks. */
    fun record(problem: BuildProblem)

    /** Drops all retained problems for [source]. Used between builds and in tests. */
    fun clear(source: BuildSource)

    companion object {
        fun getInstance(project: Project): BuildEventCaptureService =
            project.service<BuildEventCaptureService>()
    }
}
