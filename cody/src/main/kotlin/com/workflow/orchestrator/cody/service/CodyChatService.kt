package com.workflow.orchestrator.cody.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await
import java.io.File

class CodyChatService(private val project: Project) {

    private val log = Logger.getInstance(CodyChatService::class.java)

    /**
     * Send a single-turn chat message with optional context items.
     * Creates a new chat session, sends one message, returns the response.
     */
    suspend fun generateCommitMessage(
        prompt: String,
        contextItems: List<ContextFile> = emptyList()
    ): String? {
        val session = startSession(contextItems)
        return sendMessage(session, prompt, contextItems)
    }

    /**
     * Three-step prompt chain for high-quality commit message generation.
     *
     * Step 1 (Understand): Deep semantic analysis of the diff — what changed,
     *   why it matters, what the developer's intent was. No formatting yet.
     *
     * Step 2 (Draft): Generate a commit message from the analysis, guided by
     *   the project's recent commit history for style consistency.
     *
     * Step 3 (Refine): Self-review the draft against quality criteria and
     *   output the final polished message.
     *
     * All steps share the same chat session so context accumulates.
     */
    suspend fun generateCommitMessageChained(
        diff: String,
        contextItems: List<ContextFile> = emptyList(),
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList()
    ): String? {
        val session = startSession(contextItems)

        // ── Step 1: Deep semantic analysis ──
        val analysisPrompt = buildString {
            appendLine("You are an expert software engineer analyzing a code change. Study this diff carefully and provide a deep semantic analysis.")
            appendLine()
            appendLine("Respond in exactly this format:")
            appendLine()
            appendLine("INTENT: <What was the developer trying to accomplish? One sentence.>")
            appendLine("TYPE: <feat|fix|refactor|perf|test|docs|style|build|ci|chore>")
            appendLine("SCOPE: <primary module/component changed, lowercase>")
            appendLine("BREAKING: <yes|no>")
            appendLine("SEMANTIC_CHANGES:")
            appendLine("- <What logical change was made and why it was necessary>")
            appendLine("SIDE_EFFECTS: <Any behavioral changes, API changes, or things downstream consumers should know. 'none' if none.>")
            appendLine()
            appendLine("Analysis rules:")
            appendLine("- INTENT is the most important field — understand the developer's goal, not just the mechanics")
            appendLine("- TYPE: 'fix' = something was broken, 'feat' = new capability, 'refactor' = same behavior better code")
            appendLine("- SCOPE: derive from the domain (e.g., 'auth', 'billing', 'pr-list'), NOT from file paths")
            appendLine("- SEMANTIC_CHANGES: describe behavioral/logical changes, not line-level edits")
            appendLine("  BAD:  'modify line 42 in AuthService.kt'")
            appendLine("  BAD:  'update the authenticate method'")
            appendLine("  GOOD: 'validate session tokens expire within 120 minutes to prevent indefinite sessions'")
            appendLine("- Group related edits into ONE bullet (e.g., adding a field + its getter + its test = one change)")
            appendLine("- If the change is trivial (import, typo, version bump), say so plainly")
            if (filesSummary.isNotBlank()) {
                appendLine()
                appendLine("Changed files: $filesSummary")
            }
            if (recentCommits.isNotEmpty()) {
                appendLine()
                appendLine("Recent commits (for context — understand how this change relates to recent work):")
                recentCommits.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Consider: Is this change continuing a recent effort? Fixing something from a recent commit? A separate concern?")
            }
            appendLine()
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }

        log.info("[Cody:CommitChain] Step 1/3: Semantic analysis (${analysisPrompt.length} chars)")
        val analysis = sendMessage(session, analysisPrompt, contextItems)
        if (analysis == null) {
            log.warn("[Cody:CommitChain] Step 1 failed")
            return null
        }
        log.info("[Cody:CommitChain] Step 1 result:\n$analysis")

        // ── Step 2: Draft the commit message ──
        val draftPrompt = buildString {
            appendLine("Now write a git commit message based on your analysis. Output ONLY the raw commit message.")
            appendLine()
            appendLine("Format (follow this EXACTLY):")
            appendLine("```")
            appendLine("type(scope): imperative summary line")
            appendLine("")
            appendLine("- First bullet explaining a specific change")
            appendLine("- Second bullet explaining another change")
            appendLine("- Each bullet starts with imperative verb and explains what+why")
            appendLine("```")
            appendLine()
            appendLine("Rules:")
            appendLine("- Line 1: type(scope): summary — imperative mood, max 72 chars, no trailing period")
            appendLine("- Line 2: always blank")
            appendLine("- Line 3+: bullet points using '- ' prefix, one per logical change")
            appendLine("- ALWAYS use bullet points in the body, even for a single change")
            appendLine("- Each bullet starts with an imperative verb (add, fix, remove, update, extract, replace, etc.)")
            appendLine("- Bullets describe WHAT changed and WHY — not file paths or implementation details")
            appendLine("- Keep bullets concise — one line each when possible")
            if (ticketId.isNotBlank()) {
                appendLine("- Prefix the summary: $ticketId type(scope): description")
            }
            appendLine()
            appendLine("Anti-patterns to avoid:")
            appendLine("- Summary that just repeats the type: BAD 'refactor: refactor auth code'")
            appendLine("- Bullets that describe file edits: BAD '- update AuthService.kt'")
            appendLine("- Passive voice: BAD '- timeout was added' → GOOD '- add session timeout'")
            appendLine("- Starting with 'This commit' or 'This change'")
            appendLine("- Wrapping in markdown code blocks")

            if (recentCommits.isNotEmpty()) {
                appendLine()
                appendLine("Match the style of these recent commits from this project:")
                recentCommits.take(8).forEach { appendLine("  $it") }
            }
        }

        log.info("[Cody:CommitChain] Step 2/3: Drafting commit message")
        val draft = sendMessage(session, draftPrompt)
        if (draft == null) {
            log.warn("[Cody:CommitChain] Step 2 failed")
            return null
        }
        log.info("[Cody:CommitChain] Step 2 draft:\n$draft")

        // ── Step 3: Self-review and refine ──
        val refinePrompt = buildString {
            appendLine("Review the commit message you just wrote. Check each criterion and fix any issues.")
            appendLine("Output ONLY the final corrected commit message — no commentary, no explanations.")
            appendLine()
            appendLine("Checklist:")
            appendLine("1. Summary line ≤72 chars? (count it)")
            appendLine("2. Imperative mood in summary AND bullets? ('add', not 'adds' or 'added')")
            appendLine("3. Summary captures the essence — would a reviewer understand the change from this line alone?")
            appendLine("4. Body uses bullet points with '- ' prefix? (ALWAYS bullets, never prose paragraphs)")
            appendLine("5. Each bullet starts with an imperative verb?")
            appendLine("6. No redundancy between summary and body?")
            appendLine("7. No file paths or line numbers in the message?")
            appendLine("8. If multiple bullets, are they truly separate logical changes? (merge if not)")
            appendLine("9. No markdown formatting wrapping the message?")
            appendLine()
            appendLine("If the draft is already good, output it unchanged. If it needs fixes, output the corrected version.")
        }

        log.info("[Cody:CommitChain] Step 3/3: Self-review and refinement")
        val refined = sendMessage(session, refinePrompt)
        if (refined == null) {
            log.warn("[Cody:CommitChain] Step 3 failed, using draft")
            return cleanMessage(draft)
        }

        val result = cleanMessage(refined)
        log.info("[Cody:CommitChain] Final commit message:\n$result")
        return result
    }

