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

    /**
     * Single source of truth for the master+autoload gate that produces the
     * `ContentBlock.ImageRef` list appended to a tool-result `ApiMessage`.
     * Production calls this from [AgentLoop]; unit tests call it directly so
     * the guard logic is pinned in one place. When either flag is OFF the
     * returned list is empty regardless of `toolResult.imageRefs`.
     */
    fun gateImageRefs(
        toolResult: ToolResult<*>,
        masterEnabled: Boolean,
        autoloadEnabled: Boolean,
    ): List<ContentBlock.ImageRef> = if (masterEnabled && autoloadEnabled) {
        toolResult.imageRefs.map { ref ->
            ContentBlock.ImageRef(
                sha256 = ref.sha256,
                mime = ref.mime,
                size = ref.size,
                originalFilename = ref.originalFilename,
            )
        }
    } else {
        emptyList()
    }
}
