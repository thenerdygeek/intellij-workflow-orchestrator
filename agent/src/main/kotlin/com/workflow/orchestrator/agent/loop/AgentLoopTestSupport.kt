package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.services.ToolResult
import org.jetbrains.annotations.VisibleForTesting

/**
 * Public seam over [AgentLoop.buildToolResultApiMessage]. Visible for tests
 * so the emission shape is pinned without spinning up a full AgentLoop.
 */
@VisibleForTesting
object AgentLoopTestSupport {
    fun buildToolResultApiMessage(
        toolUseId: String,
        toolResult: ToolResult<*>,
        truncatedContent: String,
    ): ApiMessage = ApiMessage(
        role = ApiRole.USER,
        content = buildList {
            add(ContentBlock.ToolResult(toolUseId = toolUseId, content = truncatedContent, isError = toolResult.isError))
            toolResult.imageRefs.forEach {
                add(ContentBlock.ImageRef(
                    sha256 = it.sha256, mime = it.mime, size = it.size, originalFilename = it.originalFilename
                ))
            }
        }
    )
}
