package com.workflow.orchestrator.automation.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class QueueRecoveryStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(QueueRecoveryStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            val queueService = project.getService(QueueService::class.java)
            queueService?.restoreFromPersistence()
            log.info("Queue recovery completed for project: ${project.name}")
        } catch (e: Exception) {
            log.warn("Queue recovery failed: ${e.message}", e)
        }
    }
}
