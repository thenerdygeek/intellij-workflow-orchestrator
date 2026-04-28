package com.workflow.orchestrator.core.services.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.services.BuildEventCaptureService

/**
 * Triggers [BuildEventCaptureServiceImpl.installCaptureListeners] after project open
 * so the Gradle import + compile event listeners are wired before any build runs.
 *
 * Why a startup activity rather than constructor wiring: the impl service constructor
 * is intentionally side-effect-free for unit-testability. A separate post-startup
 * step keeps that contract while still ensuring listeners are live before the user
 * triggers a Maven/Gradle reload or a compile.
 */
class BuildEventCaptureProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = BuildEventCaptureService.getInstance(project)
        if (service is BuildEventCaptureServiceImpl) {
            service.installCaptureListeners()
        }
    }
}
