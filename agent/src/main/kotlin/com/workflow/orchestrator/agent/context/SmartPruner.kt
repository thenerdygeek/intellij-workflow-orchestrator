package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall

/**
 * Stateless smart pruning strategies that eliminate genuinely redundant content
 * before resorting to lossy compression. Three strategies:
 *
 * 1. **Deduplicate file reads** — if the same file is read twice (without an intervening edit),
 *    replace the older read's tool result with a dedup marker.
 * 2. **Purge failed tool inputs** — after N turns, truncate the large arguments of tool calls
 *    that resulted in errors.
 * 3. **Supersede confirmed writes** — if a write is confirmed by a subsequent read of the same
 *    file, compact the write's tool result.
 *
 * All strategies operate in-place on the message list and return estimated tokens saved.
 */
object SmartPruner {

    private val pathRegex = Regex(""""(?:path|file_path|file)"\s*:\s*"([^"]+)"""")

    private val errorPatterns = listOf(
        "ERROR",
        "error:",
        "Failed",
    )

    private val errorContainsPatterns = listOf(
        "old_string not found",
        "Exception:",
        "Command failed with exit code",
    )

    /**
     * Deduplicate file reads: if the same file path is read twice, replace the older
     * read's tool result with a dedup marker. Resets tracking when an edit to the same
     * path is seen (post-edit reads are NOT duplicates).
     *
     * @return estimated tokens saved
     */
    fun deduplicateFileReads(messages: MutableList<ChatMessage>): Int {
        // Map: file path -> index of the most recent read_file tool result for that path
        val lastReadIndex = mutableMapOf<String, Int>()
        var tokensSaved = 0

        for (i in messages.indices) {
            val msg = messages[i]

            // Check if this is a tool call (assistant message with toolCalls)
            if (msg.role == "assistant" && msg.toolCalls != null) {
                for (tc in msg.toolCalls) {
                    val path = extractPath(tc.function.arguments) ?: continue
                    when (tc.function.name) {
                        "edit_file" -> {
                            // Edit resets read tracking for this path
                            lastReadIndex.remove(path)
                        }
                    }
                }
                continue
            }

            // Check if this is a tool result for a read_file call
            if (msg.role == "tool" && msg.toolCallId != null) {
                val toolCall = findToolCall(messages, i, msg.toolCallId) ?: continue
                if (toolCall.name != "read_file") continue

                val path = extractPath(toolCall.arguments) ?: continue

                val previousIndex = lastReadIndex[path]
                if (previousIndex != null) {
                    // Replace the older read with a dedup marker
                    val oldContent = messages[previousIndex].content ?: ""
                    val oldTokens = TokenEstimator.estimate(oldContent)
                    val marker = "[Deduplicated — '$path' was re-read later]"
                    messages[previousIndex] = messages[previousIndex].copy(content = marker)
                    tokensSaved += oldTokens - TokenEstimator.estimate(marker)
                }
                lastReadIndex[path] = i
            }
        }

        return maxOf(0, tokensSaved)
    }

    /**
     * Purge failed tool inputs: find tool results that look like errors. After
     * [turnsAfterError] messages have passed since the error, truncate the large
     * arguments in the corresponding assistant tool_call message.
     *
     * Only truncates if args > 500 chars.
     *
     * @return estimated tokens saved
     */
    fun purgeFailedToolInputs(messages: MutableList<ChatMessage>, turnsAfterError: Int = 4): Int {
        var tokensSaved = 0

        // First pass: identify error tool results and their indices
        val errorIndices = mutableListOf<Int>()
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role == "tool" && isErrorResult(msg.content)) {
                errorIndices.add(i)
            }
        }

