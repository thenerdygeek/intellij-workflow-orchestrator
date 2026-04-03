package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.context.events.*
import org.slf4j.LoggerFactory

/**
 * Converts event-sourced [View] events into Sourcegraph-compatible [ChatMessage] objects.
 *
 * This is the bridge between the event-sourced context management system and the
 * LLM API layer. It handles:
 * 1. Event-to-message conversion with tool call pairing
 * 2. Orphan filtering (unmatched tool_calls / tool results)
 * 3. Sourcegraph sanitization (no system/tool roles, strict alternation)
 * 4. Middle-truncation of oversized messages
 *
 * The sanitization logic mirrors [com.workflow.orchestrator.core.ai.SourcegraphChatClient.sanitizeMessages]
 * which is battle-tested in production.
 */
class ConversationMemory(private val maxMessageChars: Int = 30_000) {

    companion object {
        private val log = LoggerFactory.getLogger(ConversationMemory::class.java)

        /**
         * Escape a string value for safe JSON string interpolation.
         * Escapes backslashes first (to avoid double-escaping), then double quotes.
         */
        internal fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    /**
     * Convert condensed event history into a list of [ChatMessage] ready for the LLM API.
     *
     * @param condensedHistory The View's event list after condensation
     * @param initialUserAction The original user message that started this turn
     * @param forgottenEventIds Event IDs that were forgotten during condensation
     * @return Sanitized, alternating user/assistant messages suitable for Sourcegraph API
     */
    fun processEvents(
        condensedHistory: List<Event>,
        initialUserAction: MessageAction,
        forgottenEventIds: Set<Int> = emptySet()
    ): List<ChatMessage> {
        // Defensive check: the bridge always adds a SystemMessageAction as the first event.
        // If it's missing, log a warning so the problem is visible during development.
        if (condensedHistory.none { it is SystemMessageAction }) {
            log.warn(
                "ConversationMemory.processEvents: no SystemMessageAction found in event history " +
                    "(${condensedHistory.size} events). The bridge should guarantee a system message is present."
            )
        }

        // Step 1: Ensure initial user message is present
        val events = ensureInitialUserMessage(condensedHistory, initialUserAction, forgottenEventIds)

        // Step 2: Convert events to raw messages with tool call pairing
        val rawMessages = convertEventsToMessages(events)

        // Step 3: Filter orphan tool calls and tool results
        val filtered = filterOrphans(rawMessages)

        // Step 4: Sourcegraph sanitization (system->user, tool->user, alternation, starts with user)
        val sanitized = sanitizeForSourcegraph(filtered)

        // Step 5: Truncate oversized messages
        return truncateMessages(sanitized)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 1: Ensure initial user message
    // ═══════════════════════════════════════════════════════════════════════

    private fun ensureInitialUserMessage(
        events: List<Event>,
        initialUserAction: MessageAction,
        forgottenEventIds: Set<Int>
    ): List<Event> {
        // If the initial user action was forgotten, don't insert it
        if (initialUserAction.id in forgottenEventIds) return events

        // Check if events[1] is already a user MessageAction (events[0] is typically system prompt)
        if (events.size > 1 && events[1] is MessageAction && (events[1] as MessageAction).source == EventSource.USER) {
            return events
        }

        // Also check events[0] — if it's already the initial user message, don't insert
        if (events.isNotEmpty() && events[0] is MessageAction && (events[0] as MessageAction).source == EventSource.USER) {
            return events
        }

        // Insert at index 1 if there's at least one event, otherwise at index 0
        val insertIndex = if (events.isNotEmpty()) 1 else 0
        return events.toMutableList().apply { add(insertIndex, initialUserAction) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 2: Convert events to messages
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Tracks a pending assistant message being accumulated from tool call actions.
     * Multiple ToolActions with the same responseGroupId form a single assistant message
     * with multiple tool_calls.
     */
    private data class PendingAssistant(
        val responseGroupId: String,
        val toolCalls: MutableList<ToolCall> = mutableListOf(),
        var expectedToolCallIds: MutableSet<String> = mutableSetOf()
    )

    private fun convertEventsToMessages(events: List<Event>): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        var pendingAssistant: PendingAssistant? = null

        for (event in events) {
            when (event) {
                is MessageAction -> {
                    // Flush pending assistant if any
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null

                    when (event.source) {
                        EventSource.USER -> messages.add(ChatMessage(role = "user", content = event.content))
                        EventSource.AGENT -> messages.add(ChatMessage(role = "assistant", content = event.content))
                        EventSource.SYSTEM -> messages.add(ChatMessage(role = "system", content = event.content))
                    }
                }

                is SystemMessageAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = event.content))
                }

                is UserSteeringAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "user", content = "<user_steering>\n${event.content}\n</user_steering>"))
                }

