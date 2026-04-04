package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CurrentTimeTool : AgentTool {
    override val name = "current_time"
    override val description = "Get the current date and time. Use when you need to know the current time for reasoning about deadlines, sprint dates, time tracking, log timestamps, or any time-sensitive decisions."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val now = ZonedDateTime.now()
        val utcNow = now.withZoneSameInstant(ZoneId.of("UTC"))

        val localFormatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val utcFormatted = utcNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val dayOfWeek = now.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        val content = """
            |Current time:
            |  Local: $localFormatted
            |  UTC:   $utcFormatted
            |  Day:   $dayOfWeek
            |  Timezone: ${now.zone}
        """.trimMargin()

        return ToolResult(
            content = content,
            summary = "Current time: $localFormatted",
            tokenEstimate = 10
        )
    }
}
