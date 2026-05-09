package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude Code-style tool search: lets the LLM discover and load deferred tools
 * that aren't in the initial tool set.
 *
 * The LLM calls this when it needs a capability not covered by core tools.
 * Matched tools are activated and become available in subsequent API calls.
 *
 * Query forms:
 * - "jira tickets" — keyword search, returns best matches
 * - "select:jira,sonar" — load specific tools by exact name
 */
class ToolSearchTool(private val registry: ToolRegistry) : AgentTool {

    override val name = "tool_search"

    override val description = """Search for and load specialized tools that aren't in your current toolset.

Use this when you need a capability not covered by your current tools.
For example, if you need to work with Jira tickets, search for "jira".
If you need to debug, search for "debug".

The search returns tool names and descriptions. Once loaded, the tools
become available for you to call in subsequent responses.

Query forms:
- "jira" — keyword search, returns best matches
- "select:jira,sonar" — load specific tools by exact name""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — keyword(s) to find relevant tools, or 'select:name1,name2' to load specific tools by name"
            ),
            "max_results" to ParameterProperty(
                type = "integer",
                description = "Maximum tools to return. Default: 5"
            )
        ),
        required = listOf("query")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("tool_search") {
        summary {
            technical("Keyword-overlap search over the deferred-tool pool (~50 tools); matched tools are activated for the rest of the session and appear in the active tool schema on the next LLM call. Supports `select:name1,name2` for exact-name loading and a `max_results` cap. Wraps ToolRegistry.searchDeferred + activateDeferred.")
            plain("Like a big reference manual where most chapters stay on the shelf — the agent grabs the chapter it needs (Jira, debugging, database, Spring) only when a task calls for it, instead of carrying every chapter in its backpack all day.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without tool_search the deferred tier collapses: every one of the ~50 deferred tools' full JSON schemas would have to live in the system prompt on every turn, ballooning per-call schema tokens from ~4K back to ~10K (per `:agent` CLAUDE.md). On a 132K-input model that's ~5% of the budget burned every turn on tool definitions the LLM is mostly not using. The alternative — pre-pruning by heuristic — would either over-prune (LLM can't reach Jira when it needs to) or under-prune (no token win). tool_search is the architecture that makes the three-tier registry pay off."
        )
        llmMistake("Searches with vague/generic terms (`tool_search query=\"help\"` / `query=\"find\"`) — keyword-overlap returns false positives matched on the substring `help` in unrelated descriptions. Use a domain word: `jira`, `debug`, `coverage`, `spring`.")
        llmMistake("Ignores tool_search entirely and falls back to `run_command` for things a deferred tool covers natively — e.g. `run_command 'curl …jira/rest/…'` instead of activating the `jira` tool. Loses auth handling, validation, and structured output; surfaced as a recurring pattern in the system prompt's task-to-tool hints table.")
        llmMistake("Calls tool_search repeatedly for the same already-activated tool — once activated, tools persist for the rest of the session, so re-searching is wasted iterations. activateDeferred() is a no-op for already-active tools, but the LLM still pays the round-trip.")
        llmMistake("Searches for a non-existent capability (e.g. `query=\"git\"`) and gets false-positive matches on tools whose descriptions contain `git` in passing (bitbucket/bamboo/sonar). Native git operations go through `run_command` — there's no `git` meta-tool. Hardened in getRelatedToolsHint by intentionally not suggesting a `git` tool for bitbucket/jira loads.")
        params {
            required("query", "string") {
                llmSeesIt("Search query — keyword(s) to find relevant tools, or 'select:name1,name2' to load specific tools by name")
                humanReadable("What chapter of the manual to look up. A keyword like `jira` or `debug` does a fuzzy search; `select:jira,sonar` is the exact-name form for when you already know what you want.")
                whenPresent("Either case-insensitive keyword overlap against tool name + description (top N by score) OR — when prefixed with `select:` — exact-name lookup of comma-separated tool names. All matches are activated immediately; subsequent LLM calls see them in the tool schema.")
                constraint("non-empty string; empty/whitespace-only queries return no matches and dump the full deferred catalog")
                constraint("`select:` form expects exact tool names — typos return null silently, not an error")
                example("jira")
                example("debug")
                example("select:jira,sonar")
                example("spring boot endpoints")
            }
            optional("max_results", "integer") {
                llmSeesIt("Maximum tools to return. Default: 5")
                humanReadable("Cap on how many chapters to grab in one search — keeps the response from dumping 20 tool schemas when the LLM only needs the top 2-3.")
                whenPresent("Top-N matches by keyword-overlap score are activated; ties broken by registration order.")
                whenAbsent("Defaults to 5.")
                constraint("ignored when `query` starts with `select:` — exact-name loading activates every named tool regardless of count")
                example("3")
                example("10")
            }
        }
        verdict {
            keep(
                "Architectural keystone of the three-tier ToolRegistry. Without it, the per-call schema budget regresses from ~4K to ~10K tokens (a ~5% input-window tax on every turn) and the deferred tier becomes pointless. The cost is ~1 extra round-trip per session when a deferred tool is needed — a clear win on any non-trivial task.",
                VerdictSeverity.STRONG,
            )
        }
        related("jira", Relationship.SEE_ALSO, "One of the most-loaded deferred tools — illustrative target. tool_search is how the LLM reaches it.")
        related("debug_step", Relationship.SEE_ALSO, "Activated alongside `debug_inspect` and `debug_breakpoints` when the LLM searches `debug`. getRelatedToolsHint nudges the LLM toward `diagnostics` and `runtime_exec` next.")
        related("spring", Relationship.SEE_ALSO, "Framework tool reachable via `tool_search query=\"spring\"`. getRelatedToolsHint suggests `endpoints, build, coverage, db_schema` as complementary loads.")
        downside("Activated tools persist for the rest of the session — there is no `tool_search action=deactivate`. Once the LLM loads `jira`, the schema overhead stays in every prompt for the rest of the session even if Jira is never touched again. resetActiveDeferred() exists but is only called on new chat, not exposed to the LLM.")
        downside("Search is keyword-overlap only — no semantic ranking, no embeddings, no synonyms. Searching `tickets` won't find `jira`; searching `tests` finds anything mentioning `test` in its description (broad). Forces the LLM to know domain vocabulary already.")
        downside("`select:` form silently drops typo'd names rather than erroring — `select:jiar,sonar` quietly loads only `sonar`. The LLM has no signal that one of its names was wrong unless it inspects the response carefully.")
        downside("getRelatedToolsHint is a hand-curated when-block (15ish hardcoded rules). New deferred tools won't appear in related-hint suggestions until someone edits the source. Drift risk as the deferred catalog grows.")
        observation("The activation side-effect on a 'search' verb is surprising — semantically `tool_search` reads like a query but it also mutates registry state. Considered renaming to `tool_load` / `tool_activate` but kept for Claude Code observable-tool-pattern parity (see class KDoc).")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: missing required 'query' parameter.",
                summary = "Missing query parameter",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val maxResults = params["max_results"]?.jsonPrimitive?.intOrNull ?: 5

        val matches = if (query.startsWith("select:")) {
            // Exact name match — load specific tools
            val names = query.removePrefix("select:").split(",").map { it.trim() }
            names.mapNotNull { toolName ->
                registry.activateDeferred(toolName)
            }
        } else {
            // Keyword search — find and activate matching tools
            val results = registry.searchDeferred(query, maxResults)
            results.forEach { registry.activateDeferred(it.name) }
            results
        }

        if (matches.isEmpty()) {
            val catalog = registry.getDeferredCatalog()
            val catalogText = if (catalog.isNotEmpty()) {
                "\n\nAvailable deferred tools:\n" +
                    catalog.joinToString("\n") { "- ${it.first}: ${it.second}" }
            } else {
                "\n\nNo deferred tools are currently registered."
            }

            return ToolResult(
                content = "No tools found matching '$query'.$catalogText",
                summary = "No tools found for '$query'",
                tokenEstimate = estimateTokens(catalogText)
            )
        }

        // Build detailed descriptions of loaded tools
        val loaded = matches.joinToString("\n\n") { tool ->
            buildString {
                appendLine("## ${tool.name}")
                appendLine(tool.description.lines().take(5).joinToString("\n"))
                append("Parameters: ${tool.parameters.properties.keys.joinToString(", ")}")
            }
        }

        val relatedHint = getRelatedToolsHint(matches.map { it.name })
        val content = buildString {
            append("Loaded ${matches.size} tool(s):\n\n$loaded\n\nThese tools are now available for you to call.")
            if (relatedHint.isNotEmpty()) {
                append("\n\nRelated tools you may also find useful (load via tool_search): $relatedHint")
            }
        }

        return ToolResult(
            content = content,
            summary = "Loaded tools: ${matches.joinToString(", ") { it.name }}",
            tokenEstimate = estimateTokens(content)
        )
    }

    /**
     * Suggest related tools based on what was just loaded.
     * Helps the LLM discover complementary tools it might not know about.
     */
    internal fun getRelatedToolsHint(loadedNames: List<String>): String {
        val related = mutableSetOf<String>()
        for (name in loadedNames) {
            when {
                name == "endpoints" -> related.addAll(listOf("spring", "build", "coverage", "db_schema"))
                name.startsWith("spring") -> related.addAll(listOf("endpoints", "build", "coverage", "db_schema"))
                name.startsWith("django") -> related.addAll(listOf("build", "db_schema", "db_query"))
                name.startsWith("fastapi") -> related.addAll(listOf("build", "db_schema"))
                name.startsWith("flask") -> related.addAll(listOf("build", "db_schema"))
                name == "build" -> related.addAll(listOf("coverage", "runtime_exec"))
                name.startsWith("debug") -> related.addAll(listOf("diagnostics", "runtime_exec"))
                name == "coverage" -> related.add("runtime_exec")
                // bitbucket / jira intentionally have no related-tools hint:
                // there is no native `git` meta-tool in this codebase; native git
                // operations go through the always-available `run_command` core tool,
                // and suggesting a non-existent "git" tool sent the LLM into a
                // dead-end tool_search that returned bitbucket/bamboo/sonar (false
                // positives matched on the substring "git" in their descriptions).
                name.startsWith("sonar") -> related.addAll(listOf("diagnostics", "coverage"))
            }
        }
        // Don't suggest tools that were just loaded
        related.removeAll(loadedNames.toSet())
        return related.take(3).joinToString(", ")
    }
}
