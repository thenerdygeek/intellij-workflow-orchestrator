package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject

class TaskListTool(
    private val storeProvider: () -> TaskStore?,
) : AgentTool {

    override val name = "task_list"
    override val isHookExempt = true

    override val description =
        "List all tasks in the session with minimal fields (id, subject, status, owner, blockedBy). " +
            "Does NOT include description or metadata — use task_get with an id for those. Prefer " +
            "working on tasks in id order (lowest first) when multiple are available; earlier tasks " +
            "often set up context for later ones. Tasks with non-empty blockedBy cannot start until " +
            "those dependencies are completed."

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = WorkerType.entries.toSet()

    override fun documentation(): ToolDocumentation = toolDoc("task_list") {
        summary {
            technical(
                "Zero-parameter dump of every task in the session's TaskStore: id, status, subject, owner, " +
                    "blockedBy. Description is intentionally elided — fetch via task_get(id) for a single " +
                    "task. Output is dirty-read (no mutex), safe because the agent loop is single-coroutine."
            )
            plain(
                "Like running `ls` on the session's to-do list, or glancing at a Kanban board: every card " +
                    "by id, status, and one-line subject — but no card details. If you need the description, " +
                    "open the card with task_get."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Marginal. The agent loop already injects `# Tasks` into every user turn via " +
                "EnvironmentDetailsBuilder.appendTasks (id + status + subject for non-deleted tasks), and " +
                "ContextManager.renderTaskProgressMarkdown surfaces a checkbox view in system-prompt Section 2 " +
                "after every compaction rebuild. Without task_list the LLM loses ONLY the owner and blockedBy " +
                "fields — recoverable by calling task_get on any task whose dependency state matters. The tool's " +
                "name implies a discoverability gap that doesn't actually exist; the LLM rarely needs it because " +
                "the list is already in front of it."
        )
        llmMistake(
            "Calls task_list every iteration to 'check progress' even though `# Tasks` is already in " +
                "environment_details on every user turn. Pure token waste; once-per-session is the maximum " +
                "useful cadence, and even that is usually redundant."
        )
        llmMistake(
            "Calls task_list when it actually needs description/blocks/timestamps — task_get is the right " +
                "tool. The 'Does NOT include description' clause in the description is meant to redirect, " +
                "but LLMs sometimes pick task_list anyway because the name sounds more general."
        )
        llmMistake(
            "Treats task_list output as authoritative for owner/blockedBy and skips task_get even when a " +
                "blocker exists — the output truncates blockedBy to a comma-joined id list with no titles, " +
                "so reasoning about the dependency graph from task_list alone is lossy."
        )
        // No params block: task_list is parameter-free by design (FunctionParameters with empty properties/required).
        verdict {
            keep(
                "Cheap-to-call summary that includes the two fields environment_details and the system-prompt " +
                    "task render do NOT include — owner and blockedBy. For multi-worker / dependency-heavy " +
                    "sessions the LLM legitimately needs to scan owner+blockedBy without paying the per-task cost " +
                    "of task_get(id) loops.",
                VerdictSeverity.WEAK,
            )
            drop(
                "The system prompt's Section 2 render and the per-turn environment_details `# Tasks` block " +
                    "together cover id+status+subject for every active task. The marginal value is owner+blockedBy " +
                    "— two fields that could be appended to the existing renders (3-line patch in " +
                    "EnvironmentDetailsBuilder.appendTasks) and obviate the tool entirely. As shipped, task_list " +
                    "is a redundant affordance that costs schema tokens on every iteration in exchange for " +
                    "information already in the LLM's context.",
                VerdictSeverity.NORMAL,
            )
        }
        // Audit notes — the central thesis is that this tool's role overlaps with the system-prompt task render.
        mergeOpportunity(
            "Merge into the system-prompt task render: extend EnvironmentDetailsBuilder.appendTasks to include " +
                "owner and blockedBy when present (`- [3] [in_progress] Fix login bug (owner: alice, blockedBy: 1,2)`). " +
                "Once the per-turn render carries every field task_list returns, the tool can be dropped from the " +
                "core registry — saving its schema slot and removing one of the four task_* tools the LLM has to " +
                "choose between."
        )
        observation(
            "Hook-exempt (bypasses PreToolUse/PostToolUse) per agent/CLAUDE.md \"TaskStore + Task Tools\". This is " +
                "correct — internal bookkeeping should never trigger an approval gate — but it also means task_list " +
                "is never the load-bearing call in any approval-gated workflow, weakening its case for a core slot."
        )
        observation(
            "No filter/status param. A `status` filter (e.g. `task_list(status=\"in_progress\")`) was the obvious " +
                "v2 — but the same compaction-resistant render in the system prompt makes filtering low-value. If " +
                "the tool survives, do NOT add a filter param; the right move is to delete the tool and patch the " +
                "render."
        )
        related("task_get", Relationship.ALTERNATIVE, "Use when you need description, dependency edges, or timestamps for ONE task — task_list omits all of those.")
        related("task_create", Relationship.COMPLEMENT, "task_create returns the new task's id; task_list is how you'd verify the full set after several creates (rarely needed — the system prompt re-renders).")
        related("task_update", Relationship.COMPLEMENT, "task_list shows current status; task_update flips it. Most callers can skip task_list because the id is already visible in environment_details.")
        related("task_report", Relationship.SEE_ALSO, "Sub-agent completion signal. Sub-agents see the parent's tasks in their initial context; they typically don't call task_list.")
        downside(
            "Returns ALL tasks (no status filter) — in long sessions with many completed tasks, the output " +
                "balloons. There's no pagination either; if a session ever exceeded ~500 tasks the result " +
                "would compete with file reads for the 50K default output cap."
        )
        downside(
            "blockedBy is rendered as `[blockedBy: 1,2]` — bare ids with no subjects. The LLM has to call " +
                "task_get on each blocker (or read the rest of task_list) to know what the dependency actually is. " +
                "Lossy for any non-trivial dependency reasoning."
        )
        downside(
            "Redundant with the system-prompt task render after every compaction (Section 2) and with the " +
                "per-turn environment_details `# Tasks` block. The LLM is shown the same data three different " +
                "ways; calling task_list adds a fourth presentation that's MORE compact in some fields but " +
                "MISSING the checkbox visualization."
        )
        downside(
            "No way to query 'what are my unblocked pending tasks?' — the LLM has to filter client-side. " +
                "For a tool whose entire job is summary-listing, the lack of filtering is a real ergonomic gap."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls task_list] --> B{TaskStore attached?}
                B -- no --> X1[Return error: store unavailable]
                B -- yes --> C[store.listTasks dirty-read snapshot]
                C --> D{tasks empty?}
                D -- yes --> Y1[Return 'No tasks in this session.']
                D -- no --> E[Render each task]
                E --> F[Format: '- id  status  subject [owner] [blockedBy]']
                F --> G[Join with newlines]
                G --> H[Return content + summary 'N tasks']
            """
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val store = storeProvider()
            ?: return ToolResult.error(
                "Task store is not available in this session.",
                "task_list failed: store unavailable",
            )

        val tasks = store.listTasks()
        if (tasks.isEmpty()) {
            return ToolResult(
                content = "No tasks in this session.",
                summary = "No tasks",
                tokenEstimate = 5,
                isError = false,
            )
        }

        val rendered = tasks.joinToString("\n") { t ->
            val owner = t.owner?.let { " [owner: $it]" }.orEmpty()
            val blockedBy = if (t.blockedBy.isEmpty()) "" else " [blockedBy: ${t.blockedBy.joinToString(",")}]"
            "- ${t.id}  ${t.status.name.lowercase()}  ${t.subject}$owner$blockedBy"
        }

        return ToolResult(
            content = rendered,
            summary = "${tasks.size} tasks",
            tokenEstimate = rendered.length / 4,
            isError = false,
        )
    }
}
