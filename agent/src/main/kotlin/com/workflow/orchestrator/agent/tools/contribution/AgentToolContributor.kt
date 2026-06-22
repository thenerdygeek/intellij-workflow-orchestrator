package com.workflow.orchestrator.agent.tools.contribution

import com.intellij.openapi.extensions.ExtensionPointName
import com.workflow.orchestrator.core.api.InternalApi

/** EP letting a depending plugin (B) contribute agent tools. @InternalApi: public so B can
 *  implement it, but NOT frozen — we may change it; B recompiles in lockstep. */
@InternalApi
interface AgentToolContributor {
    fun registerTools(context: ToolRegistrationContext)
    companion object {
        val EP_NAME: ExtensionPointName<AgentToolContributor> =
            ExtensionPointName.create("com.workflow.orchestrator.agentToolContributor")
    }
}
