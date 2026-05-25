// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.AgentToolRegistrationRefresher

/**
 * Adapter that wires [AgentToolRegistrationRefresher] (declared in :core so :web can call it)
 * to [AgentService.reregisterConditionalTools] (in :agent) without creating a :web → :agent
 * module dependency.
 *
 * Registered as a project service in plugin.xml with the [AgentToolRegistrationRefresher]
 * interface, following the same :core-interface / :agent-impl pattern used by
 * [com.workflow.orchestrator.agent.subagent.SubagentSpawnerAdapter].
 *
 * Called by [com.workflow.orchestrator.web.ui.WebSettingsConfigurable.apply] after persisting
 * new settings, so toggling enableWebFetch / enableWebSearch takes effect immediately in the
 * running agent session without an IDE restart.
 */
class AgentToolRegistrationRefresherAdapter(private val project: Project) : AgentToolRegistrationRefresher {

    private val log = Logger.getInstance(AgentToolRegistrationRefresherAdapter::class.java)

    override fun refresh() {
        try {
            project.service<AgentService>().reregisterConditionalTools()
        } catch (e: Exception) {
            log.warn("AgentToolRegistrationRefresherAdapter: reregisterConditionalTools failed: ${e.message}", e)
        }
    }
}
