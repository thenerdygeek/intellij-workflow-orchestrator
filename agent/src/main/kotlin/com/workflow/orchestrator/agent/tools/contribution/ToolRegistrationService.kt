package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolRegistry

/**
 * Project-scoped host for agent tools contributed by depending plugins via the
 * [AgentToolContributor] EP. Thin by design (Phase 0b-3): A's own [ToolRegistry] stays owned by
 * `AgentService`; this service only runs the EP iteration (with per-contributor isolation) against
 * that registry. A-internal infrastructure — B contributes through the EP, not through this service,
 * so this is NOT part of the @InternalApi B-facing surface.
 */
@Service(Service.Level.PROJECT)
class ToolRegistrationService(private val project: Project) {

    /** Run every registered [AgentToolContributor] against [registry]; never throws. */
    fun contributeExternalTools(registry: ToolRegistry): ContributionDiagnostics {
        val context = ToolRegistrationContext(project, registry)
        val contributors = AgentToolContributor.EP_NAME.extensionList
        return ToolContributionRunner.run(contributors, context, registry)
    }

    companion object {
        fun getInstance(project: Project): ToolRegistrationService = project.service()
    }
}
