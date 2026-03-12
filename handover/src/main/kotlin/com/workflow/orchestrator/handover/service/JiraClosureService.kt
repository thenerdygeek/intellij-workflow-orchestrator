package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.SuiteResult
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

    private val json = Json { ignoreUnknownKeys = true }

    fun buildClosureComment(suiteResults: List<SuiteResult>): String {
        if (suiteResults.isEmpty()) return ""

        val sb = StringBuilder()

        // Suite results table
        sb.appendLine("h4. Automation Results")
        sb.appendLine("|| Suite || Status || Link ||")
        for (suite in suiteResults) {
            val statusIcon = when (suite.passed) {
                true -> "(/) PASS"
                false -> "(x) FAIL"
                null -> "(?) RUNNING"
            }
            sb.appendLine("| ${escapeWikiMarkup(suite.suitePlanKey)} | $statusIcon | [View Results|${suite.bambooLink}] |")
        }

        // Docker tags
        val mergedTags = mutableMapOf<String, String>()
        for (suite in suiteResults) {
            try {
                val parsed = json.decodeFromString<JsonObject>(suite.dockerTagsJson)
                for ((key, value) in parsed) {
                    mergedTags[key] = value.jsonPrimitive.content
                }
            } catch (_: Exception) {
                // Malformed JSON — skip this suite's tags
            }
        }

        if (mergedTags.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("h4. Docker Tags")
            sb.appendLine("{code:json}")
            sb.appendLine(buildJsonString(mergedTags))
            sb.append("{code}")
        }

        return sb.toString()
    }

    private fun escapeWikiMarkup(text: String): String {
        return text.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}")
    }

    private fun buildJsonString(tags: Map<String, String>): String {
        val entries = tags.entries.joinToString(",\n  ") { (k, v) -> "\"$k\": \"$v\"" }
        return "{\n  $entries\n}"
    }

    companion object {
        fun getInstance(project: Project): JiraClosureService {
            return project.getService(JiraClosureService::class.java)
        }
    }
}
