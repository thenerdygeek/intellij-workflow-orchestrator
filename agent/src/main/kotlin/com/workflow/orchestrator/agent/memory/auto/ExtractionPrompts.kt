package com.workflow.orchestrator.agent.memory.auto

/**
 * Prompt template for session-end memory extraction LLM calls.
 *
 * Sent to a cheap model (Haiku) for structured extraction.
 * Must be concise (minimizes cost) and produce valid JSON.
 */
object ExtractionPrompts {

    /** Number of initial lines to preserve (goal context). */
    private const val KEEP_FIRST_LINES = 5

    /** Number of recent lines to preserve (current state). */
    private const val KEEP_LAST_LINES = 35

    /** Total max preserved lines when truncating. */
    internal const val MAX_CONVERSATION_LINES = KEEP_FIRST_LINES + KEEP_LAST_LINES

    /** Max characters per conversation line. */
    private const val MAX_LINE_LENGTH = 300

    const val EXTRACTION_SYSTEM_MESSAGE =
        "You extract structured memory from conversations. " +
            "Return ONLY valid JSON matching the schema. No markdown, no explanation. " +
            "If nothing is worth saving, return {\"core_memory_updates\":[],\"archival_inserts\":[]}."

    /**
     * Build prompt for session-end extraction.
     *
     * This single prompt catches everything: corrections, confirmations,
     * patterns, decisions, references, error resolutions. No need for
     * real-time detection — one pass over the full conversation is more
     * accurate than regex mid-session.
     *
     * @param conversationLines role-prefixed conversation lines ("user: ...", "assistant: ...")
     * @param currentCoreMemory current core memory blocks (label -> value)
     * @param currentDate today's date (ISO YYYY-MM-DD), used so the LLM can convert
     *   relative dates ("tomorrow", "next week") into absolute calendar dates when
     *   extracting project state.
     */
    fun sessionEndPrompt(
        conversationLines: List<String>,
        currentCoreMemory: Map<String, String>,
        currentDate: String
    ): String {
        // Preserve first N (goal context) + last M (current state) for long sessions.
        // Short sessions (<= MAX_CONVERSATION_LINES) use all messages verbatim.
        val preserved = if (conversationLines.size <= MAX_CONVERSATION_LINES) {
            conversationLines
        } else {
            val firstPart = conversationLines.take(KEEP_FIRST_LINES)
            val lastPart = conversationLines.takeLast(KEEP_LAST_LINES)
            val omittedCount = conversationLines.size - MAX_CONVERSATION_LINES
            firstPart + listOf("... [$omittedCount messages omitted] ...") + lastPart
        }
        val truncated = preserved
            .map { it.take(MAX_LINE_LENGTH) }
            .joinToString("\n")

        val coreMemorySection = if (currentCoreMemory.any { it.value.isNotBlank() }) {
            currentCoreMemory.entries
                .filter { it.value.isNotBlank() }
                .joinToString("\n") { "  ${it.key}: ${it.value}" }
        } else {
            "  (empty)"
        }

        return """Today's date is $currentDate.

Analyze this completed session and extract learnings worth remembering across future sessions.

CURRENT CORE MEMORY (do not duplicate what's already here):
$coreMemorySection

CONVERSATION:
$truncated

EXTRACT THESE CATEGORIES:
1. **User corrections** — "don't do X", "stop doing Y", "never use Z" → save as behavioral rule to "patterns" block
2. **User confirmations** — "yes exactly", "perfect", acceptance of non-obvious approach → save validated approach to "patterns" block
3. **User profile** — role, expertise, preferences, how they like to work → "user" block
4. **Project state** — active goals, key decisions, deadlines, blockers (convert relative dates to absolute) → "project" block
5. **Conventions/patterns** — project-specific rules not in docs → "patterns" block
6. **Error resolutions** — stack trace → fix mapping → archival with tags
7. **API gotchas / configuration patterns** → archival with tags
8. **Decisions with rationale** — why approach A was chosen over B → archival with tags
9. **External references** — dashboard URLs, board locations, tool pointers → archival with "reference" tag

RULES:
- Only extract what matters across sessions. Skip ephemeral task details.
- Don't duplicate information already in core memory.
- For core updates use action "append" (add new fact) or "replace" (update stale fact — requires "old_content" matching existing text exactly).
- For archival entries include 2-4 lowercase tags.
- Be selective — 2-5 items total is typical. Zero is fine if nothing is worth saving.

Return JSON:
{"core_memory_updates":[{"block":"...","action":"append"|"replace","content":"...","old_content":"...or null"}],"archival_inserts":[{"content":"...","tags":["tag1","tag2"]}]}"""
    }
}