        // Second pass: for errors old enough, truncate their tool call args
        for (errorIndex in errorIndices) {
            val turnsAfter = messages.size - 1 - errorIndex
            if (turnsAfter < turnsAfterError) continue

            val toolCallId = messages[errorIndex].toolCallId ?: continue

            // Find the assistant message containing this tool call
            for (j in (errorIndex - 1) downTo 0) {
                val candidate = messages[j]
                if (candidate.role != "assistant") continue
                val toolCalls = candidate.toolCalls ?: continue

                val matchIndex = toolCalls.indexOfFirst { it.id == toolCallId }
                if (matchIndex < 0) continue

                val tc = toolCalls[matchIndex]
                if (tc.function.arguments.length <= 500) break

                val oldTokens = TokenEstimator.estimate(tc.function.arguments)
                val truncatedArgs = tc.function.arguments.take(200) + "... [args truncated — tool call failed]"
                val newTokens = TokenEstimator.estimate(truncatedArgs)
                tokensSaved += oldTokens - newTokens

                // Replace the tool call with truncated args
                val newToolCalls = toolCalls.toMutableList()
                newToolCalls[matchIndex] = tc.copy(
                    function = FunctionCall(
                        name = tc.function.name,
                        arguments = truncatedArgs
                    )
                )
                messages[j] = candidate.copy(toolCalls = newToolCalls)
                break
            }
        }

        return maxOf(0, tokensSaved)
    }

    /**
     * Supersede confirmed writes: if an edit_file tool result indicates success ("applied"
     * or "success"), and a subsequent read_file for the same path appears, compact the
     * write's tool result.
     *
     * @return estimated tokens saved
     */
    fun supersedeConfirmedWrites(messages: MutableList<ChatMessage>): Int {
        // Track successful writes: path -> list of (toolResultIndex)
        data class WriteRecord(val index: Int, val path: String)

        val pendingWrites = mutableListOf<WriteRecord>()
        var tokensSaved = 0

        for (i in messages.indices) {
            val msg = messages[i]

            if (msg.role == "tool" && msg.toolCallId != null) {
                val toolCall = findToolCall(messages, i, msg.toolCallId) ?: continue

                when (toolCall.name) {
                    "edit_file" -> {
                        val content = msg.content ?: ""
                        val contentLower = content.lowercase()
                        if (contentLower.contains("applied") || contentLower.contains("success")) {
                            val path = extractPath(toolCall.arguments)
                            if (path != null) {
                                pendingWrites.add(WriteRecord(i, path))
                            }
                        }
                    }
                    "read_file" -> {
                        val path = extractPath(toolCall.arguments) ?: continue
                        // Compact all pending writes for this path
                        val toCompact = pendingWrites.filter { it.path == path }
                        for (writeRecord in toCompact) {
                            val oldContent = messages[writeRecord.index].content ?: ""
                            val oldTokens = TokenEstimator.estimate(oldContent)
                            val marker = "[Write confirmed by subsequent read — edit to '${writeRecord.path}' was applied successfully.]"
                            messages[writeRecord.index] = messages[writeRecord.index].copy(content = marker)
                            tokensSaved += oldTokens - TokenEstimator.estimate(marker)
                        }
                        pendingWrites.removeAll(toCompact)
                    }
                }
            }
        }

        return maxOf(0, tokensSaved)
    }

    /**
     * Run all three pruning strategies and return total estimated tokens saved.
     */
    fun pruneAll(messages: MutableList<ChatMessage>, turnsAfterError: Int = 4): Int {
        var total = 0
        total += deduplicateFileReads(messages)
        total += purgeFailedToolInputs(messages, turnsAfterError)
        total += supersedeConfirmedWrites(messages)
        return total
    }

    /**
     * Walk backward from a tool result message index to find the FunctionCall
     * that triggered it (matched by toolCallId).
     */
    fun findToolCall(messages: List<ChatMessage>, toolResultIndex: Int, toolCallId: String): FunctionCall? {
        for (j in (toolResultIndex - 1) downTo 0) {
            val candidate = messages[j]
            if (candidate.role != "assistant") continue
            val matchingCall = candidate.toolCalls?.find { it.id == toolCallId }
            if (matchingCall != null) {
                return matchingCall.function
            }
        }
        return null
    }

    /** Extract a file path from tool call arguments JSON. */
    private fun extractPath(arguments: String): String? {
        return pathRegex.find(arguments)?.groupValues?.get(1)
    }

    /** Check if a tool result content looks like an error. */
    private fun isErrorResult(content: String?): Boolean {
        if (content == null) return false
        val stripped = content
            .removePrefix("<external_data>\n")
            .removePrefix("<external_data>")
            .trimStart()

        for (pattern in errorPatterns) {
            if (stripped.startsWith(pattern)) return true
        }
        for (pattern in errorContainsPatterns) {
            if (stripped.contains(pattern)) return true
        }
        return false
    }
}
