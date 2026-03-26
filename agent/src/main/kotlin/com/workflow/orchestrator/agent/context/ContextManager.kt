package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Manages the conversation history for a worker session.
 *
 * Implements two-threshold compression (Factory.ai pattern):
 * - T_max: When total tokens reach this threshold, compression triggers
 * - T_retained: After compression, total tokens are reduced to this level
 *
 * Anchored summaries: Only newly dropped messages are summarized.
 * Already-summarized spans are preserved as-is.
 *
 * When [brain] is provided, [compressWithLlm] can use LLM-powered summarization
 * for tool results (which contain high-information content like file paths, line
 * numbers, and code changes). The synchronous [compress] method always uses the
 * truncation summarizer.
 *
 * THREAD SAFETY: Not thread-safe. Must be accessed from a single coroutine context
 * (the ReAct loop runs sequentially). Do not call addMessage/compress/reconcile concurrently.
 */
class ContextManager(
    private val maxInputTokens: Int = com.workflow.orchestrator.agent.settings.AgentSettings.DEFAULTS.maxInputTokens,
    private val brain: LlmBrain? = null,
    private val tMaxRatio: Double = 0.85,
    private val tRetainedRatio: Double = 0.70,
    private val toolResultMaxTokens: Int = 4000,
    private var reservedTokens: Int = 0,
    private val summarizer: (List<ChatMessage>) -> String = { msgs ->
        val sb = StringBuilder("## Compressed Context Summary\n")
        sb.appendLine("WARNING: This is a lossy summary. Details may be missing or truncated.")
        sb.appendLine()
        val filePaths = mutableSetOf<String>()
        val filePathRegex = Regex("""[\w./\\-]+\.\w{1,10}""")
        for (msg in msgs) {
            val content = msg.content ?: continue
            // Extract file paths from all message types
            filePathRegex.findAll(content).forEach { match ->
                val path = match.value
                if (path.contains('/') || path.contains('\\')) {
                    filePaths.add(path)
                }
            }
            when (msg.role) {
                "user" -> sb.appendLine("- User: ${content.take(500)}")
                "assistant" -> {
                    if (content.length > 5) sb.appendLine("- Agent: ${content.take(500)}")
                }
                "tool" -> {
                    val unwrapped = content
                        .removePrefix("<external_data>\n")
                        .removeSuffix("\n</external_data>")
                        .removePrefix("<external_data>")
                        .removeSuffix("</external_data>")
                    val previewLines = unwrapped.lines().take(10)
                    val preview = previewLines.joinToString("\n").take(500)
                    sb.appendLine("- Tool result:")
                    sb.appendLine(preview)
                    val totalLines = unwrapped.lines().size
                    if (totalLines > 10) {
                        sb.appendLine("  ... (${totalLines - 10} more lines)")
                    }
                }
                "system" -> {} // Skip system messages in summary
            }
            if (sb.length > 6000) break
        }
        if (filePaths.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### Referenced Files")
            filePaths.take(30).forEach { sb.appendLine("- $it") }
        }
        sb.toString().take(8000)
    }
) {
    companion object {
        private const val COMPRESSION_BOUNDARY = "[CONTEXT COMPRESSED] Messages above this point are a lossy summary of earlier work.\n" +
            "- Line numbers, code snippets, and variable names may be approximate\n" +
            "- ALWAYS re-read a file before editing it, even if the summary mentions it\n" +
            "- If you need exact details from the summary, verify with a tool call first\n" +
            "- Treat summarized content as a starting point for re-investigation, not as ground truth"

        /** Tools whose results must never be pruned — they represent irreplaceable work. */
        val PROTECTED_TOOLS = setOf(
            "agent", "delegate_task", "create_plan", "update_plan_step",
            "save_memory", "activate_skill", "ask_questions", "attempt_completion"
        )

        /** Minimum token savings to justify pruning — skip results smaller than this. */
        private const val PRUNE_MINIMUM_TOKENS = 200
    }

    private val messages = mutableListOf<ChatMessage>()
    private val anchoredSummaries = mutableListOf<String>()
    /** Disk spillover for full tool outputs (OpenCode pattern). */
    var toolOutputStore: ToolOutputStore? = null
    /** Dedicated plan anchor — survives compression, updated in-place. */
    private var planAnchor: ChatMessage? = null
    /** Whether an active plan exists in the context. */
    val hasPlanAnchor: Boolean get() = planAnchor != null
    /** Dedicated skill anchor — survives compression, updated on skill activation/deactivation. */
    private var skillAnchor: ChatMessage? = null
    /** Dedicated mention anchor — file content from @ mentions, survives compression. */
    private var mentionAnchor: ChatMessage? = null
    /** Dedicated facts anchor — compression-proof structured knowledge from FactsStore. */
    private var factsAnchor: ChatMessage? = null
    /** Dedicated guardrails anchor — compression-proof learned constraints. */
    private var guardrailsAnchor: ChatMessage? = null
    /** Facts store for recording verified findings that survive compression. */
    var factsStore: FactsStore? = null
    private var totalTokens = 0

    /** Effective budget after subtracting reserved tokens (tool defs, system prompt overhead, buffer). */
    private val effectiveBudget: Int get() = maxInputTokens - reservedTokens

    private val tMax: Int get() = (effectiveBudget * tMaxRatio).toInt()
    private val tRetained: Int get() = (effectiveBudget * tRetainedRatio).toInt()

    /** Current token usage across all messages. */
    val currentTokens: Int get() = totalTokens

    /** Current message count. */
    val messageCount: Int get() = messages.size

    /** The effective max input tokens (for budget warning calculations). */
    val effectiveMaxInputTokens: Int get() = maxInputTokens

    /**
     * Recalculate reserved tokens when the tool set changes.
     * This adjusts the effective budget and compression thresholds
     * so they stay accurate as tools expand during a session.
     */
    fun updateReservedTokens(newReserved: Int) {
        reservedTokens = newReserved
        // effectiveBudget, tMax, tRetained are computed properties — they auto-update
    }

    /**
     * Set or update the anchored plan summary. Dedicated slot separate from
     * the messages list — always included in getMessages(), never dropped by compress().
     */
    fun setPlanAnchor(message: ChatMessage?) {
        planAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Set or update the anchored skill summary. Dedicated slot separate from
     * the messages list — always included in getMessages(), never dropped by compress().
     */
    fun setSkillAnchor(message: ChatMessage?) {
        skillAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Set or update the anchored mention context (file content, folder trees from @ mentions).
     * Dedicated slot separate from the messages list — always included in getMessages(),
     * never dropped by compress().
     */
    fun setMentionAnchor(message: ChatMessage?) {
        mentionAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Update the facts anchor from the current FactsStore state.
     * Called after each fact is recorded to keep the anchor in sync.
     * The facts anchor is a compression-proof system message containing
     * all verified facts from the session.
     */
    fun updateFactsAnchor() {
        val store = factsStore ?: return
        val contextStr = store.toContextString()
        factsAnchor = if (contextStr.isNotEmpty()) {
            ChatMessage(role = "system", content = contextStr)
        } else null
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * Set or update the anchored guardrails context. Dedicated compression-proof slot
     * containing learned constraints from previous sessions.
     */
    fun setGuardrailsAnchor(message: ChatMessage?) {
        guardrailsAnchor = message
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /** Get all messages including any summary prefixes. */
    fun getMessages(): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()

        // Prepend anchored summaries as a system message if any exist
        if (anchoredSummaries.isNotEmpty()) {
            result.add(ChatMessage(
                role = "system",
                content = anchoredSummaries.joinToString("\n\n")
            ))
        }

        // Anchors that provide background context go BEFORE conversation history
        skillAnchor?.let { result.add(it) }
        mentionAnchor?.let { result.add(it) }
        factsAnchor?.let { result.add(it) }
        guardrailsAnchor?.let { result.add(it) }

        result.addAll(messages)

        // Plan anchor goes AFTER conversation history for maximum attention
        // (U-shaped attention: LLMs attend most to beginning and end of context)
        planAnchor?.let { result.add(it) }

        return result
    }

    /** Add a message to the conversation history. Triggers compression if budget exceeded. */
    fun addMessage(message: ChatMessage) {
        // Use the list-based estimator which correctly counts tool call tokens,
        // not just content (assistant messages with tool_calls have null content)
        val tokenCount = TokenEstimator.estimate(listOf(message))
        messages.add(message)
        totalTokens += tokenCount

        // Reconcile every 20 messages to prevent drift from summary growth
        if (messages.size % 20 == 0 && anchoredSummaries.isNotEmpty()) {
            totalTokens = TokenEstimator.estimate(getMessages())
        }

        if (totalTokens > tMax) {
            // Phase 0: Smart pruning — eliminate genuinely redundant content first (no info loss)
            SmartPruner.pruneAll(messages)
            totalTokens = TokenEstimator.estimate(getMessages())

            if (totalTokens > tMax) {
                // Phase 1: Prune old tool results (fast, no LLM)
                pruneOldToolResults()
                // Phase 2: Full compression if still over budget
                if (totalTokens > tMax) {
                    compress()
                }
            }
        }
    }

    /**
     * Add a tool result to context. Full content sent to LLM (capped at 2000 lines / 50KB).
     * Full content also saved to disk for re-reads after pruning.
     *
     * Following OpenCode's pattern: LLM sees everything on first read.
     * Phase 1 pruning (pruneOldToolResults) handles aging — no premature compression.
     */
    fun addToolResult(toolCallId: String, content: String, summary: String) {
        // Save full content to disk (for re-reads after pruning)
        val diskPath = toolOutputStore?.save(toolCallId, content)

        // Cap at 2000 lines / 50KB (OpenCode's limits) — NOT the old 4K token compression
        val cappedContent = toolOutputStore?.capContent(content, diskPath) ?: content

        // Wrap in external_data tags for injection defense
        val wrapped = "<external_data>\n$cappedContent\n</external_data>"
        addMessage(ChatMessage(role = "tool", content = wrapped, toolCallId = toolCallId))
    }

    /** Add an assistant message (LLM response). */
    fun addAssistantMessage(message: ChatMessage) {
        addMessage(message)
    }

    /**
     * Compress the conversation to fit within T_retained.
     * Strategy: Summarize the oldest non-system messages and replace them
     * with a single summary message. Keep the most recent messages intact.
     *
     * Called automatically when addMessage() pushes totalTokens over tMax,
     * or explicitly by the session when BudgetEnforcer signals COMPRESS.
     */
    fun compress() {
        if (messages.size <= 2) return // Nothing to compress

        // Find how many messages to drop to get below T_retained
        var tokensToRemove = totalTokens - tRetained
        val messagesToSummarize = mutableListOf<ChatMessage>()
        val indicesToRemove = mutableListOf<Int>()

        for (i in messages.indices) {
            if (tokensToRemove <= 0) break
            val msg = messages[i]
            // Only protect the FIRST system message (original prompt at index 0).
            // Allow other system messages (LoopGuard reminders, budget warnings) to be compressed.
            if (msg.role == "system" && i == 0) continue

            val msgTokens = TokenEstimator.estimate(listOf(msg))
            messagesToSummarize.add(msg)
            indicesToRemove.add(i)
            tokensToRemove -= msgTokens
        }

        if (messagesToSummarize.isEmpty()) return

        // Orphan protection: if we're dropping an assistant message with tool_calls,
        // also drop the corresponding tool result messages to avoid orphans.
        val droppedToolCallIds = mutableSetOf<String>()
        for (idx in indicesToRemove) {
            val msg = messages[idx]
            val tcs = msg.toolCalls
            if (msg.role == "assistant" && tcs != null) {
                tcs.forEach { tc -> droppedToolCallIds.add(tc.id) }
            }
        }
        if (droppedToolCallIds.isNotEmpty()) {
            for (i in messages.indices) {
                if (i in indicesToRemove) continue
                val msg = messages[i]
                if (msg.role == "tool" && msg.toolCallId in droppedToolCallIds) {
                    messagesToSummarize.add(msg)
                    indicesToRemove.add(i)
                }
            }
        }

        // Create anchored summary of dropped messages.
        // Uses LLM-powered summarization when brain is available, otherwise falls back
        // to the default truncation summarizer.
        val summary = summarizeMessages(messagesToSummarize)
        anchoredSummaries.add(summary + "\n\n" + COMPRESSION_BOUNDARY)
        // Cap at 3 summaries — consolidate older ones
        if (anchoredSummaries.size > 3) {
            val consolidated = anchoredSummaries.joinToString("\n---\n")
            anchoredSummaries.clear()
            anchoredSummaries.add(consolidated.take(4000))
        }

        // Remove compressed messages (reverse order to preserve indices)
        indicesToRemove.reversed().forEach { messages.removeAt(it) }

        // Inject post-compression continuation message so the LLM knows context was compressed
        injectPostCompressionGuidance()

        // Recalculate total tokens
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /**
     * LLM-powered compression: uses the LLM to summarize tool results (which contain
     * high-information content like file paths, line numbers, code changes, and errors)
     * while falling back to the truncation summarizer for non-tool messages or on error.
     *
     * Unlike [compress] (which is synchronous and called automatically from [addMessage]),
     * this method is a suspend function that must be called explicitly by the session loop.
     */
    suspend fun compressWithLlm(llmBrain: LlmBrain) {
        if (messages.size <= 2) return

        var tokensToRemove = totalTokens - tRetained
        val messagesToDrop = mutableListOf<ChatMessage>()
        val indicesToRemove = mutableListOf<Int>()

        for (i in messages.indices) {
            if (tokensToRemove <= 0) break
            val msg = messages[i]
            // Only protect the FIRST system message (original prompt at index 0).
            // Allow other system messages (LoopGuard reminders, budget warnings) to be compressed.
            if (msg.role == "system" && i == 0) continue

            val msgTokens = TokenEstimator.estimate(listOf(msg))
            messagesToDrop.add(msg)
            indicesToRemove.add(i)
            tokensToRemove -= msgTokens
        }

        if (messagesToDrop.isEmpty()) return

        // Orphan protection: if we're dropping an assistant message with tool_calls,
        // also drop the corresponding tool result messages to avoid orphans.
        val droppedToolCallIds = mutableSetOf<String>()
        for (idx in indicesToRemove) {
            val msg = messages[idx]
            val tcs = msg.toolCalls
            if (msg.role == "assistant" && tcs != null) {
                tcs.forEach { tc -> droppedToolCallIds.add(tc.id) }
            }
        }
        if (droppedToolCallIds.isNotEmpty()) {
            for (i in messages.indices) {
                if (i in indicesToRemove) continue
                val msg = messages[i]
                if (msg.role == "tool" && msg.toolCallId in droppedToolCallIds) {
                    messagesToDrop.add(msg)
                    indicesToRemove.add(i)
                }
            }
        }

        val hasToolResults = messagesToDrop.any { it.role == "tool" }

        val summary = if (hasToolResults) {
            // Tool results contain high-information content — use LLM with structured template
            try {
                val promptContent = messagesToDrop.mapNotNull { it.content }.joinToString("\n---\n")
                val summarizePrompt = """
IMPORTANT: Mark any detail you are uncertain about with [APPROX].
Do NOT invent line numbers or code patterns — only include what is explicitly stated.

Summarize the conversation so far into a structured continuation prompt.
Use this exact format:

## Goal
What is the user trying to accomplish?

## Instructions
Key instructions or constraints the user specified.

## Discoveries
Important findings from code exploration, tool results, and analysis.
Include specific file paths, line numbers, and code patterns found.

## Accomplished
What has been completed so far. List specific changes made.

## Relevant Files
Files that were read, edited, or referenced. Include paths.

Be concise but preserve ALL technical details — file paths, line numbers,
error messages, code snippets, and specific findings. These details are
critical for continuing the task.
""".trimIndent()
                val summarizationPrompt = listOf(
                    ChatMessage(
                        role = "system",
                        content = summarizePrompt
                    ),
                    ChatMessage(
                        role = "user",
                        content = promptContent
                    )
                )

                when (val result = llmBrain.chat(summarizationPrompt, null, 500, null)) {
                    is ApiResult.Success -> {
                        val llmSummary = result.data.choices.firstOrNull()?.message?.content
                        if (!llmSummary.isNullOrBlank()) {
                            "Previous context summary (LLM): $llmSummary"
                        } else {
                            // Empty LLM response — fall back
                            summarizer(messagesToDrop)
                        }
                    }
                    is ApiResult.Error -> {
                        // LLM failed — fall back to truncation (never throw)
                        summarizer(messagesToDrop)
                    }
                }
            } catch (_: Exception) {
                // Catch-all safety net — fall back to truncation
                summarizer(messagesToDrop)
            }
        } else {
            // No tool results — truncation is sufficient and cheaper
            summarizer(messagesToDrop)
        }

        anchoredSummaries.add(summary + "\n\n" + COMPRESSION_BOUNDARY)
        // Cap at 3 summaries — consolidate older ones
        if (anchoredSummaries.size > 3) {
            val consolidated = anchoredSummaries.joinToString("\n---\n")
            anchoredSummaries.clear()
            anchoredSummaries.add(consolidated.take(4000))
        }

        // Remove compressed messages (reverse order to preserve indices)
        indicesToRemove.reversed().forEach { messages.removeAt(it) }

        // Inject post-compression continuation message so the LLM knows context was compressed
        injectPostCompressionGuidance()

        // Recalculate total tokens
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /** Metadata about a tool call that triggered a tool result. */
    private data class ToolCallMeta(val toolName: String, val arguments: String)

    /**
     * Walk backward from a tool result message index to find the assistant message
     * containing the tool_call that triggered it (matched by toolCallId).
     */
    private fun findToolCallMetadata(toolResultIndex: Int): ToolCallMeta? {
        val toolCallId = messages[toolResultIndex].toolCallId ?: return null
        return findToolCallMetadata(toolResultIndex, toolCallId)
    }

    /**
     * Walk backward from a tool result message index to find the assistant message
     * containing the tool_call that triggered it (matched by explicit toolCallId).
     */
    private fun findToolCallMetadata(toolResultIndex: Int, toolCallId: String?): ToolCallMeta? {
        if (toolCallId == null) return null
        for (j in (toolResultIndex - 1) downTo 0) {
            val candidate = messages[j]
            if (candidate.role != "assistant") continue
            val matchingCall = candidate.toolCalls?.find { it.id == toolCallId }
            if (matchingCall != null) {
                return ToolCallMeta(
                    toolName = matchingCall.function.name,
                    arguments = matchingCall.function.arguments
                )
            }
        }
        return null
    }

    /**
     * Tier 2 compression: keep first 20 + last 5 lines of a tool result.
     * For medium-age results that are outside the full-protection window but
     * inside the compressed-protection window. Preserves key context (headers,
     * signatures, final output) while dropping the middle.
     */
    private fun compressToolResult(content: String?, meta: ToolCallMeta?): String {
        val raw = (content ?: "")
            .removePrefix("<external_data>").removePrefix("\n")
            .removeSuffix("</external_data>").removeSuffix("\n")
        val lines = raw.lines()
        if (lines.size <= 30) return raw
        val head = lines.take(20).joinToString("\n")
        val tail = lines.takeLast(5).joinToString("\n")
        val omitted = lines.size - 25
        return buildString {
            appendLine("[Compressed tool result — ${lines.size} lines, showing first 20 + last 5]")
            if (meta != null) appendLine("Tool: ${meta.toolName}")
            appendLine(head)
            appendLine("\n[... $omitted lines omitted ...]")
            appendLine(tail)
        }.trimEnd()
    }

    /**
     * Build a metadata-rich placeholder for a pruned tool result.
     * Contains tool name, arguments (truncated), content preview, disk path, and recovery hint.
     */
    private fun buildRichPlaceholder(
        originalContent: String?,
        meta: ToolCallMeta?,
        toolCallId: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[Tool result pruned to save context]")

        // Tool name and arguments
        val toolName = meta?.toolName ?: "unknown_tool"
        sb.appendLine("Tool: $toolName")
        if (meta != null) {
            val truncatedArgs = if (meta.arguments.length > 300) {
                meta.arguments.take(300) + "..."
            } else {
                meta.arguments
            }
            sb.appendLine("Args: $truncatedArgs")
        }

        // Content preview — first 5 lines of the unwrapped original content
        if (!originalContent.isNullOrBlank()) {
            val unwrapped = originalContent
                .removePrefix("<external_data>\n")
                .removeSuffix("\n</external_data>")
                .removePrefix("<external_data>")
                .removeSuffix("</external_data>")
            val previewLines = unwrapped.lines().take(5)
            if (previewLines.isNotEmpty()) {
                sb.appendLine("Preview:")
                previewLines.forEach { sb.appendLine("  $it") }
                val totalLines = unwrapped.lines().size
                if (totalLines > 5) {
                    sb.appendLine("  ... (${totalLines - 5} more lines)")
                }
            }
        }

        // Disk path for recovery
        val diskPath = toolCallId?.let { toolOutputStore?.getPath(it) }
        if (diskPath != null) {
            sb.appendLine("Full output saved: $diskPath")
        }

        // Recovery hint
        val recoveryHint = buildRecoveryHint(toolName, meta?.arguments, diskPath)
        if (recoveryHint != null) {
            sb.appendLine("Recovery: $recoveryHint")
        }

        return "<external_data>${sb.toString().trimEnd()}</external_data>"
    }

    /**
     * Build an actionable recovery hint based on the tool that was pruned.
     */
    private fun buildRecoveryHint(toolName: String, arguments: String?, diskPath: String?): String? {
        // Try to extract a file path from arguments for file-related tools
        val filePath = arguments?.let {
            val pathMatch = Regex(""""(?:path|file_path|file)"\s*:\s*"([^"]+)"""").find(it)
            pathMatch?.groupValues?.get(1)
        }

        return when {
            toolName == "read_file" && filePath != null ->
                "use read_file on '$filePath' to re-read"
            toolName == "search_code" ->
                "re-run search_code with the same query to refresh results"
            toolName == "glob_files" ->
                "re-run glob_files with the same pattern to refresh results"
            toolName == "run_command" ->
                "re-run the command if the output is still needed"
            toolName == "diagnostics" ->
                "re-run diagnostics to get current results"
            diskPath != null ->
                "use read_file on '$diskPath' to recover full output"
            else -> null
        }
    }

    /**
     * Phase 1 compression: prune old tool results in-place with 3-tier degradation.
     *
     * Walking backward from the most recent messages:
     * - **Tier 1 (FULL):** Within [protectedTokens] — kept as-is.
     * - **Tier 2 (COMPRESSED):** Within [compressedProtectionTokens] — first 20 + last 5 lines kept.
     * - **Tier 3 (METADATA):** Beyond both windows — replaced with rich placeholder
     *   containing tool name, arguments, content preview, and recovery hints.
     *
     * Small results (< [PRUNE_MINIMUM_TOKENS]) and protected tools are never degraded.
     */
    fun pruneOldToolResults(
        protectedTokens: Int = 40_000,
        compressedProtectionTokens: Int = 60_000
    ) {
        var fullProtected = 0
        var compressedProtected = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role != "tool") continue
            val msgTokens = TokenEstimator.estimate(listOf(msg))

            // Tier 1: FULL — within full protection window
            if (fullProtected + msgTokens <= protectedTokens) {
                fullProtected += msgTokens
                continue
            }

            // Skip small results and protected tools at all tiers
            if (msgTokens < PRUNE_MINIMUM_TOKENS) continue
            val toolCallId = msg.toolCallId
            val meta = findToolCallMetadata(i, toolCallId)
            if (meta != null && meta.toolName in PROTECTED_TOOLS) {
                fullProtected += msgTokens
                continue
            }

            // Tier 2: COMPRESSED — within compressed protection window
            if (compressedProtected + msgTokens <= compressedProtectionTokens) {
                compressedProtected += msgTokens
                val compressed = compressToolResult(msg.content, meta)
                messages[i] = ChatMessage(
                    role = "tool",
                    content = "<external_data>$compressed</external_data>",
                    toolCallId = toolCallId
                )
                continue
            }

            // Tier 3: METADATA — full replacement with rich placeholder
            val richPlaceholder = buildRichPlaceholder(msg.content, meta, toolCallId)
            messages[i] = ChatMessage(
                role = "tool",
                content = richPlaceholder,
                toolCallId = toolCallId
            )
        }
        totalTokens = TokenEstimator.estimate(getMessages())
    }

    /** Summarize messages using the truncation summarizer. */
    private fun summarizeMessages(messagesToSummarize: List<ChatMessage>): String {
        return summarizer(messagesToSummarize)
    }

    /**
     * Inject a system message after compression to orient the LLM.
     * Tells the model that context was compressed, earlier messages are lossy,
     * and it should re-read files before editing.
     */
    private fun injectPostCompressionGuidance() {
        val continuationMsg = ChatMessage(
            role = "system",
            content = "Context was compressed to stay within limits. Earlier messages are now a lossy summary. " +
                "Your key findings are preserved in <agent_facts>. ALWAYS re-read files before editing. " +
                "Continue from where you left off."
        )
        messages.add(continuationMsg)
    }

    /**
     * Add a system message to the conversation.
     * Used for injecting warnings, guidance, or context notes.
     */
    fun addSystemMessage(content: String) {
        addMessage(ChatMessage(role = "system", content = content))
    }

    /** Reset the context (for a new worker session). */
    fun reset() {
        messages.clear()
        anchoredSummaries.clear()
        planAnchor = null
        skillAnchor = null
        mentionAnchor = null
        factsAnchor = null
        guardrailsAnchor = null
        factsStore?.clear()
        totalTokens = 0
    }

    /**
     * Reconcile the heuristic token count with the actual count from the API.
     *
     * Called after each LLM response with the server-reported prompt_tokens.
     * The API's tokenizer is authoritative — our character-based heuristic
     * (text.length / 3.5) can be 20-40% off, especially for code and JSON.
     *
     * This calibration ensures compression thresholds and budget nudges
     * fire at the right time.
     */
    fun reconcileWithActualTokens(actualPromptTokens: Int) {
        if (actualPromptTokens > 0) {
            // The API's promptTokens IS the authoritative context size.
            // It includes system prompt + tool definitions + all messages.
            // No need to subtract reservedTokens — the API already counted everything.
            // Our totalTokens should track the same thing the API tracks.
            totalTokens = actualPromptTokens
        }
    }

    /** Get remaining token budget (accounting for reserved tokens). */
    fun remainingBudget(): Int = effectiveBudget - totalTokens

    /** Check if budget is critically low (<10% remaining). */
    fun isBudgetCritical(): Boolean = remainingBudget() < (effectiveBudget * 0.10)

    /**
     * Remove the oldest system warning message from the internal messages list.
     * Operates on [messages] (not [getMessages]) to avoid index mismatch with anchors.
     *
     * @return true if a warning was removed, false if none found
     */
    fun removeOldestSystemWarning(): Boolean {
        val idx = messages.indexOfFirst { msg ->
            msg.role == "system" && msg.content?.contains("system_warning") == true
        }
        if (idx >= 0) {
            messages.removeAt(idx)
            totalTokens = TokenEstimator.estimate(getMessages())
            return true
        }
        return false
    }

    /**
     * Count the number of system warning messages in the internal messages list.
     * Used to cap warnings and prevent context bloat from accumulated warnings.
     */
    fun countSystemWarnings(): Int = messages.count { msg ->
        msg.role == "system" && msg.content?.contains("system_warning") == true
    }
}
