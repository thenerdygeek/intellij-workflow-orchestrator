package com.workflow.orchestrator.cody.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.ChatMessage
import com.workflow.orchestrator.cody.protocol.ChatSubmitParams
import com.workflow.orchestrator.core.ai.BranchNameAiGenerator
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cody-powered implementation of [BranchNameAiGenerator].
 * Sends a focused prompt to Cody to generate a short, professional branch name slug.
 *
 * This is deliberately verbose with logging — it serves as the first real Cody integration
 * test point, so we want full visibility into the agent lifecycle and chat flow.
 */
class CodyBranchNameGeneratorImpl : BranchNameAiGenerator {

    private val log = Logger.getInstance(CodyBranchNameGeneratorImpl::class.java)

    override suspend fun generateBranchSlug(
        project: Project,
        ticketKey: String,
        title: String,
        description: String?
    ): String? {
        log.info("[Cody:BranchGen] === Starting branch name generation for $ticketKey ===")
        log.info("[Cody:BranchGen] Title: '$title'")
        log.info("[Cody:BranchGen] Description present: ${description != null}")

        val providerService = CodyAgentProviderService.getInstance(project)
        log.info("[Cody:BranchGen] CodyAgentProviderService acquired, isRunning=${providerService.isRunning()}")
        log.info("[Cody:BranchGen] Active provider: ${providerService.activeProviderName ?: "none"}")

        return try {
            // Step 1: Ensure agent is running
            log.info("[Cody:BranchGen] Step 1: Ensuring Cody agent is running...")
            val server = providerService.ensureRunning()
            log.info("[Cody:BranchGen] Step 1 complete: Agent server acquired successfully")

            // Step 2: Create new chat session
            log.info("[Cody:BranchGen] Step 2: Creating new chat session...")
            val chatId = try {
                withTimeoutOrNull(15_000) {
                    server.chatNew().await()
                }
            } catch (e: Exception) {
                log.warn("[Cody:BranchGen] Step 2 FAILED: chatNew() threw: ${e.message}", e)
                return null
            }
            if (chatId == null) {
                log.error("[Cody:BranchGen] Step 2 FAILED: chatNew() returned null or timed out (15s)")
                return null
            }
            log.info("[Cody:BranchGen] Step 2 complete: Chat session created with id='$chatId'")

            // Step 3: Build and send prompt
            val prompt = buildPrompt(title, description)
            log.info("[Cody:BranchGen] Step 3: Sending prompt to Cody (${prompt.length} chars)...")
            log.debug("[Cody:BranchGen] Full prompt:\n$prompt")

            val response = withTimeoutOrNull(30_000) {
                server.chatSubmitMessage(
                    ChatSubmitParams(
                        id = chatId,
                        message = ChatMessage(
                            text = prompt,
                            addEnhancedContext = false
                        )
                    )
                ).await()
            }

            if (response == null) {
                log.error("[Cody:BranchGen] Step 3 FAILED: Timed out waiting for Cody response (30s)")
                return null
            }

            log.info("[Cody:BranchGen] Step 3 complete: Received response with ${response.messages.size} messages")
            for ((i, msg) in response.messages.withIndex()) {
                log.info("[Cody:BranchGen]   Message[$i]: speaker='${msg.speaker}', text='${msg.text?.take(100)}'")
            }

            // Step 4: Extract and sanitize the response
            val rawAnswer = response.messages.lastOrNull { it.speaker == "assistant" }?.text
            log.info("[Cody:BranchGen] Step 4: Raw assistant response: '${rawAnswer?.take(200)}'")

            if (rawAnswer.isNullOrBlank()) {
                log.warn("[Cody:BranchGen] Step 4 FAILED: Empty response from Cody")
                return null
            }

            val slug = sanitizeSlug(rawAnswer)
            log.info("[Cody:BranchGen] Step 4 complete: Sanitized slug = '$slug'")

            if (slug.isBlank()) {
                log.warn("[Cody:BranchGen] Sanitized slug is blank, returning null")
                return null
            }

            log.info("[Cody:BranchGen] === Branch name generation SUCCESS for $ticketKey: '$slug' ===")
            slug
        } catch (e: IllegalStateException) {
            log.warn("[Cody:BranchGen] Cody agent not available: ${e.message}", e)
            log.warn("[Cody:BranchGen] Install Cody CLI (npm install -g @sourcegraph/cody) " +
                "or configure path in Settings > Tools > Workflow Orchestrator > Advanced")
            null
        } catch (e: Exception) {
            log.error("[Cody:BranchGen] Unexpected error during branch name generation", e)
            null
        }
    }

    private fun buildPrompt(title: String, description: String?): String {
        val descPart = if (!description.isNullOrBlank()) {
            "\nTicket description: $description"
        } else ""

        return """Generate a short, professional git branch name slug from this Jira ticket.

Rules:
- Output ONLY the slug on a single line, nothing else
- No explanation, no quotes, no backticks, no markdown
- Use lowercase words separated by hyphens
- Maximum 5 words (aim for 3-4)
- Capture the core intent/fix, not every detail
- No ticket ID, no prefix like "feature/" or "fix/" — just the descriptive slug

Examples:
  "Fix NPE in OrderService" → fix-null-pointer-order-service
  "Add retry logic for payment API" → add-retry-payment-api
  "Update user email validation" → update-user-validation

Ticket title: $title$descPart"""
    }

    /**
     * Clean up Cody's response to produce a valid branch slug.
     * Handles chatty responses like "Here's a branch name: fix-something"
     * by extracting the last hyphenated slug from the response.
     */
    internal fun sanitizeSlug(raw: String): String {
        val firstLine = raw.trim()
            .removePrefix("```").removeSuffix("```")
            .removePrefix("`").removeSuffix("`")
            .removePrefix("\"").removeSuffix("\"")
            .removePrefix("'").removeSuffix("'")
            .lines().first().trim()

        // If the line looks like a clean slug already, use it directly
        val cleanSlug = firstLine.lowercase()
        if (cleanSlug.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$")) && cleanSlug.count { it == '-' } >= 1) {
            return cleanSlug.take(60)
        }

        // Chatty response: extract the last hyphenated-word sequence (the slug)
        // e.g., "Here's a good name: fix-null-pointer-order" → "fix-null-pointer-order"
        val slugPattern = Regex("[a-z][a-z0-9]*(-[a-z][a-z0-9]*){1,6}")
        val match = slugPattern.findAll(cleanSlug).lastOrNull()
        if (match != null) {
            return match.value.take(60)
        }

        // Fallback: brute-force sanitize
        return cleanSlug
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(60)
            .trimEnd('-')
    }
}
