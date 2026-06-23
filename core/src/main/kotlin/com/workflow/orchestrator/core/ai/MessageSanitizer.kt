package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import com.workflow.orchestrator.core.util.StringUtils

/**
 * Cross-cutting message sanitization for Anthropic-backed providers.
 *
 * Both [SourcegraphChatClient] (`/.api/llm/chat/completions`) and
 * [BrainRouter]'s stream path (`/.api/completions/stream`) send to
 * Anthropic-backed models. Both are subject to the same rejection rules:
 *
 * - "system role is not supported" — Sourcegraph proxy rejects system-role messages.
 * - "message content cannot be empty" — Anthropic rejects messages whose content
 *   field is empty or null with no tool calls.
 *
 * Extracting the sanitization here prevents the two call sites from drifting
 * apart. Behaviour is identical to the private `sanitizeMessages` that previously
 * lived only in [SourcegraphChatClient] — this is a refactor-and-reuse, not a
 * semantic change.
 */
object MessageSanitizer {

    /**
     * Sanitize a message list for submission to an Anthropic-backed provider.
     *
     * **Phase 1** — convert `system` and `tool` roles to `user` content so that
     * the proxy / gateway never sees a role it rejects:
     *   - `system` messages are buffered and merged into the next `user` message
     *     as a `<system_instructions>` block.
     *   - `tool` messages are emitted as `user` messages with a plain-text
     *     `TOOL RESULT:\n` prefix (no XML — prevents the LLM from echoing the
     *     XML sentinel back as structured output).
     *
     * **Phase 2** — merge consecutive same-role messages (Anthropic strict
     * alternation requirement).
     *
     * **Phase 3** — handle empty / null content:
     *   - Case 1: Messages with no content AND no tool calls → dropped entirely.
     *     [`isEffectivelyBlank`][StringUtils.isEffectivelyBlank] also catches
     *     U+200B-only echoes so that an LLM that mirrors a stale placeholder
     *     back doesn't pollute future prompts.
     *
     * **Phase 4** — ensure the conversation starts with `user`.
     *
     * **Phase 5** — final same-role merge pass after Phase 3 removals.
     *
     * **Phase 6** — ensure the conversation ends with `user`. Anthropic-via-Vertex
     * rejects requests whose final message is assistant ("This model does not
     * support assistant message prefill"). Symmetric counterpart to Phase 4.
     *
     * @param messages Raw conversation history from the agent loop.
     * @return Sanitized list safe to submit to Sourcegraph / Anthropic.
     */
    fun sanitizeForAnthropic(messages: List<ChatMessage>): List<ChatMessage> {
        // Phase 1: convert system and tool roles to user content
        val converted = mutableListOf<ChatMessage>()
        var pendingSystemContent: String? = null

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    // Buffer system content to merge into next user message
                    val content = msg.content ?: ""
                    pendingSystemContent = if (pendingSystemContent != null) {
                        "$pendingSystemContent\n$content"
                    } else content
                }
                "tool" -> {
                    // Convert tool result to user message with plain text prefix.
                    // Do NOT use XML tags like <tool_result> — they prime the LLM to
                    // generate <tool_calls> as text instead of using the structured API.
                    // Plain text labels (matching OpenHands/SWE-agent pattern) avoid this.
                    val toolContent =
                        "${XmlToolProtocol.TOOL_RESULT_WIRE_PREFIX}${msg.content ?: ""}"
                    // Preserve `parts` so that ContentPart.Image entries on a tool-result
                    // message survive the role-coercion. The /stream path reads `parts`
                    // directly to hydrate base64 image URIs; dropping them here would lose
                    // any auto-loaded images (e.g. jira.download_attachment results).
                    // The SourcegraphChatClient (/chat/completions) path never sends images,
                    // so it doesn't depend on this field being forwarded — but it doesn't
                    // hurt to preserve it either.
                    converted.add(ChatMessage(role = "user", content = toolContent, parts = msg.parts))
                }
                "user" -> {
                    // Merge any pending system content into this user message.
                    // Preserve `parts` so that ContentPart.Image entries on user messages
                    // (image-bearing turns from the UI paste / file upload flow) survive
                    // to the /stream path for hydration.
                    val content = if (pendingSystemContent != null) {
                        val merged = "<system_instructions>\n$pendingSystemContent\n</system_instructions>\n\n<user_message>\n${msg.content ?: ""}\n</user_message>"
                        pendingSystemContent = null
                        merged
                    } else {
                        msg.content ?: ""
                    }
                    converted.add(ChatMessage(role = "user", content = content, parts = msg.parts))
                }
                "assistant" -> {
                    // If there's buffered system content with no user message yet, emit as user first
                    if (pendingSystemContent != null) {
                        converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
                        pendingSystemContent = null
                    }
                    converted.add(msg)
                }
                else -> converted.add(msg)
            }
        }

        // Flush any remaining system content
        if (pendingSystemContent != null) {
            converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
        }

        // Phase 2: merge consecutive same-role messages (Anthropic requirement)
        val merged = mutableListOf<ChatMessage>()
        for (msg in converted) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role) {
                // Merge into previous message
                merged[merged.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                merged.add(msg)
            }
        }

        // Phase 3: handle empty/null content (Anthropic rejects "message content cannot be empty")
        // Drop messages with no content AND no tool calls AND no parts.
        // `isEffectivelyBlank` (not `isNullOrBlank`) also catches U+200B-only echoes —
        // if the LLM mirrors a stale placeholder back as its own "response" it will be
        // dropped here rather than reaching the next prompt. See StringUtils.
        // Exception: messages with non-text ContentPart entries in `parts` (e.g.
        // ContentPart.Image) carry real payload even when the text content is null/blank.
        // The /stream path reads `parts` directly to hydrate base64 URIs — dropping
        // such a message would silently lose the image. Keep any message that has `parts`.
        merged.removeAll { msg ->
            StringUtils.isEffectivelyBlank(msg.content)
                && msg.toolCalls.isNullOrEmpty()
                && msg.parts.isNullOrEmpty()
        }

        // Phase 4: ensure conversation starts with "user"
        if (merged.isNotEmpty() && merged.first().role != "user") {
            merged.add(0, ChatMessage(role = "user", content = "[Context follows]"))
        }

        // Phase 5: ensure no two consecutive same-role messages after removals
        val result = mutableListOf<ChatMessage>()
        for (msg in merged) {
            val last = result.lastOrNull()
            if (last != null && last.role == msg.role) {
                result[result.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                result.add(msg)
            }
        }

        // Phase 6: ensure conversation ends with "user". Anthropic-via-Vertex rejects
        // requests whose final message is assistant with: "This model does not support
        // assistant message prefill. The conversation must end with a user message."
        // Tool-role tails are coerced to user in Phase 1, so this only fires when an
        // upstream path (ContextManager.reInjectActiveSkill / reInjectActivePlan, or a
        // compact() whose verbatim L4 tail was assistant) leaves assistant at the end.
        if (result.isNotEmpty() && result.last().role == "assistant") {
            result.add(ChatMessage(role = "user", content = "[Continue]"))
        }

        return result
    }
}