                is ToolAction -> {
                    val toolCall = toolActionToToolCall(event)

                    if (pendingAssistant != null && pendingAssistant.responseGroupId == event.responseGroupId) {
                        // Same response group — accumulate
                        pendingAssistant.toolCalls.add(toolCall)
                        pendingAssistant.expectedToolCallIds.add(event.toolCallId)
                    } else {
                        // New response group — flush previous and start new
                        flushPending(pendingAssistant, messages)
                        pendingAssistant = PendingAssistant(
                            responseGroupId = event.responseGroupId,
                            toolCalls = mutableListOf(toolCall),
                            expectedToolCallIds = mutableSetOf(event.toolCallId)
                        )
                    }
                }

                is ToolResultObservation -> {
                    // Flush pending assistant before adding tool result
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null

                    messages.add(ChatMessage(
                        role = "tool",
                        content = event.content,
                        toolCallId = event.toolCallId
                    ))
                }

                is CondensationObservation -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "user", content = event.content))
                }

                is AgentThinkAction -> {
                    // Skip — internal reasoning, not sent to LLM
                }

                is AgentFinishAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "assistant", content = event.finalThought))
                }

                is FactRecordedAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(
                        role = "system",
                        content = "<agent_facts>\n[${event.factType}] ${event.path?.let { "$it: " } ?: ""}${event.content}\n</agent_facts>"
                    ))
                }

                is PlanUpdatedAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(
                        role = "system",
                        content = "<active_plan>\n${event.planJson}\n</active_plan>"
                    ))
                }

                is CondensationAction -> {
                    // Skip — condensation metadata, not sent to LLM
                }

                is CondensationRequestAction -> {
                    // Skip — condensation request, not sent to LLM
                }

                is DelegateAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "assistant", content = "Delegating to ${ event.agentType}: ${event.prompt}"))
                }

                is SkillActivatedAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = "Skill activated: ${event.skillName}\n${event.content}"))
                }

                is SkillDeactivatedAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = "Skill deactivated: ${event.skillName}"))
                }

                is GuardrailRecordedAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = "Guardrail: ${event.rule}"))
                }

                is MentionAction -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "user", content = event.content))
                }

                is ErrorObservation -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = "Error: ${event.content}"))
                }

                is SuccessObservation -> {
                    flushPending(pendingAssistant, messages)
                    pendingAssistant = null
                    messages.add(ChatMessage(role = "system", content = event.content))
                }
            }
        }

        // Flush any remaining pending assistant
        flushPending(pendingAssistant, messages)

        return messages
    }

    private fun flushPending(pending: PendingAssistant?, messages: MutableList<ChatMessage>) {
        if (pending != null && pending.toolCalls.isNotEmpty()) {
            messages.add(ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = pending.toolCalls.toList()
            ))
        }
    }

    private fun toolActionToToolCall(action: ToolAction): ToolCall {
        val (name, arguments) = when (action) {
            is FileReadAction -> "read_file" to """{"path":"${escapeJson(action.path)}"}"""
            is FileEditAction -> "edit_file" to buildString {
                append("""{"path":"${escapeJson(action.path)}"""")
                action.oldStr?.let { append(""","old_str":"${escapeJson(it)}"""") }
                action.newStr?.let { append(""","new_str":"${escapeJson(it)}"""") }
                append("}")
            }
            is CommandRunAction -> "run_command" to buildString {
                append("""{"command":"${escapeJson(action.command)}"""")
                action.cwd?.let { append(""","cwd":"${escapeJson(it)}"""") }
                append("}")
            }
            is SearchCodeAction -> "search_code" to buildString {
                append("""{"query":"${escapeJson(action.query)}"""")
                action.path?.let { append(""","path":"${escapeJson(it)}"""") }
                append("}")
            }
            is DiagnosticsAction -> "diagnostics" to buildString {
                append("{")
                action.path?.let { append(""""path":"${escapeJson(it)}"""") }
                append("}")
            }
            is GenericToolAction -> action.toolName to action.arguments
            is MetaToolAction -> action.toolName to action.arguments
        }

        return ToolCall(
            id = action.toolCallId,
            function = FunctionCall(name = name, arguments = arguments)
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 3: Filter orphans
    // ═══════════════════════════════════════════════════════════════════════

    private fun filterOrphans(messages: List<ChatMessage>): List<ChatMessage> {
        // Collect all tool_call IDs from assistant messages
        val assistantToolCallIds = mutableSetOf<String>()
        for (msg in messages) {
            if (msg.role == "assistant" && msg.toolCalls != null) {
                for (tc in msg.toolCalls) {
                    assistantToolCallIds.add(tc.id)
                }
            }
        }

        // Collect all tool result IDs
        val toolResultIds = mutableSetOf<String>()
        for (msg in messages) {
            val tcId = msg.toolCallId
            if (msg.role == "tool" && tcId != null) {
                toolResultIds.add(tcId)
            }
        }

        return messages.filter { msg ->
            when {
                // Remove assistant messages with tool_calls that have NO matching tool results
                msg.role == "assistant" && msg.toolCalls != null -> {
                    val calls = msg.toolCalls ?: emptyList()
                    calls.any { tc -> tc.id in toolResultIds }
                }
                // Remove tool messages with no matching assistant tool_call
                msg.role == "tool" && msg.toolCallId != null -> {
                    val id = msg.toolCallId ?: ""
                    id in assistantToolCallIds
                }
                else -> true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 4: Sourcegraph sanitization
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Mirrors [com.workflow.orchestrator.core.ai.SourcegraphChatClient.sanitizeMessages]:
     * - system -> user with `<system_instructions>` wrapping, buffered and merged into next user
     * - tool -> user with `<tool_result>` wrapping
     * - Merge consecutive same-role messages
     * - Ensure starts with user
     * - Empty assistant content -> `<tool_calls/>` placeholder
     */
    private fun sanitizeForSourcegraph(messages: List<ChatMessage>): List<ChatMessage> {
        // Phase 1: Convert system and tool roles to user content
        val converted = mutableListOf<ChatMessage>()
        var pendingSystemContent: String? = null

        for (msg in messages) {
            when (msg.role) {
                "system" -> {
                    val content = msg.content ?: ""
                    pendingSystemContent = if (pendingSystemContent != null) {
                        "$pendingSystemContent\n$content"
                    } else content
                }
                "tool" -> {
                    // Flush pending system content first
                    if (pendingSystemContent != null) {
                        converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
                        pendingSystemContent = null
                    }
                    val toolContent = "<tool_result${msg.toolCallId?.let { " tool_use_id=\"$it\"" } ?: ""}>\n${msg.content ?: ""}\n</tool_result>"
                    converted.add(ChatMessage(role = "user", content = toolContent))
                }
                "user" -> {
                    val content = if (pendingSystemContent != null) {
                        val merged = "<system_instructions>\n$pendingSystemContent\n</system_instructions>\n\n<user_message>\n${msg.content ?: ""}\n</user_message>"
                        pendingSystemContent = null
                        merged
                    } else {
                        msg.content ?: ""
                    }
                    converted.add(ChatMessage(role = "user", content = content))
                }
                "assistant" -> {
                    if (pendingSystemContent != null) {
                        converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
                        pendingSystemContent = null
                    }
                    converted.add(msg)
                }
                else -> converted.add(msg)
            }
        }

        // Flush remaining system content
        if (pendingSystemContent != null) {
            converted.add(ChatMessage(role = "user", content = "<system_instructions>\n$pendingSystemContent\n</system_instructions>"))
        }

        // Phase 2: Merge consecutive same-role messages
        val merged = mutableListOf<ChatMessage>()
        for (msg in converted) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role && last.toolCalls == null && msg.toolCalls == null) {
                merged[merged.size - 1] = ChatMessage(
                    role = msg.role,
                    content = "${last.content ?: ""}\n\n${msg.content ?: ""}"
                )
            } else {
                merged.add(msg)
            }
        }

        // Phase 3: Handle empty/null content
        merged.removeAll { msg ->
            msg.content.isNullOrBlank() && msg.toolCalls.isNullOrEmpty()
        }

        // Assistant messages with tool calls but null/empty content -> placeholder
        for (i in merged.indices) {
            val msg = merged[i]
            if (msg.role == "assistant" && msg.content.isNullOrBlank() && !msg.toolCalls.isNullOrEmpty()) {
                merged[i] = ChatMessage(
                    role = "assistant",
                    content = "<tool_calls/>",
                    toolCalls = msg.toolCalls
                )
            }
        }

        // Phase 4: Ensure conversation starts with "user"
        if (merged.isNotEmpty() && merged.first().role != "user") {
            merged.add(0, ChatMessage(role = "user", content = "[Context follows]"))
        }

        // Phase 5: Final consecutive same-role merge (after removals may have created new adjacencies)
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

    // ═══════════════════════════════════════════════════════════════════════
    // Step 5: Truncate oversized messages
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Middle-truncation: keeps first 60% + last 40% of content, with a truncation marker.
     * Applied per-message when content exceeds [maxMessageChars].
     */
    private fun truncateMessages(messages: List<ChatMessage>): List<ChatMessage> {
        return messages.map { msg ->
            val content = msg.content
            if (content != null && content.length > maxMessageChars) {
                val keepFront = (maxMessageChars * 0.6).toInt()
                val keepBack = (maxMessageChars * 0.4).toInt()
                val truncated = content.substring(0, keepFront) +
                    "\n\n... [${content.length - keepFront - keepBack} chars truncated] ...\n\n" +
                    content.substring(content.length - keepBack)
                msg.copy(content = truncated)
            } else {
                msg
            }
        }
    }
}
