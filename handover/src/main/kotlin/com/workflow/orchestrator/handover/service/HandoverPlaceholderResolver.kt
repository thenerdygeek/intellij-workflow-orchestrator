package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.model.SuiteResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.annotations.TestOnly

/**
 * Project-scoped service that resolves a single placeholder key to a
 * [HandoverPlaceholderValue] for a given [HandoverTemplateAction].
 *
 * All data is sourced from the already-loaded [HandoverStateService.stateFlow] and
 * [WorkflowContextService.state] snapshots — no network I/O, so the function is fast
 * and safe to call on the EDT after a state update.
 *
 * Unknown keys return [HandoverPlaceholderValue.unavailable] (never throw).
 *
 * AI placeholders (`ai.changeSummary`, `ai.ticketSummary`) delegate to [HandoverAiSummaryCache]
 * which caches by `(ticketId, sha, kind)` and invalidates on branch/ticket changes.
 */
@Service(Service.Level.PROJECT)
class HandoverPlaceholderResolver {

    private val log = Logger.getInstance(HandoverPlaceholderResolver::class.java)

    private val stateService: HandoverStateService
    private val workflowContext: WorkflowContextService
    private val aiCache: HandoverAiSummaryCache
    private val json = Json { ignoreUnknownKeys = true }

    /** IntelliJ DI constructor. */
    constructor(project: Project) {
        this.stateService = HandoverStateService.getInstance(project)
        this.workflowContext = WorkflowContextService.getInstance(project)
        this.aiCache = HandoverAiSummaryCache.getInstance(project)
    }

    /** Test constructor — allows injecting mocks without a running IDE. */
    @TestOnly
    constructor(
        stateService: HandoverStateService,
        workflowContext: WorkflowContextService,
        aiCache: HandoverAiSummaryCache,
    ) {
        this.stateService = stateService
        this.workflowContext = workflowContext
        this.aiCache = aiCache
    }

    /**
     * Resolves [key] to a [HandoverPlaceholderValue] appropriate for [action].
     *
     * Never throws — unknown keys return [HandoverPlaceholderValue.unavailable].
     */
    suspend fun resolve(key: String, action: HandoverTemplateAction): HandoverPlaceholderValue {
        log.debug("[Handover:Resolver] Resolving placeholder '$key' for action $action")
        val state: HandoverState = stateService.stateFlow.value
        val ctx: WorkflowContext = workflowContext.state.value

        return when (key) {
            "ticket.id" ->
                ctx.activeTicket?.key
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no active ticket")

            "ticket.summary" ->
                ctx.activeTicket?.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no active ticket")

            "ticket.status" ->
                state.currentStatusName
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("status unknown")

            "pr.id" ->
                ctx.focusPr?.prId?.toString()
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no focused PR")

            "pr.url" ->
                state.prUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("PR URL not set")

            "build.url" ->
                // BuildSummary has no url field — flag and defer.
                HandoverPlaceholderValue.unavailable("build URL not in state model")

            "build.planKey" ->
                state.buildStatus?.planKey
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no build status")

            "build.number" ->
                state.buildStatus?.buildNumber?.toString()
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no build status")

            "automation.url" ->
                state.suiteResults.lastOrNull()?.bambooLink
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no automation suite results")

            "docker.tag" ->
                resolveFirstDockerTag(state)

            "docker.tagsJson" ->
                state.suiteResults.lastOrNull()?.dockerTagsJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { HandoverPlaceholderValue.available(it) }
                    ?: HandoverPlaceholderValue.unavailable("no docker tags")

            "automation.suiteTable" ->
                renderSuiteTable(state.suiteResults, action)

            "ai.changeSummary" ->
                aiCache.changeSummary()

            "ai.ticketSummary" ->
                aiCache.ticketSummary()

            else ->
                HandoverPlaceholderValue.unavailable("unknown placeholder")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses the last suite's [SuiteResult.dockerTagsJson] and returns the first
     * `<key>:<value>` entry, or unavailable when there are no tags.
     */
    private fun resolveFirstDockerTag(state: HandoverState): HandoverPlaceholderValue {
        val raw = state.suiteResults.lastOrNull()?.dockerTagsJson
            ?.takeIf { it.isNotBlank() }
            ?: return HandoverPlaceholderValue.unavailable("no docker tags")
        return try {
            val obj = json.parseToJsonElement(raw) as? JsonObject
                ?: return HandoverPlaceholderValue.unavailable("docker tags not a JSON object")
            val (k, v) = obj.entries.firstOrNull()
                ?: return HandoverPlaceholderValue.unavailable("docker tags object is empty")
            HandoverPlaceholderValue.available("$k:${v.jsonPrimitive.content}")
        } catch (e: Exception) {
            log.warn("[Handover:Resolver] Failed to parse dockerTagsJson: ${e.message}")
            HandoverPlaceholderValue.unavailable("docker tags JSON parse error")
        }
    }

    /**
     * Renders the automation suite table in Jira wiki markup or HTML depending on [action].
     *
     * Colour semantics:
     * - `passed == true` → PASS (green)
     * - `passed == false` → FAIL (red)
     * - `passed == null` → running (amber)
     */
    private fun renderSuiteTable(
        suites: List<SuiteResult>,
        action: HandoverTemplateAction,
    ): HandoverPlaceholderValue {
        if (suites.isEmpty()) return HandoverPlaceholderValue.unavailable("no automation suites")

        val table = when (action) {
            HandoverTemplateAction.JIRA -> buildJiraTable(suites)
            HandoverTemplateAction.EMAIL -> buildEmailTable(suites)
        }
        return HandoverPlaceholderValue.available(table)
    }

    private fun buildJiraTable(suites: List<SuiteResult>): String = buildString {
        appendLine("|| Suite || Result ||")
        suites.forEach { suite ->
            val (label, color) = jiraResultLabel(suite.passed)
            appendLine("| ${suite.suitePlanKey} | {color:$color}$label{color} |")
        }
    }.trimEnd('\n')

    private fun buildEmailTable(suites: List<SuiteResult>): String = buildString {
        appendLine("<tr><th>Suite</th><th>Result</th></tr>")
        suites.forEach { suite ->
            val (label, color) = emailResultLabel(suite.passed)
            appendLine("<tr><td>${suite.suitePlanKey}</td><td><span style=\"color:$color\">$label</span></td></tr>")
        }
    }.trimEnd('\n')

    /** Returns (label, jiraColorName) for Jira `{color:…}` markup. */
    private fun jiraResultLabel(passed: Boolean?): Pair<String, String> = when (passed) {
        true  -> Pair("PASS", "green")
        false -> Pair("FAIL", "red")
        null  -> Pair("running", "orange")
    }

    /** Returns (label, cssHexColor) for HTML `<span style="color:…">` markup. */
    private fun emailResultLabel(passed: Boolean?): Pair<String, String> = when (passed) {
        true  -> Pair("PASS", "#2e7d32")
        false -> Pair("FAIL", "#c62828")
        null  -> Pair("running", "#b07c12")
    }

    companion object {
        fun getInstance(project: Project): HandoverPlaceholderResolver =
            project.getService(HandoverPlaceholderResolver::class.java)
    }
}
