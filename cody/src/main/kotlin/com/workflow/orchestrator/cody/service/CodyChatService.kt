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
     * Multi-turn prompt chain for structured commit message generation.
     *
     * Step 1 (Analyze): Send the diff with analysis instructions.
     *   The LLM categorizes changes, identifies scope, and lists modifications.
     *
     * Step 2 (Generate): Using the analysis context from Step 1,
     *   generate a properly formatted conventional commit message.
     *
     * Both steps share the same chatId so the agent retains context.
     */
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
            appendLine("Format:")
            appendLine("- Line 1: type(scope): summary in imperative mood (max 50 chars ideal, 72 hard limit)")
            appendLine("- Line 2: blank")
            appendLine("- Lines 3+: body explaining WHY this change was made, not WHAT (the diff shows what)")
            appendLine("  Use bullet points (- prefix) only if there are multiple distinct logical changes.")
            appendLine("  For a single change, write a short paragraph instead of a bullet.")
            appendLine()
            if (ticketId.isNotBlank()) {
                appendLine("Prefix the summary: $ticketId type(scope): description")
            }
            appendLine()
            appendLine("Quality rules:")
            appendLine("- Summary uses imperative mood: 'add' not 'added', 'fix' not 'fixed'")
            appendLine("- Summary does NOT just repeat the type: BAD 'refactor: refactor auth code'")
            appendLine("- Body explains the motivation/reasoning, not implementation details")
            appendLine("- If the change is trivial, the body can be omitted entirely")
            appendLine("- NO markdown code blocks around the output")
            appendLine("- NO trailing period on the summary line")
            appendLine("- NO 'This commit...' or 'This change...' phrasing")

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
            appendLine("2. Imperative mood? ('add', not 'adds' or 'added')")
            appendLine("3. Summary captures the essence — would a reviewer understand the change from this line alone?")
            appendLine("4. Body explains WHY, not WHAT? (no 'changed X to Y' — explain the reason)")
            appendLine("5. No redundancy between summary and body?")
            appendLine("6. No file paths or line numbers in the message?")
            appendLine("7. If multiple bullets, are they truly separate logical changes? (merge if not)")
            appendLine("8. Does it match the project's commit style?")
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

        val final = cleanMessage(refined)
        log.info("[Cody:CommitChain] Final commit message:\n$final")
        return final
    }

    /** Strip markdown wrappers the LLM might add despite instructions. */
    private fun cleanMessage(raw: String): String {
        return raw
            .removePrefix("```\n").removePrefix("```")
            .removeSuffix("\n```").removeSuffix("```")
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
