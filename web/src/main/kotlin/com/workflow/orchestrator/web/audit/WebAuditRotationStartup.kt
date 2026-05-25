package com.workflow.orchestrator.web.audit

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.nio.file.Path

/**
 * Runs on every project open to prune web audit log files older than 7 days.
 * Mirrors the convention used by [WebFetchServiceImpl]: audit files live at
 * `~/.workflow-orchestrator/{dirName}-{first6OfSHA256(basePath)}/logs/web/`.
 */
class WebAuditRotationStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        WebAuditLog(auditLogDir(project)).rotateIfStale()
    }
}

private fun auditLogDir(project: Project): Path {
    val basePath = project.basePath ?: System.getProperty("user.home")
    return ProjectIdentifier.logsDir(basePath).toPath().resolve("web")
}
