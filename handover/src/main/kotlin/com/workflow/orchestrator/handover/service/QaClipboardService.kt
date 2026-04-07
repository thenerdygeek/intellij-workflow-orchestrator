package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.ClipboardPayload
import com.workflow.orchestrator.handover.model.SuiteLinkEntry
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class QaClipboardService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val log = Logger.getInstance(QaClipboardService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun formatForClipboard(payload: ClipboardPayload): String {
        log.info("[Handover:QA] Formatting clipboard content: ${payload.dockerTags.size} docker tags, ${payload.suiteLinks.size} suite links, ${payload.ticketIds.size} tickets")
        return buildString {
            if (payload.dockerTags.isNotEmpty()) {
                appendLine("Docker Tags:")
                payload.dockerTags.forEach { (service, tag) -> appendLine("  • $service: $tag") }
            }

            if (payload.suiteLinks.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Automation Results:")
                payload.suiteLinks.forEach { link ->
                    val status = if (link.passed) "PASS" else "FAIL"
                    appendLine("  • ${link.suiteName}: $status — ${link.link}")
                }
            }

            if (payload.ticketIds.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append("Tickets: ${payload.ticketIds.joinToString(", ")}")
            }
        }
    }

    fun buildPayloadFromSuiteResults(
        suiteResults: List<SuiteResult>,
        ticketIds: List<String>
    ): ClipboardPayload {
        log.info("[Handover:QA] Building clipboard payload from ${suiteResults.size} suite results and ${ticketIds.size} tickets")
        val mergedTags = mutableMapOf<String, String>()
        val suiteLinks = mutableListOf<SuiteLinkEntry>()

        for (suite in suiteResults) {
            // Extract docker tags
            try {
                val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
                parsed.forEach { (key, value) -> mergedTags[key] = value.jsonPrimitive.content }
            } catch (_: Exception) {
                // Skip malformed JSON
            }

            // Only include completed suites in clipboard
            if (suite.passed != null) {
                suiteLinks.add(
                    SuiteLinkEntry(
                        suiteName = suite.suitePlanKey,
                        passed = suite.passed,
                        link = suite.bambooLink
                    )
                )
            }
        }

        log.debug("[Handover:QA] Merged ${mergedTags.size} docker tags, ${suiteLinks.size} completed suite links")
        return ClipboardPayload(
            dockerTags = mergedTags,
            suiteLinks = suiteLinks,
            ticketIds = ticketIds
        )
    }

    companion object {
        fun getInstance(project: Project): QaClipboardService =
            project.getService(QaClipboardService::class.java)
    }
}
