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
     * Single-pass PR description generation leveraging Claude Opus/Sonnet
     * via Cody CLI. Same architecture as commit message generation —
     * rich context in one prompt, no chaining needed.
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

        val prompt = buildString {
            appendLine("Generate a pull request description in markdown. Output ONLY the markdown — no preamble, no wrapping code blocks.")
            appendLine()

            // ── Structure ──
            appendLine("STRUCTURE (follow exactly):")
            appendLine()
            appendLine("## Summary")
            appendLine("2-3 sentences: what this PR does and why. Written for someone reading a PR review email.")
            appendLine()
            appendLine("## Changes")
            appendLine("- Bullet per logical change, imperative mood, describes behavioral change not file edit")
            appendLine()
            appendLine("## Testing")
            appendLine("- [ ] Checkbox items for what reviewers should verify")
            if (ticketId.isNotBlank()) {
                appendLine()
                appendLine("## Jira")
                appendLine("$ticketId: $ticketSummary")
            }
            appendLine()

            // ── Rules ──
            appendLine("RULES:")
            appendLine("- Summary understandable without reading the code")
            appendLine("- Changes bullets: WHAT and WHY, not HOW or file paths")
            appendLine("- Use `backticks` for class/method/config names")
            appendLine("- Testing: specific scenarios, not generic 'test the feature'")
            appendLine("- Be concise — reviewers scan, don't read novels")
            appendLine("- If breaking changes exist, add ## Breaking Changes section")
            appendLine("- Omit empty sections")
            appendLine()
            appendLine("AVOID: file paths in Changes, passive voice, 'This PR' phrasing,")
            appendLine("redundancy between Summary and Changes, wrapping in code blocks")

            // ── Jira context ──
            if (ticketId.isNotBlank()) {
                appendLine()
                appendLine("JIRA TICKET: $ticketId — $ticketSummary")
                if (ticketDescription.isNotBlank()) {
                    appendLine("Ticket description: ${ticketDescription.take(500)}")
                }
            }

            // ── Branch info ──
            if (sourceBranch.isNotBlank()) {
                appendLine()
                appendLine("BRANCH: $sourceBranch → $targetBranch")
            }

            // ── Commits ──
            if (commitMessages.isNotEmpty()) {
                appendLine()
                appendLine("COMMITS IN THIS PR:")
                commitMessages.take(20).forEach { appendLine("  - $it") }
            }

            // ── Diff ──
            appendLine()
            appendLine("DIFF:")
            appendLine("```diff")
            appendLine(diff)
            appendLine("```")
        }

        log.info("[Cody:PrDesc] Single-pass generation (${prompt.length} chars, ${contextItems.size} context items)")
        val result = sendMessage(session, prompt, contextItems)
        if (result == null) {
            log.warn("[Cody:PrDesc] Generation failed — no response")
            return null
        }

        val cleaned = cleanMessage(result)
        log.info("[Cody:PrDesc] Generated PR description (${cleaned.length} chars)")
        return cleaned
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
                    val content = SensitiveContentSanitizer.sanitizeForExternalTransmission(file.readText())
                    server.textDocumentDidOpen(
                        ProtocolTextDocument(
                            uri = "file://$filePath",
                            content = content
                        )
                    )
                    log.info("[Cody:Chat] didOpen: $filePath (${content.length} chars)")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
        val sanitizedText = SensitiveContentSanitizer.sanitizeForExternalTransmission(text)
        val response = session.server.chatSubmitMessage(
            ChatSubmitParams(
                id = session.chatId,
                message = ChatMessage(
                    text = sanitizedText,
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
