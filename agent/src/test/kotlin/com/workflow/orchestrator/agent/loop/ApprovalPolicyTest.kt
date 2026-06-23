package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.dto.FunctionParameters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalPolicyTest {
    private fun tool(
        n: String, requires: Boolean = false, session: Boolean = false,
    ) = object : AgentTool {
        override val name = n
        override val description = n
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)
        override val requiresApproval = requires
        override val allowSessionApproval = session
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "", summary = "", tokenEstimate = 0)
    }

    @Test
    fun `run_command-style tool is per-invocation`() {
        val p = ApprovalPolicy.forTool(tool("run_command", requires = true, session = false))
        assertTrue(p.requiresApproval); assertFalse(p.allowSessionApproval)
    }

    @Test
    fun `edit_file-style tool allows session`() {
        val p = ApprovalPolicy.forTool(tool("edit_file", requires = true, session = true))
        assertTrue(p.requiresApproval); assertTrue(p.allowSessionApproval)
    }

    @Test
    fun `read-only tool needs no approval`() {
        val p = ApprovalPolicy.forTool(tool("read_file"))
        assertFalse(p.requiresApproval); assertFalse(p.allowSessionApproval)
    }
}
