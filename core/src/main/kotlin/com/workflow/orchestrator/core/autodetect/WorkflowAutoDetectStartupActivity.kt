package com.workflow.orchestrator.core.autodetect

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Runs auto-detection once on project open, after smart mode (so PSI / Maven /
 * Git APIs are ready). Background only — never blocks the EDT.
 */
class WorkflowAutoDetectStartupActivity : ProjectActivity {

    private val log = logger<WorkflowAutoDetectStartupActivity>()

    override suspend fun execute(project: Project) {
        log.info("[AutoDetect:Startup] Project opened, scheduling initial sweep")
        DumbService.getInstance(project).runWhenSmart {
            val orchestrator = project.getService(AutoDetectOrchestrator::class.java)
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                orchestrator.detectAll()
            }
        }
    }
}
