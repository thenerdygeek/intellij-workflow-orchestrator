package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.brain.OpenAiCompatBrain
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.runtime.PlanManager
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@Service(Service.Level.PROJECT)
class AgentService(
    private val project: Project
) : Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Reference to the active AgentController, used for session resume from History tab. */
    var activeController: com.workflow.orchestrator.agent.ui.AgentController? = null

    /** Plan manager for the current agent session, set by SingleAgentSession. */
    var currentPlanManager: PlanManager? = null

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            // Builtin tools
            register(ReadFileTool())
            register(EditFileTool())
            register(SearchCodeTool())
            register(RunCommandTool())
            register(DiagnosticsTool())

            // PSI tools
            register(FileStructureTool())
            register(FindReferencesTool())
            register(FindDefinitionTool())
            register(TypeHierarchyTool())
            register(CallHierarchyTool())

            // Spring PSI tools
            register(SpringContextTool())
            register(SpringEndpointsTool())
            register(SpringBeanGraphTool())

            // Jira integration tools
            register(JiraGetTicketTool())
            register(JiraGetTransitionsTool())
            register(JiraTransitionTool())
            register(JiraCommentTool())
            register(JiraGetCommentsTool())
            register(JiraLogWorkTool())

            // Bamboo integration tools
            register(BambooBuildTool())
            register(BambooGetBuildTool())
            register(BambooTriggerBuildTool())
            register(BambooGetBuildLogTool())
            register(BambooGetTestResultsTool())

            // SonarQube integration tools
            register(SonarIssuesTool())
            register(SonarQualityGateTool())
            register(SonarCoverageTool())
            register(SonarSearchProjectsTool())

            // Bitbucket integration tools
            register(BitbucketPrTool())

            // Planning tools
            register(CreatePlanTool())
            register(UpdatePlanStepTool())
        }
    }

    val brain: LlmBrain by lazy {
        val settings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        OpenAiCompatBrain(
            sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
            tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
            model = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"
        )
    }

    fun isConfigured(): Boolean {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        return agentSettings.state.agentEnabled &&
            connections.state.sourcegraphUrl.isNotBlank() &&
            !credentialStore.getToken(ServiceType.SOURCEGRAPH).isNullOrBlank()
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        fun getInstance(project: Project): AgentService {
            return project.service<AgentService>()
        }
    }
}
