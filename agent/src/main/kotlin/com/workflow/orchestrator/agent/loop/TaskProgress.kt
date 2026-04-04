package com.workflow.orchestrator.agent.loop

/**
 * Tracks task progress as a markdown checklist.
 *
 * Faithful port of Cline's focus-chain task progress tracking:
 * - The LLM includes a `task_progress` parameter in tool calls
 * - Progress is a markdown checklist: "- [x] done item" / "- [ ] pending item"
 * - Parsed using the same regex as Cline's focus-chain-utils.ts
 * - Survives compaction (re-injected into system prompt after context trim)
 *
 * Cline stores this as `currentFocusChainChecklist` in TaskState and
 * persists it to a markdown file on disk via writeFocusChainToDisk().
 * We store it in ContextManager and include it in the system prompt.
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/shared/focus-chain-utils.ts">Cline focus-chain-utils.ts</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/task/focus-chain/utils.ts">Cline focus-chain utils.ts</a>
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/prompts/system-prompt/components/task_progress.ts">Cline task_progress.ts</a>
 */
data class TaskProgress(
    val items: List<TaskProgressItem> = emptyList()
) {
    /**
     * Render the checklist as markdown.
     * Matches Cline's format: "- [x] completed" / "- [ ] incomplete".
     */
    fun toMarkdown(): String = items.joinToString("\n") { item ->
        val checkbox = if (item.completed) "[x]" else "[ ]"
        "- $checkbox ${item.description}"
    }

    /**
     * Return a copy with the item at [index] marked completed or incomplete.
     */
    fun withUpdatedItem(index: Int, completed: Boolean): TaskProgress {
        if (index !in items.indices) return this
        val updated = items.toMutableList()
        updated[index] = updated[index].copy(completed = completed)
        return TaskProgress(updated)
    }

    /**
     * Return a copy with a new incomplete item appended.
     */
    fun withNewItem(description: String): TaskProgress {
        return TaskProgress(items + TaskProgressItem(description = description, completed = false))
    }

    /**
     * Count of completed items.
     * Port of Cline's parseFocusChainListCounts().completedItems.
     */
    val completedCount: Int get() = items.count { it.completed }

    /**
     * Total number of items.
     * Port of Cline's parseFocusChainListCounts().totalItems.
     */
    val totalCount: Int get() = items.size

    /**
     * True if all items are completed.
     */
    val isComplete: Boolean get() = items.isNotEmpty() && completedCount == totalCount

    /**
     * True if there are no items.
     */
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        /**
         * Parse a markdown checklist into TaskProgress.
         *
         * Port of Cline's parseFocusChainItem() regex:
         * Matches "- [x] text", "- [X] text", "- [ ] text"
         * with flexible spacing (Cline's FOCUS_CHAIN_ITEM_REGEX).
         */
        private val ITEM_REGEX = Regex("""^-\s*\[([ xX])]\s*(.+)$""")

        /**
         * Parse markdown checklist text into a TaskProgress.
         *
         * Port of Cline's focus-chain-utils.ts parsing logic:
         * - isFocusChainItem(): checks if line starts with "- [ ]", "- [x]", or "- [X]"
         * - parseFocusChainItem(): extracts checked status and text via regex
         *
         * Non-checklist lines are silently ignored (matches Cline behavior).
         */
        fun fromMarkdown(markdown: String): TaskProgress {
            if (markdown.isBlank()) return TaskProgress()
            val items = markdown.lines().mapNotNull { line ->
                val match = ITEM_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
                val completed = match.groupValues[1].let { it == "x" || it == "X" }
                val description = match.groupValues[2].trim()
                TaskProgressItem(description = description, completed = completed)
            }
            return TaskProgress(items)
        }
    }
}

/**
 * A single item in the task progress checklist.
 */
data class TaskProgressItem(
    val description: String,
    val completed: Boolean = false
)
