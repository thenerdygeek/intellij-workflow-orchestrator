package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.services.ToolResult
import org.jetbrains.annotations.VisibleForTesting

/**
 * Public seam over private [AgentLoop] helpers. Visible for tests so emission
 * shapes are pinned without spinning up a full AgentLoop.
 */
@VisibleForTesting
object AgentLoopTestSupport {

    /**
     * Mirrors AgentLoop.buildApiContentBlocks(msg). Keep in sync.
     *
     * Exposes [AgentLoop.buildApiContentBlocks] for unit tests.
     *
     * After the 2026-05-13 XML-in-content migration this must return a single
     * [ContentBlock.Text] carrying [ChatMessage.content] verbatim (XML inline).
     */
    fun buildApiContentBlocks(msg: ChatMessage): List<ContentBlock> {
        val text = msg.content ?: ""
        return listOf(ContentBlock.Text(text))
    }

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
