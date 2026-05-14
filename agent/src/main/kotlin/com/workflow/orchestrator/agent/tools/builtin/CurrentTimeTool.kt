package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("current_time") {
        summary {
            technical("Returns the current wall-clock time as four lines: ISO-8601 offset-date-time in the JVM's local zone (`Local:`), the same instant in UTC (`UTC:`), the day-of-week name (`Day:`), and the IANA timezone id (`Timezone:`, e.g. `Europe/London`). No parameters; reads `ZonedDateTime.now()` directly with no caching.")
            plain("Like glancing at the clock on the wall — the agent gets back today's date, the current time in two zones, and what day of the week it is. That's it.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without current_time the LLM falls back to `run_command date` (which works on Unix but not Windows cmd.exe) or guesses from training-data cutoff. The shell route costs a process spawn (~50ms), routes through the approval gate (run_command is ALWAYS_PER_INVOCATION), and parses freeform output. Guessing is worse — the LLM consistently picks dates near its training cutoff and gets sprint-window math wrong by months. Cheap to keep; expensive to remove."
        )
        llmMistake("Uses the local `Local:` field for log/sprint comparisons against UTC timestamps from Jira/Bamboo, producing off-by-N-hours errors. The `UTC:` field is right there in the same output but the LLM picks the more familiar local one.")
        llmMistake("Re-calls current_time multiple times in the same session for ~~freshness~~ — wasted iterations, since wall-clock drift across an agent turn is meaningless for sprint/deadline reasoning.")
        llmMistake("Calls current_time as a no-op stall while thinking, instead of using `think`. Tool-call audit shows clusters of current_time invocations preceding actual work.")
        params { }
        verdict {
            keep(
                "Trivial implementation (zero deps, ~10 lines), measurably reduces date-grounding errors in sprint/deadline reasoning, and fills a real gap on Windows where `run_command date` returns a different format. Earning its slot in the deferred-tool catalog easily.",
                VerdictSeverity.STRONG,
            )
        }
        related("run_command", Relationship.FALLBACK, "If current_time were unavailable, `run_command date -u +%FT%TZ` is the closest UTC-only substitute on Unix; on Windows the format differs.")
        downside("Returns the JVM-local timezone (whatever the IDE inherits from the OS), not the user's preferred reporting zone — there's no `tz` param. A user in PST sees `-08:00`; the same code running on a CI box in UTC sees `+00:00`. The UTC field is always present as an unambiguous anchor.")
        downside("No monotonic-clock variant. If the LLM tried to use this for elapsed-time measurements across two calls, NTP adjustments or DST transitions could yield negative deltas. Not what the tool is for, but the description doesn't warn against it.")
        downside("Registered as a deferred tool under 'Utilities' — invisible until the LLM `tool_search`es for it. For a tool this cheap, the discovery friction probably costs more in extra `tool_search` calls than the schema would cost in the core set.")
        observation("Schema cost is ~0 (zero parameters) yet the tool sits in the deferred tier. Promoting to core would save the `tool_search` round-trip whenever the LLM needs a timestamp — worth measuring.")
    }

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