    /**
     * Three-step prompt chain for high-quality PR description generation.
     *
     * Step 1 (Understand): Analyze the diff + commits to understand the full scope
     *   of the PR — what changed, why, and what reviewers need to know.
     *
     * Step 2 (Draft): Generate a structured markdown PR description with
     *   Summary, Changes, Testing, and optional Breaking Changes sections.
     *
     * Step 3 (Refine): Self-review against PR description quality criteria.
     */
    suspend fun generatePrDescriptionChained(
        diff: String,
        commitMessages: List<String> = emptyList(),
        contextItems: List<ContextFile> = emptyList(),
        ticketId: String = "",
        ticketSummary: String = "",
        ticketDescription: String = "",
        sourceBranch: String = "",
        targetBranch: String = ""
    ): String? {
        val session = startSession(contextItems)

        // ── Step 1: Understand the PR scope ──
        val analysisPrompt = buildString {
            appendLine("You are reviewing a pull request. Analyze ALL the changes to understand the full scope.")
            appendLine()
            appendLine("Respond in exactly this format:")
            appendLine()
            appendLine("PURPOSE: <What is this PR trying to accomplish? One clear sentence.>")
            appendLine("TYPE: <feature|bugfix|refactor|performance|infrastructure|documentation>")
            appendLine("RISK_LEVEL: <low|medium|high> — how risky is this change for reviewers?")
            appendLine("SEMANTIC_CHANGES:")
            appendLine("- <What logical change was made and its impact>")
            appendLine("TESTING_NOTES: <What should be tested? What edge cases exist?>")
            appendLine("BREAKING_CHANGES: <Any API/behavior changes that affect consumers? 'none' if none>")
            appendLine("REVIEWER_ATTENTION: <What specific areas should reviewers focus on?>")
            appendLine()
            appendLine("Analysis rules:")
            appendLine("- PURPOSE should be understandable by someone who hasn't seen the code")
            appendLine("- SEMANTIC_CHANGES: describe behavioral changes, not file-level edits")
            appendLine("- Group related edits into single bullets")
            appendLine("- TESTING_NOTES: be specific about scenarios, not generic 'test the feature'")
            if (ticketId.isNotBlank()) {
                appendLine()
                appendLine("Jira ticket: $ticketId — $ticketSummary")
                if (ticketDescription.isNotBlank()) {
                    appendLine("Ticket description: ${ticketDescription.take(500)}")
                }
            }
            appendLine()
            appendLine("Branch: $sourceBranch → $targetBranch")
            if (commitMessages.isNotEmpty()) {
                appendLine()
                appendLine("Commits in this PR:")
                commitMessages.take(20).forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("Diff (may be truncated):")
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }

        log.info("[Cody:PrChain] Step 1/3: Understanding PR scope (${analysisPrompt.length} chars)")
        val analysis = sendMessage(session, analysisPrompt, contextItems)
        if (analysis == null) {
            log.warn("[Cody:PrChain] Step 1 failed")
            return null
        }
        log.info("[Cody:PrChain] Step 1 result:\n$analysis")

        // ── Step 2: Draft the PR description ──
        val draftPrompt = buildString {
            appendLine("Now write a pull request description in markdown based on your analysis.")
            appendLine("Output ONLY the markdown — no preamble, no wrapping code blocks.")
            appendLine()
            appendLine("Use this structure:")
            appendLine()
            appendLine("## Summary")
            appendLine("2-3 sentences explaining what this PR does and why. Written for someone")
            appendLine("who will read this in a PR review email — clear, concise, no jargon.")
            appendLine()
            appendLine("## Changes")
            appendLine("Bullet list of specific changes. Each bullet describes a behavioral change,")
            appendLine("not a file edit. Use imperative mood ('Add', not 'Added').")
            appendLine()
            appendLine("## Testing")
            appendLine("How this was tested and what reviewers should verify.")
            appendLine("Use checkboxes: - [ ] for TODO items, - [x] for completed items.")
            appendLine()
            if (ticketId.isNotBlank()) {
                appendLine("## Jira")
                appendLine("Link to $ticketId with the ticket summary.")
                appendLine()
            }
            appendLine("If there are breaking changes, add a ## Breaking Changes section.")
            appendLine()
            appendLine("Quality rules:")
            appendLine("- Summary should be understandable without reading the code")
            appendLine("- Changes bullets focus on WHAT and WHY, not HOW")
            appendLine("- Use `backticks` for class names, method names, config keys")
            appendLine("- Be concise — reviewers scan, they don't read novels")
            appendLine("- NO file paths in the Changes section")
        }

        log.info("[Cody:PrChain] Step 2/3: Drafting PR description")
        val draft = sendMessage(session, draftPrompt)
        if (draft == null) {
            log.warn("[Cody:PrChain] Step 2 failed")
            return null
        }
        log.info("[Cody:PrChain] Step 2 draft:\n${draft.take(300)}")

        // ── Step 3: Self-review and refine ──
        val refinePrompt = buildString {
            appendLine("Review the PR description you just wrote. Check each criterion and fix issues.")
            appendLine("Output ONLY the final corrected markdown — no commentary.")
            appendLine()
            appendLine("Checklist:")
            appendLine("1. Would a reviewer understand the PR from the Summary alone?")
            appendLine("2. Are Changes bullets behavioral/logical, not file-level edits?")
            appendLine("3. Is Testing specific enough to actually follow?")
            appendLine("4. No redundancy between Summary and Changes?")
            appendLine("5. No unnecessary sections (remove empty sections)?")
            appendLine("6. Professional tone — no 'I', no casual language?")
            appendLine("7. Markdown formatting correct (headers, bullets, checkboxes)?")
            appendLine()
            appendLine("If the draft is already good, output it unchanged.")
        }

        log.info("[Cody:PrChain] Step 3/3: Self-review and refinement")
        val refined = sendMessage(session, refinePrompt)
        if (refined == null) {
            log.warn("[Cody:PrChain] Step 3 failed, using draft")
            return cleanMessage(draft)
        }

        val result = cleanMessage(refined)
        log.info("[Cody:PrChain] Final PR description (${result.length} chars)")
        return result
    }

    /** Strip markdown wrappers the LLM might add despite instructions. */
    private fun cleanMessage(raw: String): String {
        return raw
            .replace(Regex("^```[a-z]*\\n?"), "")
            .replace(Regex("\\n?```$"), "")
            .trim()
    }

    /**
     * Start a chat session and notify the agent about context files.
     */
    private suspend fun startSession(contextItems: List<ContextFile> = emptyList()): ChatSession {
        val server = CodyAgentProviderService.getInstance(project).ensureRunning()

        // Notify agent about context files
        for (item in contextItems) {
            val filePath = item.uri.fsPath
            try {
                val file = File(filePath)
                if (file.exists() && file.isFile) {
                    val content = file.readText()
                    server.textDocumentDidOpen(
                        ProtocolTextDocument(
                            uri = "file://$filePath",
                            content = content
                        )
                    )
                    log.info("[Cody:Chat] didOpen: $filePath (${content.length} chars)")
                }
            } catch (e: Exception) {
                log.warn("[Cody:Chat] Failed to open context file $filePath: ${e.message}")
            }
        }

        val chatId = server.chatNew().await()
        log.info("[Cody:Chat] Chat session created: $chatId")
        return ChatSession(chatId, server)
    }

    /**
     * Send a message in an existing chat session. Returns the assistant's response.
     */
    private suspend fun sendMessage(
        session: ChatSession,
        text: String,
        contextItems: List<ContextFile> = emptyList()
    ): String? {
        val response = session.server.chatSubmitMessage(
            ChatSubmitParams(
                id = session.chatId,
                message = ChatMessage(
                    text = text,
                    contextItems = contextItems
                )
            )
        ).await()

        return response.messages.lastOrNull { it.speaker == "assistant" }?.text
    }

    private data class ChatSession(
        val chatId: String,
        val server: com.workflow.orchestrator.cody.agent.CodyAgentServer
    )
}
