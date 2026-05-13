package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ChatMessage
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
     *     U+200B-only echoes (the placeholder we inject in Case 2) so that an
     *     LLM that mirrors the placeholder back doesn't pollute future prompts.
     *   - Case 2: Assistant messages with tool calls but null/empty content →
     *     substitute a U+200B zero-width space placeholder. Requirements: not
     *     natural language (avoids stuck-echo loops), not XML (avoids
     *     `<tool_calls/>` hallucination), short and obviously structural.
     *
     * **Phase 4** — ensure the conversation starts with `user`.
     *
     * **Phase 5** — final same-role merge pass after Phase 3 removals.
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
                    val toolContent = "TOOL RESULT:\n${msg.content ?: ""}"
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
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
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
        // Case 1: Messages with no content AND no tool calls → drop entirely.
        // `isEffectivelyBlank` (not `isNullOrBlank`) also catches U+200B-only echoes —
        // the LLM occasionally mirrors the placeholder we inject in Case 2 below back as
        // its own "response". If we don't drop those, they reach the next prompt and
        // train the model to mimic the empty pattern further. See StringUtils.
        // Exception: messages with non-text ContentPart entries in `parts` (e.g.
        // ContentPart.Image) carry real payload even when the text content is null/blank.
        // The /stream path reads `parts` directly to hydrate base64 URIs — dropping
        // such a message would silently lose the image. Keep any message that has `parts`.
        merged.removeAll { msg ->
            StringUtils.isEffectivelyBlank(msg.content)
                && msg.toolCalls.isNullOrEmpty()
                && msg.parts.isNullOrEmpty()
        }
        // Case 2: Assistant messages with tool calls but null/empty content → set placeholder
        // (LLM often returns content=null when making tool calls — this is normal)
        // IMPORTANT: When the assistant makes tool calls, the API often returns content=null.
        // But Anthropic/Sourcegraph rejects empty content in conversation history. We need a
        // placeholder. Requirements:
        // 1. Not natural language — LLM will echo it back as a response (known stuck loop)
        // 2. Not XML that looks like tool syntax — LLM reproduces <tool_calls/> verbatim
        // 3. Short and obviously structural — won't be mistaken for actual output
        // Using a unicode marker that no LLM would generate as a natural response:
        for (i in merged.indices) {
            val msg = merged[i]
            if (msg.role == "assistant" && msg.content.isNullOrBlank() && !msg.toolCalls.isNullOrEmpty()) {
                merged[i] = ChatMessage(
                    role = "assistant",
                    content = "\u200B", // zero-width space — invisible, non-empty, impossible to echo
                    toolCalls = msg.toolCalls
                )
            }
        }

        // Phase 4: ensure conversation starts with "user"
        if (merged.isNotEmpty() && merged.first().role != "user") {
            merged.add(0, ChatMessage(role = "user", content = "[Context follows]"))
        }

        // Phase 5: ensure no two consecutive same-role messages after removals
        val result = mutableListOf<ChatMessage>()
        for (msg in merged) {
            val last = result.lastOrNull()
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
                result[result.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                result.add(msg)
            }
        }

        return result
    }
}
