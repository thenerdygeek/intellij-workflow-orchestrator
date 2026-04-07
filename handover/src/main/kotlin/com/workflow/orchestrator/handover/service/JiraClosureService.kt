package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Service(Service.Level.PROJECT)
class JiraClosureService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    private val log = Logger.getInstance(JiraClosureService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun buildClosureComment(suiteResults: List<SuiteResult>): String {
        log.info("[Handover:Jira] Building closure comment from ${suiteResults.size} suite results")
        if (suiteResults.isEmpty()) {
            log.warn("[Handover:Jira] No suite results provided for closure comment")
            return ""
        }

        val mergedTags = mergeDockerTags(suiteResults)
        val comment = buildString {
            // Suite results table
            appendLine("h4. Automation Results")
            appendLine("|| Suite || Status || Link ||")
            for (suite in suiteResults) {
                val statusIcon = when (suite.passed) {
                    true -> "(/) PASS"
                    false -> "(x) FAIL"
                    null -> "(?) RUNNING"
                }
                appendLine("| ${escapeWikiMarkup(suite.suitePlanKey)} | $statusIcon | [View Results|${suite.bambooLink}] |")
            }

            if (mergedTags.isNotEmpty()) {
                appendLine()
                appendLine("h4. Docker Tags")
                appendLine("{code:json}")
                appendLine(json.encodeToString(mergedTags))
                append("{code}")
            }
        }

        log.info("[Handover:Jira] Closure comment built with ${mergedTags.size} docker tags")
        log.debug("[Handover:Jira] Comment preview: ${comment.take(200)}")
        return comment
    }

    private fun mergeDockerTags(suiteResults: List<SuiteResult>): Map<String, String> {
        val mergedTags = mutableMapOf<String, String>()
        for (suite in suiteResults) {
            try {
                val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
                parsed.forEach { (key, value) -> mergedTags[key] = value.jsonPrimitive.content }
            } catch (_: Exception) {
                // Malformed JSON — skip this suite's tags
            }
        }
        return mergedTags
    }

    private fun escapeWikiMarkup(text: String): String =
        text.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}")

    companion object {
        fun getInstance(project: Project): JiraClosureService =
            project.getService(JiraClosureService::class.java)
    }
}
