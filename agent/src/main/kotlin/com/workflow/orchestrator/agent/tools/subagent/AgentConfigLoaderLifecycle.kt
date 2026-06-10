// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * Application-level owner of the [AgentConfigLoader] singleton's lifecycle (audit bug B1).
 *
 * The loader is app-wide (one user persona dir, one WatchService) but used to be
 * Disposer-registered to a PROJECT-scoped service — so the first project to close disposed
 * it for every other open project. This service is instantiated by [AgentService] and is
 * disposed by the platform on application shutdown / dynamic plugin unload, which is the
 * only correct time to tear the singleton down.
 */
@Service(Service.Level.APP)
class AgentConfigLoaderLifecycle : Disposable {
    override fun dispose() {
        AgentConfigLoader.getInstanceIfCreated()?.dispose()
    }
}
