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
    suspend fun generateCommitMessageChained(
        diff: String,
        contextItems: List<ContextFile> = emptyList(),
        ticketId: String = "",
        filesSummary: String = ""
    ): String? {
        val session = startSession(contextItems)

        // ── Step 1: Analyze the diff ──
        val analysisPrompt = buildString {
            appendLine("Analyze this git diff and respond with a structured analysis in exactly this format:")
            appendLine()
            appendLine("TYPE: <one of: feat, fix, refactor, perf, test, docs, style, build, ci, chore>")
            appendLine("SCOPE: <the primary module, component, or area changed — use lowercase, e.g., auth, api, dashboard>")
            appendLine("BREAKING: <yes or no>")
            appendLine("CHANGES:")
            appendLine("- <imperative verb> <what was changed and why>")
            appendLine("- <imperative verb> <what was changed and why>")
            appendLine("WHY: <one sentence explaining the motivation — why was this change necessary?>")
            appendLine()
            appendLine("Rules:")
            appendLine("- Each CHANGE bullet must start with an imperative verb (add, fix, remove, update, rename, extract, etc.)")
            appendLine("- Focus on semantics, not file names or line numbers")
            appendLine("- Group related modifications into a single bullet")
            appendLine("- If only one logical change, use only one bullet")
            if (filesSummary.isNotBlank()) {
                appendLine()
                appendLine("Changed files: $filesSummary")
            }
            appendLine()
            appendLine("Git diff:")
            appendLine("```")
            appendLine(diff)
            appendLine("```")
        }

        log.info("[Cody:CommitChain] Step 1: Sending analysis prompt (${analysisPrompt.length} chars)")
        val analysis = sendMessage(session, analysisPrompt, contextItems)
        if (analysis == null) {
            log.warn("[Cody:CommitChain] Step 1 failed — no analysis returned")
            return null
        }
        log.info("[Cody:CommitChain] Step 1 result:\n$analysis")

        // ── Step 2: Generate the commit message from the analysis ──
        val generatePrompt = buildString {
            appendLine("Using your analysis above, generate a git commit message. Output ONLY the commit message, nothing else.")
            appendLine()
            appendLine("Format rules:")
            appendLine("1. Summary line: `type(scope): imperative description` — max 72 characters")
            appendLine("2. Blank line")
            appendLine("3. Body: bullet points with `- ` prefix, one per logical change")
            appendLine("4. Each bullet uses imperative mood and explains WHAT and WHY")
            appendLine("5. If only one change, still use one bullet in the body")
            appendLine("6. If BREAKING is yes, add `BREAKING CHANGE: description` after the bullets")
            if (ticketId.isNotBlank()) {
                appendLine("7. Prefix the summary line with the ticket ID: $ticketId")
                appendLine("   Example: `$ticketId feat(auth): add session timeout`")
            }
            appendLine()
            appendLine("Do NOT wrap in markdown code blocks. Do NOT add quotes. Output the raw commit message only.")
        }

        log.info("[Cody:CommitChain] Step 2: Sending generation prompt")
        val commitMessage = sendMessage(session, generatePrompt)
        if (commitMessage == null) {
            log.warn("[Cody:CommitChain] Step 2 failed — no commit message returned")
            return null
        }

        // Clean up any markdown wrapping the LLM might add despite instructions
        val cleaned = commitMessage
            .removePrefix("```\n").removePrefix("```")
            .removeSuffix("\n```").removeSuffix("```")
            .trim()

        log.info("[Cody:CommitChain] Final commit message:\n$cleaned")
        return cleaned
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
