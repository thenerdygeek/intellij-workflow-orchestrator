package com.workflow.orchestrator.web.service.sanitizer

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.SubagentSpawner

class SanitizerSubagent(private val spawner: SubagentSpawner) {

    suspend fun sanitize(
        project: Project,
        extractedText: String,
        brainId: String?,
        timeoutMs: Long,
    ): SubagentSpawner.SanitizerResult {
        val system = loadSystemPrompt()
        val user = "Source-of-truth text follows between <input> tags.\n" +
                   "Return only the JSON object specified in your instructions.\n" +
                   "<input>\n$extractedText\n</input>"
        return spawner.runSanitizer(
            project = project,
            brainId = brainId,
            systemPrompt = system,
            userPrompt = user,
            timeoutMs = timeoutMs,
        )
    }

    private fun loadSystemPrompt(): String =
        javaClass.getResourceAsStream("/personas/sanitizer-system-prompt.txt")
            ?.bufferedReader()?.readText()
            ?: error("sanitizer-system-prompt.txt resource missing")
}
