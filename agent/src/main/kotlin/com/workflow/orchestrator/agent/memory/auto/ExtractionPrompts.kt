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

Analyze this completed session and decide whether anything is worth remembering across future sessions. Most sessions produce no memory — that's correct. Save 0 or 1 items. More than 3 items is suspicious.

CURRENT CORE MEMORY (existing facts — do not duplicate, prefer replace):
$coreMemorySection

CONVERSATION:
$truncated

WHAT TO SAVE: Information a future session could not derive by reading the current code, running the current tests, or reading git log / CLAUDE.md / README. If the answer is in the repo, skip it.

SKIP examples (do not save these):
- "Project uses Spring Boot 3.2" → visible in pom.xml
- "The fix for the NPE was a null check on getEmail()" → visible in the commit
- "UserService imports SessionManager" → visible in the file
- "User ran tests with pytest -v" → trivial, not a preference

SAVE examples:
- "Don't mock the database in integration tests. User said: 'our mocked tests passed but the prod migration still failed last quarter.'"
- "Deploy process: Bamboo plan PROJ-DEPLOY-PROD, not the CI plan. (as of $currentDate)"
- "User's Jira board: https://jira.corp/secure/RapidBoard.jspa?rapidView=812"
- "Non-obvious fix: when Gradle build fails with 'unable to find toolchain', set org.gradle.java.installations.auto-detect=false in gradle.properties."

EXTRACT ONLY THESE CATEGORIES:
1. User rules and corrections → "patterns" block.
   Any time the user pushed back, course-corrected, or said 'don't/stop/never/actually/instead/I'd rather'. Quote the user directly when possible. Include the reason ONLY if the user stated it — never invent a reason.
2. User & project facts not in code → "user" or "project" block.
   Role, preferences, deadlines, team conventions, external URLs, dashboard pointers. Use absolute dates (today is $currentDate).
3. Non-obvious error → fix mappings → archival with 2-4 lowercase hyphen-separated tags. Only if the fix is not visible in git log.

RULES:
- Default to saving nothing. 0 items is the normal answer.
- Before saving a core memory update, check the existing core memory above. Prefer "replace" with old_content approximately matching the superseded entry. Use "append" only when no existing entry is close. When in doubt between append and replace, prefer replace.
- When saving, quote the user where possible — their phrasing is more trustworthy than a paraphrase.
- Archival tags: 2-4, lowercase, hyphen-separated (e.g. "build-system", "gradle", "toolchain").

Return JSON:
{"core_memory_updates":[{"block":"...","action":"append"|"replace","content":"...","old_content":"...or null"}],"archival_inserts":[{"content":"...","tags":["tag1","tag2"]}]}"""
    }
}
