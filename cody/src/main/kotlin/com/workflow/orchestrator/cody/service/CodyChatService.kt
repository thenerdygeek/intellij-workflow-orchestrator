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
     * Single-pass commit message generation leveraging Claude Opus/Sonnet
     * via Cody CLI. Provides rich context (diff, recent commits, PSI code
     * intelligence) in one prompt — no chaining needed with a capable model.
     */
    suspend fun generateCommitMessageChained(
        diff: String,
        contextItems: List<ContextFile> = emptyList(),
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = ""
    ): String? {
        val session = startSession(contextItems)

        val prompt = buildString {
            appendLine("Generate a git commit message for the following changes. Output ONLY the raw commit message — no commentary, no markdown code blocks.")
            appendLine()

            // ── Format specification ──
            appendLine("FORMAT (follow exactly):")
            appendLine("type(scope): imperative summary (max 72 chars, no trailing period)")
            appendLine("")
            appendLine("- Bullet point per logical change, imperative verb, explains what+why")
            appendLine("- Group related edits into one bullet")
            appendLine()

            // ── Rules ──
            appendLine("RULES:")
            appendLine("- type: feat|fix|refactor|perf|test|docs|style|build|ci|chore")
            appendLine("- scope: domain area (e.g., auth, billing, pr-list), NOT file paths")
            appendLine("- Summary: imperative mood ('add' not 'added'), captures the essence")
            appendLine("- Body: ALWAYS bullet points with '- ' prefix, even for single changes")
            appendLine("- Bullets describe behavioral/semantic changes, not line-level edits")
            appendLine("- If trivial (typo, import, version bump), body can be one short bullet")
            if (ticketId.isNotBlank()) {
                appendLine("- Prefix summary with ticket: $ticketId type(scope): description")
            }
            appendLine()
            appendLine("AVOID: repeating the type in summary, file paths in bullets, passive voice,")
            appendLine("'This commit/change' phrasing, wrapping in code blocks")

            // ── Recent commits for context + style ──
            if (recentCommits.isNotEmpty()) {
                appendLine()
                appendLine("RECENT COMMITS (understand how this change relates to recent work; match this project's style):")
                recentCommits.forEach { appendLine("  $it") }
            }

            // ── Code intelligence context ──
            if (codeContext.isNotBlank()) {
                appendLine()
                appendLine("CODE CONTEXT (classes, annotations, and structure of changed files):")
                appendLine(codeContext)
            }

            // ── Changed files ──
            if (filesSummary.isNotBlank()) {
                appendLine()
                appendLine("CHANGED FILES: $filesSummary")
            }

            // ── The diff ──
            appendLine()
            appendLine("DIFF:")
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }

        log.info("[Cody:Commit] Single-pass generation (${prompt.length} chars, ${contextItems.size} context items)")
        val result = sendMessage(session, prompt, contextItems)
        if (result == null) {
            log.warn("[Cody:Commit] Generation failed — no response")
            return null
        }

        val cleaned = cleanMessage(result)
        log.info("[Cody:Commit] Generated commit message:\n$cleaned")
        return cleaned
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
