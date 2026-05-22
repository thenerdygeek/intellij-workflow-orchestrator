package com.workflow.orchestrator.agent.delegation

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Calls [DelegationInboundService.start] after project opening. Runs only
 * when the inbound setting is enabled — the service performs the gate check.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §5.4.
 */
class DelegationInboundStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(DelegationInboundService::class.java).start()
    }
}
