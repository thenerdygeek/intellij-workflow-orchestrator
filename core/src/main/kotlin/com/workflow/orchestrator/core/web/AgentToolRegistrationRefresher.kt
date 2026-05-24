// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.core.web

/**
 * Bridge interface that allows :web settings configurables to trigger a re-evaluation of
 * conditional tool registration in AgentService without creating a :web → :agent dependency
 * (which would form a cycle: :agent → :core → :web → :agent).
 *
 * The :agent module registers a project service implementing this interface
 * ([com.workflow.orchestrator.agent.AgentToolRegistrationRefresherAdapter]) that delegates
 * to [com.workflow.orchestrator.agent.AgentService.reregisterConditionalTools].
 *
 * [WebSettingsConfigurable] calls [refresh] on apply so that toggling enableWebFetch /
 * enableWebSearch takes effect immediately in the running agent session without an IDE restart.
 */
interface AgentToolRegistrationRefresher {
    /**
     * Re-evaluate which conditional tools should be registered and update the registry.
     * Idempotent — safe to call multiple times; only changes state when the toggle has flipped.
     */
    fun refresh()
}
