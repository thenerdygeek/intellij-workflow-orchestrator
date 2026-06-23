package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalPolicy
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves a depending plugin (B) CAN now contribute a SAFE write tool: a contributed tool that
 * declares isMutating + requiresApproval is picked up by the SAME registry-derivation AgentService
 * uses for plan-mode write filtering AND trips the same approval policy. The blocker the spec named
 * ("until safety props land, B may not contribute write tools") is lifted.
 */
class ContributedWriteToolClassificationTest {
    private val bWriteTool = object : AgentTool {
        override val name = "companyb_write"
        override val description = "B write tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override val isMutating = true
        override val requiresApproval = true
        override val allowSessionApproval = true
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    @Test
    fun `contributed mutating tool is write-classified by the registry derivation`() {
        val registry = ToolRegistry()
        registry.registerCore(bWriteTool)
        val writeNames = registry.allTools().filter { it.isMutating }.map { it.name }.toSet()
        assertTrue("companyb_write" in writeNames, "B write tool must be in the derived write-tool set")
    }

    @Test
    fun `contributed write tool trips the approval gate policy`() {
        val policy = ApprovalPolicy.forTool(bWriteTool)
        assertTrue(policy.requiresApproval)
        assertTrue(policy.allowSessionApproval)
    }
}
