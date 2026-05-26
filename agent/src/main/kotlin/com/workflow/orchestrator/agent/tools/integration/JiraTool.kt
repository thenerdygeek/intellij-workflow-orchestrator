package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionInput
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Jira meta-tool replacing 15 individual jira_* tools.
 *
 * Saves ~14,400 tokens per API call by collapsing all Jira operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: get_ticket, get_transitions, transition, comment, get_comments,
 *          log_work, get_worklogs, get_sprints, get_linked_prs, get_boards,
 *          get_sprint_issues, get_board_issues, search_issues, search_tickets,
 *          get_dev_branches, start_work, download_attachment
 */
class JiraTool : AgentTool {

    override val name = "jira"

    override val description = """
Jira ticket management — issues, sprints, boards, transitions, comments, time logging.

Actions and their parameters:
- get_ticket(key, include_dev_status?, include_remote_links?, include_history?, include_permissions?) → Full ticket details. include_* flags fan out in parallel; each adds a block to the response. include_dev_status for "what's the status across CI/PR", include_remote_links for "what design docs link to this", include_history for "who changed what when", include_permissions to check what actions the current user can take before attempting them.
- get_transitions(key) → Available status transitions
- transition(key, transition_id, fields?, comment?) → Move ticket to new status.
  If the response payload is MissingFields, call ask_followup_question for each
  field to collect values from the user, then retry with fields={<fieldId>: <value>, ...}.
  fields format:
    user/assignee/reviewer: {"name": "username"}   (Jira DC)
    labels:                 ["label1", "label2"]
    priority/select/option: {"id": "option-id"}
    multi select:           [{"id": "a"}, {"id": "b"}]
    cascading:              {"value": "parent", "child": {"value": "child"}}
    version/component:      {"id": "id"} or [{"id": "id"}, ...]
- comment(key, body) → Add comment to ticket
- get_comments(key) → List comments
- log_work(key, time_spent, comment?) → Log time (format: '2h', '30m', '1h 30m')
- get_worklogs(key) → List work logs
- get_sprints(board_id) → List sprints for board
- get_boards(type?, name_filter?) → List boards (type: scrum|kanban)
- get_sprint_issues(sprint_id, current_user_only?) → Issues in sprint (current_user_only=true scopes to you; default whole sprint)
- get_board_issues(board_id) → Issues on board
- search_issues(text, max_results?, current_user_only?) → JQL/text search (default 20 results, default current user only)
- search_tickets(jql, max_results?) → Run raw JQL query
- get_linked_prs(key) → PRs linked to issue
- get_dev_branches(key) → Dev branches for issue
- start_work(key, branch_name, source_branch) → Create branch and start work
- download_attachment(key, attachment_id) → Download attachment content

key: Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue.
description optional: for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_ticket", "get_transitions", "transition", "comment", "get_comments",
                    "log_work", "get_worklogs", "my_worklogs", "get_sprints", "get_linked_prs", "get_boards",
                    "get_sprint_issues", "get_board_issues", "search_issues", "search_tickets",
                    "get_dev_branches", "start_work", "download_attachment", "user_search"
                )
            ),
            "key" to ParameterProperty(
                type = "string",
                description = "Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue"
            ),
            "transition_id" to ParameterProperty(
                type = "string",
                description = "Transition ID — for transition (use get_transitions first)"
            ),
            "fields" to ParameterProperty(
                type = "object",
                description = "Optional field values for transition screens — for transition. " +
                    "Keys are Jira field IDs (e.g. \"assignee\", \"labels\", \"priority\"). " +
                    "Values follow the Jira field-value format: " +
                    "user={\"name\":\"username\"}, labels=[\"l1\",\"l2\"], " +
                    "option/priority={\"id\":\"id\"}, multi-select=[{\"id\":\"a\"},{\"id\":\"b\"}], " +
                    "cascading={\"value\":\"parent\",\"child\":{\"value\":\"child\"}}."
            ),
            "body" to ParameterProperty(
                type = "string",
                description = "Comment body — for comment"
            ),
            "comment" to ParameterProperty(
                type = "string",
                description = "Optional comment — for transition, log_work"
            ),
            "time_spent" to ParameterProperty(
                type = "string",
                description = "Time in Jira format: '2h', '30m', '1h 30m' — for log_work"
            ),
            "board_id" to ParameterProperty(
                type = "string",
                description = "Board ID — for get_sprints, get_boards (optional filter), get_board_issues"
            ),
            "sprint_id" to ParameterProperty(
                type = "string",
                description = "Sprint ID — for get_sprint_issues"
            ),
            "type" to ParameterProperty(
                type = "string",
                description = "Board type filter: 'scrum' or 'kanban' — for get_boards",
                enumValues = listOf("scrum", "kanban")
            ),
            "name_filter" to ParameterProperty(
                type = "string",
                description = "Name filter — for get_boards"
            ),
            "text" to ParameterProperty(
                type = "string",
                description = "Search text — for search_issues"
            ),
            "jql" to ParameterProperty(
                type = "string",
                description = "Raw JQL query string — for search_tickets"
            ),
            "max_results" to ParameterProperty(
                type = "string",
                description = "Max results (default 20 for search_issues, default 8 for search_tickets)"
            ),
            "current_user_only" to ParameterProperty(
                type = "string",
                description = "Filter to the current user's tickets only ('true'/'false'). search_issues defaults 'true'; get_sprint_issues defaults 'false' (whole sprint) and applies it server-side as assignee=currentUser().",
                enumValues = listOf("true", "false")
            ),
            "attachment_id" to ParameterProperty(
                type = "string",
                description = "Jira attachment ID — for download_attachment"
            ),
            "branch_name" to ParameterProperty(
                type = "string",
                description = "Branch name to create — for start_work"
            ),
            "source_branch" to ParameterProperty(
                type = "string",
                description = "Source branch — for start_work"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of this action shown to user in approval dialog — recommended for transition, comment, start_work"
            ),
            "include_dev_status" to ParameterProperty(
                type = "boolean",
                description = "When true, get_ticket also embeds the full dev panel (branches, PRs, commits, builds, deployments, reviews) " +
                    "in the response. Default false. Use for broad questions like 'has this been deployed' or 'what's the status of this ticket'."
            ),
            "include_remote_links" to ParameterProperty(
                type = "boolean",
                description = "If true, also fetch remote links (Confluence pages, external URLs linked from this ticket). Default false."
            ),
            "include_history" to ParameterProperty(
                type = "boolean",
                description = "If true, also fetch the ticket's status/assignee/priority change history. Default false."
            ),
            "include_permissions" to ParameterProperty(
                type = "boolean",
                description = "If true, also fetch the user's permissions on this ticket's project (transition, comment, log work, watch). Default false. Useful for the LLM to check before attempting an action that may 403."
            ),
            "started" to ParameterProperty(
                type = "string",
                description = "Optional ISO 8601 datetime when the work was performed (e.g. '2026-05-10T09:00:00+00:00' or '2026-05-10') — for log_work. " +
                    "Backdating worklogs requires the underlying user to have permission; otherwise Jira returns 403. " +
                    "Defaults to 'now' when omitted."
            ),
            "author" to ParameterProperty(
                type = "string",
                description = "Optional Jira username/accountId filter — for get_worklogs (returns only worklogs by this user)"
            ),
            "since" to ParameterProperty(
                type = "string",
                description = "Optional ISO date (YYYY-MM-DD or full ISO 8601) lower bound — for get_worklogs, my_worklogs. Inclusive."
            ),
            "until" to ParameterProperty(
                type = "string",
                description = "Optional ISO date (YYYY-MM-DD or full ISO 8601) upper bound — for get_worklogs, my_worklogs. Inclusive."
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query for user lookup — for user_search. Matches displayName, username, and email."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("jira") {
        summary {
            technical(
                "Single-tool dispatcher for 17 Jira REST operations: ticket reads (with parallel dev-status / remote-links / history / permissions fan-outs), JQL search, comments, time logging, sprint+board listings, dev-status branches/PRs, transitions with typed MissingFields contract, start-work (Git+Jira composition), and attachment download (with optional image autoload + read_document hint). Bearer-auth via PasswordSafe; conditionally registered when ConnectionSettings.jiraUrl is non-blank."
            )
            plain(
                "The agent's Jira remote control. Like a Jira REST client wrapped in a single tool with 17 named operations — read a ticket, search by JQL, transition state, add a comment, log time, download an attachment, and so on. Only shows up if the user has actually configured a Jira URL in Settings; tokens stay in the OS keychain (PasswordSafe), never in the LLM's working memory."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `jira`, the LLM falls back to `run_command curl -H 'Authorization: Bearer <token>' https://jira/...`. That regresses three things at once: (1) the user's Jira token has to be either embedded in the command line (lands in shell history + agent log) or already exported as an env var (most users haven't); (2) ProcessEnvironment's sensitive-vars stripper doesn't filter command arguments, so a token-in-arg leaks; (3) typed contracts like MissingFields disappear — the LLM has to parse raw JSON error bodies instead. The whole point of this tool is to keep credentials in PasswordSafe and out of LLM context."
        )
        llmMistake(
            "Calls `transition` with a guessed transition_id instead of calling `get_transitions` first. Transitions are workflow-specific — IDs vary per project. The tool returns InvalidTransition with the available list; LLM has to retry."
        )
        llmMistake(
            "On MissingFields, the LLM tries to invent custom-field IDs (`customfield_10412`) from the field's display name. Custom-field IDs are project-specific and can only come from the MissingFields payload itself. The fix: feed the payload's (id, name) pairs into ask_followup_question literally."
        )
        llmMistake(
            "Confuses `transition` (Jira state machine move with workflow validation) with `comment` (just adds text) — when asked to 'mark this resolved' the LLM sometimes adds a comment saying 'resolved' instead of running the Resolve transition."
        )
        llmMistake(
            "Picks `search_issues` when JQL would be cleaner and `search_tickets` when free-text would be cleaner. The two-action split is intentional but the description doesn't make the cleavage line obvious."
        )
        llmMistake(
            "Uses an over-broad JQL like `project = PROJ` and gets thousands of results truncated to 8 (the search_tickets default). Should add `AND updated >= -30d AND status != Closed` or similar narrowing."
        )
        llmMistake(
            "Forgets that `download_attachment` requires BOTH `key` AND `attachment_id` — sometimes calls with only the attachment ID, gets a missing-param error."
        )
        llmMistake(
            "Calls `get_ticket` without any include_* flags, then realizes it needs dev status, then calls `get_dev_branches` separately — wasting a round-trip. The fan-out flags exist precisely to avoid this; LLM should set them up front when it knows it needs the broader context."
        )
        llmMistake(
            "Treats 401 as retryable. 401 means the token is bad; retrying just gets another 401. The right move is to surface the message to the user and stop."
        )
        flowchart(
            """
            flowchart TD
                A[LLM needs Jira data] --> B{What kind?}
                B -- specific issue --> C[get_ticket key=PROJ-123]
                C --> D{Need dev info?}
                D -- yes --> E[get_ticket include_dev_status=true]
                D -- no --> F[Done]
                B -- find issues --> G{Have JQL?}
                G -- yes --> H[search_tickets jql=...]
                G -- no, free text --> I[search_issues text=...]
                B -- modify state --> J[get_transitions key]
                J --> K[transition key transition_id]
                K --> L{MissingFields?}
                L -- yes --> M[ask_followup_question per field]
                M --> K
                L -- no --> N[Done]
                B -- start work --> O[start_work key branch_name source_branch]
                B -- attachment --> P[get_ticket key]
                P --> Q[download_attachment key attachment_id]
            """
        )
        actions {
            action("get_ticket") {
                description {
                    technical("Returns the ticket fields plus 0-4 optional fan-out blocks (dev status, remote links, history, permissions). Each include_* flag spawns a parallel async call; results merge into one text block.")
                    plain("Reads everything about a ticket. Like opening the ticket in Jira and clicking through every tab in one go — basics, dev status (PRs, builds, deploys), linked design docs, change history, your permissions on it.")
                }
                whenLLMUses("Whenever the user mentions a ticket key (`PROJ-1234`) or asks 'what's the status of X'. The default call is just (key); add include_* flags when the user asks broader questions like 'has this been deployed' (→ include_dev_status) or 'who changed this' (→ include_history).")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("The Jira ticket identifier — project prefix plus a dash plus a number.")
                        whenPresent("Validated by ToolValidation.validateJiraKey (uppercase letters, dash, digits) before the request.")
                        constraint("must match Jira issue-key format: ^[A-Z][A-Z0-9_]+-\\d+$")
                        example("PROJ-1234")
                        example("ABC-9")
                    }
                    optional("include_dev_status", "boolean") {
                        llmSeesIt("When true, get_ticket also embeds the full dev panel (branches, PRs, commits, builds, deployments, reviews) in the response. Default false. Use for broad questions like 'has this been deployed' or 'what's the status of this ticket'.")
                        humanReadable("Adds the Dev Status panel data (branches, PRs, commits, builds, deployments, reviews) to the response.")
                        whenPresent("A second async call runs `service.getFullDevStatus(key)` in parallel; result is appended.")
                        whenAbsent("Only the basic ticket fields are fetched — one HTTP call.")
                        example("true")
                    }
                    optional("include_remote_links", "boolean") {
                        llmSeesIt("If true, also fetch remote links (Confluence pages, external URLs linked from this ticket). Default false.")
                        humanReadable("Adds remote-link data (Confluence pages, Figma mocks, external URLs) to the response.")
                        whenPresent("Parallel async call to `service.getRemoteLinks(key)`; result appended as `Remote Links` block.")
                        whenAbsent("Remote links not fetched.")
                        example("true")
                    }
                    optional("include_history", "boolean") {
                        llmSeesIt("If true, also fetch the ticket's status/assignee/priority change history. Default false.")
                        humanReadable("Adds the change-log (who changed status/assignee/priority and when).")
                        whenPresent("Parallel async call to `service.getTicketHistory(key)`; last 20 entries appended.")
                        whenAbsent("History not fetched.")
                        example("true")
                    }
                    optional("include_permissions", "boolean") {
                        llmSeesIt("If true, also fetch the user's permissions on this ticket's project (transition, comment, log work, watch). Default false. Useful for the LLM to check before attempting an action that may 403.")
                        humanReadable("Adds the current user's permission flags on this project — useful before attempting a write action that might 403.")
                        whenPresent("Parallel async call to `service.getMyPermissions(projectKey)`; result appended as `Permissions` block.")
                        whenAbsent("Permissions not fetched.")
                        example("true")
                    }
                }
                precondition("Jira URL must be configured in Settings (otherwise the tool isn't registered).")
                precondition("user must have Browse permission on the project containing this issue (else 403).")
                onSuccess("Returns a single text block: ticket header (key, summary, status, assignee, reporter, priority, fix versions, etc.) followed by 0-4 fan-out sections in order — Dev Status, Remote Links, History, Permissions. Token estimate sums all blocks.")
                onFailure("issue not found", "Returns a 404-shaped error with the issue key. LLM should re-confirm with the user (often a typo).")
                onFailure("403 Forbidden", "User lacks Browse permission. Surface to user; don't retry.")
                onFailure("401 Unauthorized", "Token expired or revoked. Stop and ask the user to re-enter the token in Settings.")
                onFailure("one fan-out errors but ticket succeeds", "Failed fan-out is silently dropped (`takeIf { !it.isError }`). The successful ticket block plus any other successful fan-outs are returned. Partial result is the design.")
                example("simple read") {
                    param("action", "get_ticket")
                    param("key", "PROJ-1234")
                    outcome("Returns ticket fields only; one HTTP call.")
                }
                example("'has this shipped?'") {
                    param("action", "get_ticket")
                    param("key", "PROJ-1234")
                    param("include_dev_status", "true")
                    outcome("Returns ticket plus full Dev Status panel (PRs, builds, deployments) in one block.")
                    notes("Saves 4-5 follow-up tool calls (get_dev_branches, get_linked_prs, etc.) by fanning out in parallel.")
                }
                example("pre-flight permission check") {
                    param("action", "get_ticket")
                    param("key", "PROJ-1234")
                    param("include_permissions", "true")
                    outcome("Returns ticket plus a Permissions block listing granted/denied flags so LLM can decide whether to even attempt a transition.")
                }
                verdict {
                    keep("The most-used Jira action. Token cost of the fan-out flags pays for itself by avoiding 3-4 follow-up calls.", VerdictSeverity.STRONG)
                }
            }
            action("get_transitions") {
                description {
                    technical("Returns available next-status transitions for the ticket — each with id, name, toStatus.")
                    plain("Asks Jira 'from this state, where can this ticket go?' Always call this before `transition` because transition IDs are workflow-specific.")
                }
                whenLLMUses("Before any `transition` call when the LLM doesn't already know the right transition_id (which is essentially always).")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket to query.")
                        whenPresent("Validated as a Jira issue key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                }
                onSuccess("Returns a list of `(id, name, toStatus)` triples — e.g. `ID 31: Start Work → In Progress`, `ID 41: Resolve Issue → Resolved`.")
                onFailure("ticket not found", "404 surfaced; LLM should re-confirm the key.")
                onFailure("user lacks transition permission", "Empty list returned by Jira (not an error). LLM has to recognize the empty case as 'you can't transition this'.")
                example("standard pre-transition") {
                    param("action", "get_transitions")
                    param("key", "PROJ-1234")
                    outcome("LLM picks the matching `id` from the result for the next `transition` call.")
                }
                verdict {
                    keep("Required precondition for `transition`. Cannot be skipped because Jira's transition IDs are workflow-specific.", VerdictSeverity.STRONG)
                }
            }
            action("transition") {
                description {
                    technical("Moves a ticket to a new status. Delegates to TicketTransitionService.executeTransition which validates required fields. On MissingFields error, returns a typed payload with field metadata so the LLM can collect values via ask_followup_question and retry.")
                    plain("Press a button in Jira's workflow — 'Resolve Issue', 'Start Progress', etc. If the workflow asks for extra fields (like a Resolution dropdown), the tool tells you exactly which fields and how to format them, then you call again with the values.")
                }
                whenLLMUses("Whenever the user says 'resolve this', 'start work on X', 'close PROJ-123', etc. Always preceded by `get_transitions` to find the right id.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket to transition.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    required("transition_id", "string") {
                        llmSeesIt("Transition ID — for transition (use get_transitions first)")
                        humanReadable("The numeric ID of the workflow transition to invoke. Comes from `get_transitions`.")
                        whenPresent("That transition is invoked.")
                        constraint("must be a numeric string from a prior get_transitions call")
                        example("31")
                        example("41")
                    }
                    optional("fields", "object") {
                        llmSeesIt("Optional field values for transition screens — for transition. Keys are Jira field IDs (e.g. \"assignee\", \"labels\", \"priority\"). Values follow the Jira field-value format: user={\"name\":\"username\"}, labels=[\"l1\",\"l2\"], option/priority={\"id\":\"id\"}, multi-select=[{\"id\":\"a\"},{\"id\":\"b\"}], cascading={\"value\":\"parent\",\"child\":{\"value\":\"child\"}}.")
                        humanReadable("Field values to set as part of the transition (e.g. resolution = Fixed). Format varies by field type — see the constraints.")
                        whenPresent("Coerced to typed FieldValues via parseFieldsJson; passed to TicketTransitionService.")
                        whenAbsent("Empty fields map; transitions that require fields will return MissingFields.")
                        constraint("user/assignee: {\"name\": \"<username>\"}")
                        constraint("labels: [\"label1\", \"label2\"]")
                        constraint("priority/select/option: {\"id\": \"<id>\"}")
                        constraint("multi-select: [{\"id\": \"a\"}, {\"id\": \"b\"}]")
                        constraint("cascading: {\"value\": \"parent\", \"child\": {\"value\": \"child\"}}")
                        example("{\"resolution\": {\"id\": \"10000\"}}")
                        example("{\"assignee\": {\"name\": \"jdoe\"}, \"labels\": [\"hotfix\"]}")
                    }
                    optional("comment", "string") {
                        llmSeesIt("Optional comment — for transition, log_work")
                        humanReadable("Comment to attach to the transition (visible in the ticket history).")
                        whenPresent("Comment included with the transition request.")
                        whenAbsent("No comment; transition is silent in the activity log.")
                        example("Resolving — fix shipped in v1.2.3.")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of this action shown to user in approval dialog — recommended for transition, comment, start_work")
                        humanReadable("One-line description for the agent's approval dialog. Recommended.")
                        whenPresent("Shown in the approval gate; helps the user decide whether to approve.")
                        whenAbsent("Approval dialog falls back to action+key.")
                        example("Resolve PROJ-1234 as Fixed")
                    }
                }
                precondition("ticket must exist and be transitionable from its current status to the target")
                precondition("user must have the Transition Issues project permission")
                onSuccess("Returns `Transitioned PROJ-1234: <fromStatus> → <toStatus>. <summary>`.")
                onFailure("MissingFields", "Returns isError=true with `payload_type: missing_required_fields`, the list of (id, name, required, schema) entries, and guidance text. LLM should call ask_followup_question per field, then retry with `fields={...}`.")
                onFailure("InvalidTransition", "Returns `payload_type: invalid_transition` with reason. Usually the transition_id doesn't apply to the current status — call get_transitions and pick a valid one.")
                onFailure("RequiresInteraction", "Returns `payload_type: requires_interaction` — the transition has a screen with conditional/scripted fields the agent can't satisfy. Surface to user.")
                onFailure("Forbidden", "Returns `payload_type: forbidden`. User lacks the Transition Issues permission.")
                onFailure("Network", "Returns `payload_type: network_error` with cause. LLM should pause briefly before retry; persistent network failure → ask user to check VPN.")
                example("simple transition with comment") {
                    param("action", "transition")
                    param("key", "PROJ-1234")
                    param("transition_id", "41")
                    param("comment", "Fix shipped in v1.2.3")
                    param("description", "Resolve PROJ-1234")
                    outcome("Ticket moves to Resolved; comment appears in activity log.")
                }
                example("transition requiring resolution field") {
                    param("action", "transition")
                    param("key", "PROJ-1234")
                    param("transition_id", "41")
                    param("fields", "{\"resolution\": {\"id\": \"10000\"}}")
                    outcome("Resolution = Fixed (10000); ticket moves to Resolved.")
                    notes("First attempt without `fields` returned MissingFields; LLM fetched the field id from the payload and retried.")
                }
                verdict {
                    keep("State machine moves are the highest-value Jira write op — they're what wraps a coding task. The MissingFields contract is what makes this safe vs guessing.", VerdictSeverity.STRONG)
                }
            }
            action("comment") {
                description {
                    technical("Adds a comment to the ticket. Pre-flight: verifies ticket exists via getTicket before posting.")
                    plain("Adds a comment on a Jira ticket — same as typing in the comment box and clicking Add.")
                }
                whenLLMUses("When the user asks 'leave a comment saying X' or wants to record context on a ticket without changing its status.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket gets the comment.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    required("body", "string") {
                        llmSeesIt("Comment body — for comment")
                        humanReadable("The comment text. Plain text or Jira wiki markup.")
                        whenPresent("Posted as the body of a new comment.")
                        constraint("must be non-blank (validateNotBlank)")
                        example("Investigated — root cause is the timezone conversion in OrderService.kt:42")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of this action shown to user in approval dialog — recommended for transition, comment, start_work")
                        humanReadable("One-line description for the approval dialog.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval dialog falls back to action+key.")
                        example("Add investigation summary to PROJ-1234")
                    }
                }
                precondition("ticket must exist (pre-flight check enforces this)")
                precondition("user must have Add Comments permission")
                onSuccess("Returns the comment id and a summary.")
                onFailure("ticket not found", "Returns isError=true with explicit `Cannot add comment: ticket PROJ-1234 not found.` LLM should re-confirm key with user.")
                onFailure("403", "Lacks Add Comments permission — surface to user.")
                onFailure("blank body", "ToolValidation.validateNotBlank rejects before the network call.")
                example("recording a finding") {
                    param("action", "comment")
                    param("key", "PROJ-1234")
                    param("body", "Repro confirmed on staging build #4567. See logs at https://...")
                    param("description", "Add repro confirmation to PROJ-1234")
                    outcome("Comment posted; activity log updated.")
                }
                verdict {
                    keep("Frequent enough — every multi-step task tends to want a status comment at the end.", VerdictSeverity.NORMAL)
                }
            }
            action("get_comments") {
                description {
                    technical("Lists comments on a ticket.")
                    plain("Reads back all comments on the ticket — like scrolling through the comment thread.")
                }
                whenLLMUses("When the user asks 'what was discussed on this ticket' or the LLM needs to understand prior decisions before acting.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket's comments to fetch.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                }
                onSuccess("Returns the comments list (author, created date, body) in chronological order.")
                onFailure("ticket not found", "404; LLM should re-confirm the key.")
                onFailure("403", "Lacks Browse permission — surface to user.")
                example("read thread") {
                    param("action", "get_comments")
                    param("key", "PROJ-1234")
                    outcome("Returns N comments newest-last (or oldest-first per Jira convention).")
                }
                verdict {
                    keep("Useful for context-gathering before recommending an action.", VerdictSeverity.NORMAL)
                    drop("Could be folded into get_ticket as `include_comments=true` — saves one action slot. Tradeoff: get_ticket already has 4 fan-outs; adding a 5th lengthens the description.", VerdictSeverity.WEAK)
                }
            }
            action("log_work") {
                description {
                    technical("Logs time against a ticket. Pre-flight: verifies ticket exists. Time format must be Jira-compatible (`2h`, `30m`, `1h 30m`).")
                    plain("Logs time spent on a ticket — like filling in the 'Log Work' dialog in Jira.")
                }
                whenLLMUses("When the user asks 'log 2 hours on PROJ-123' or after completing a multi-hour task they want tracked.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket to log time on.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    required("time_spent", "string") {
                        llmSeesIt("Time in Jira format: '2h', '30m', '1h 30m' — for log_work")
                        humanReadable("How long was spent — Jira's compact format. Hours and minutes; days/weeks if your project allows them.")
                        whenPresent("Validated by ToolValidation.validateTimeSpent then passed through to Jira.")
                        constraint("must match Jira time format (e.g. `2h`, `30m`, `1h 30m`, `1d`)")
                        example("2h")
                        example("30m")
                        example("1h 30m")
                    }
                    optional("comment", "string") {
                        llmSeesIt("Optional comment — for transition, log_work")
                        humanReadable("Optional description of what the time was spent on.")
                        whenPresent("Stored on the worklog entry.")
                        whenAbsent("Worklog has no description.")
                        example("Investigated timezone bug in OrderService")
                    }
                    optional("started", "string") {
                        llmSeesIt("Optional ISO 8601 datetime when the work was performed (e.g. '2026-05-10T09:00:00+00:00' or '2026-05-10') — for log_work. Backdating worklogs requires the underlying user to have permission; otherwise Jira returns 403. Defaults to 'now' when omitted.")
                        humanReadable("When the work was performed. Omit to use the current time; provide a past datetime to backdate the entry (requires permission).")
                        whenPresent("Passed as the worklog's `started` field; Jira stores it verbatim.")
                        whenAbsent("Defaults to the current date and time.")
                        example("2026-05-10T09:00:00+00:00")
                        example("2026-05-10")
                    }
                }
                precondition("ticket must exist (pre-flight check enforces this)")
                precondition("user must have Work On Issues permission")
                onSuccess("Returns the worklog id and confirmation.")
                onFailure("ticket not found", "Returns explicit `Cannot log work: ticket PROJ-1234 not found.`")
                onFailure("invalid time format", "Rejected by validateTimeSpent before the network call.")
                onFailure("403", "Lacks Work On Issues permission.")
                example("after a 90-min debugging session") {
                    param("action", "log_work")
                    param("key", "PROJ-1234")
                    param("time_spent", "1h 30m")
                    param("comment", "Investigated and fixed the timezone bug")
                    outcome("Worklog created; ticket's logged-work total updated.")
                }
                verdict {
                    keep("Cheap to keep; users who care about time tracking really care.", VerdictSeverity.NORMAL)
                    drop("Observationally low-frequency: most users log time via the IDE's time-tracking UI or via Tempo — not via the agent. Could be a drop candidate if action-level usage tracking confirmed.", VerdictSeverity.WEAK)
                }
            }
            action("get_worklogs") {
                description {
                    technical("Lists worklog entries for a ticket. Accepts `key`, `issue_key`, or `issue_id` for compat.")
                    plain("Reads back the time entries on a ticket — who logged how much when.")
                }
                whenLLMUses("Rarely — only when the user explicitly asks 'how much time has been logged on this'.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket's worklogs to fetch. `issue_key` and `issue_id` are also accepted as aliases.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    optional("author", "string") {
                        llmSeesIt("Optional Jira username/accountId filter — for get_worklogs (returns only worklogs by this user)")
                        humanReadable("Filter results to entries logged by this specific user. Accepts Jira username (DC) or accountId (Cloud).")
                        whenPresent("Only worklogs by that user are returned.")
                        whenAbsent("All authors' worklogs for the ticket are returned.")
                        example("jdoe")
                    }
                    optional("since", "string") {
                        llmSeesIt("Optional ISO date (YYYY-MM-DD or full ISO 8601) lower bound — for get_worklogs, my_worklogs. Inclusive.")
                        humanReadable("Only include worklogs started on or after this date. Inclusive.")
                        whenPresent("Worklogs whose `started` field is on or after this value are included.")
                        whenAbsent("No lower bound applied; all dates included.")
                        example("2026-05-01")
                        example("2026-05-01T00:00:00+00:00")
                    }
                    optional("until", "string") {
                        llmSeesIt("Optional ISO date (YYYY-MM-DD or full ISO 8601) upper bound — for get_worklogs, my_worklogs. Inclusive.")
                        humanReadable("Only include worklogs started on or before this date. Inclusive.")
                        whenPresent("Worklogs whose `started` field is on or before this value are included.")
                        whenAbsent("No upper bound applied; all dates included.")
                        example("2026-05-31")
                        example("2026-05-31T23:59:59+00:00")
                    }
                }
                onSuccess("Returns worklog entries (author, time spent, started date, comment).")
                onFailure("ticket not found", "404 surfaced.")
                onFailure("403", "Lacks Browse permission.")
                example("audit trail") {
                    param("action", "get_worklogs")
                    param("key", "PROJ-1234")
                    outcome("Returns the time-log table for the ticket.")
                }
                verdict {
                    drop("Highest drop-candidate among the 17 actions. Users who want time-log readback typically open Jira directly. Removing this action would save its description line (~20 tokens) and one schema slot. Keep only if action-level usage shows non-trivial calls.", VerdictSeverity.NORMAL)
                }
            }
            action("get_sprints") {
                description {
                    technical("Lists active+future sprints for a Scrum board. board_id must be an integer string.")
                    plain("Lists the sprints on a board — current and upcoming. Scrum boards only.")
                }
                whenLLMUses("When the user asks 'what sprint are we in' or wants to plan into a future sprint.")
                params {
                    required("board_id", "string") {
                        llmSeesIt("Board ID — for get_sprints, get_boards (optional filter), get_board_issues")
                        humanReadable("The numeric Jira board ID. Get one from `get_boards`.")
                        whenPresent("Parsed as integer; `service.getAvailableSprints(boardId)` called.")
                        constraint("must be a numeric string convertible via toIntOrNull")
                        example("42")
                    }
                }
                onSuccess("Returns sprints (id, name, state, start/end dates).")
                onFailure("board_id not numeric", "Returns explicit error before network call.")
                onFailure("board not found", "404 surfaced.")
                onFailure("kanban board (no sprints)", "Empty list — LLM should recognize as 'this isn't a scrum board'.")
                example("'what sprint are we in'") {
                    param("action", "get_sprints")
                    param("board_id", "42")
                    outcome("Returns active+future sprints; LLM picks the one with `state: ACTIVE`.")
                }
                verdict {
                    keep("Required for any sprint-aware workflow.", VerdictSeverity.NORMAL)
                    drop("Low frequency — sprint info changes rarely and tends to be a UI lookup. Drop candidate if usage tracking confirms.", VerdictSeverity.WEAK)
                }
            }
            action("get_linked_prs") {
                description {
                    technical("Returns PRs linked from the ticket via Dev Status / Application Links. Subset of `get_ticket(include_dev_status=true)`.")
                    plain("Lists the pull requests linked to this ticket.")
                }
                whenLLMUses("When narrowly asked 'what PRs are linked to this ticket' and the LLM doesn't need the rest of the dev panel.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket. `issue_key` and `issue_id` aliases accepted.")
                        whenPresent("Used as the issue identifier.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                }
                onSuccess("Returns linked PR list (status, name, URL).")
                onFailure("ticket not found", "404 surfaced.")
                onFailure("Application Links not configured", "Empty list — Dev Status panel needs Bitbucket↔Jira app link configured server-side.")
                example("PR triage") {
                    param("action", "get_linked_prs")
                    param("key", "PROJ-1234")
                    outcome("Returns 0..N linked PRs; LLM picks the OPEN one to follow up on.")
                }
                verdict {
                    keep("Cheap, narrow, useful when LLM only needs PR data.", VerdictSeverity.NORMAL)
                    drop("Overlaps with `get_ticket(include_dev_status=true)`. Could be replaced by guidance to use the include flag — saves one action slot.", VerdictSeverity.WEAK)
                }
            }
            action("get_boards") {
                description {
                    technical("Lists boards. Optional filters: type (scrum|kanban), name_filter (substring match).")
                    plain("Lists Jira boards — Scrum and/or Kanban. Filterable by name.")
                }
                whenLLMUses("Rare — only when the LLM needs to discover board IDs to chain into get_sprints / get_board_issues.")
                params {
                    optional("type", "string") {
                        llmSeesIt("Board type filter: 'scrum' or 'kanban' — for get_boards")
                        humanReadable("Filter by board type.")
                        whenPresent("Only boards of that type are returned.")
                        whenAbsent("All boards (both types) returned.")
                        enumValue("scrum", "kanban")
                    }
                    optional("name_filter", "string") {
                        llmSeesIt("Name filter — for get_boards")
                        humanReadable("Substring match on board name.")
                        whenPresent("Server-side filter applied.")
                        whenAbsent("All board names returned.")
                        example("Mobile")
                    }
                }
                onSuccess("Returns boards (id, name, type, project key).")
                onFailure("Jira returns 0 boards", "Empty list — common if the user hasn't been added to any boards.")
                example("find the mobile scrum board") {
                    param("action", "get_boards")
                    param("type", "scrum")
                    param("name_filter", "Mobile")
                    outcome("Returns boards whose name contains 'Mobile' AND are scrum boards.")
                }
                verdict {
                    keep("Necessary first step for any sprint/board workflow.", VerdictSeverity.NORMAL)
                }
            }
            action("get_sprint_issues") {
                description {
                    technical("Lists issues in a specific sprint. sprint_id must be an integer string.")
                    plain("Lists the tickets in a sprint — like opening the sprint page in Jira.")
                }
                whenLLMUses("Sprint planning, sprint review, or 'what's in the current sprint' questions.")
                params {
                    required("sprint_id", "string") {
                        llmSeesIt("Sprint ID — for get_sprint_issues")
                        humanReadable("The numeric sprint id. Get one from `get_sprints`.")
                        whenPresent("Parsed as integer.")
                        constraint("must be a numeric string convertible via toIntOrNull")
                        example("123")
                    }
                    optional("current_user_only", "string") {
                        llmSeesIt("Filter to the current user's tickets only ('true'/'false'). search_issues defaults 'true'; get_sprint_issues defaults 'false' (whole sprint) and applies it server-side as assignee=currentUser().")
                        humanReadable("When true, only your assigned issues are returned, resolved server-side the same way the Sprint tab does.")
                        whenPresent("'true' adds a server-side assignee=currentUser() filter; 'false' returns every issue in the sprint.")
                        whenAbsent("Defaults to the full sprint issue list (all assignees).")
                        enumValue("true", "false")
                    }
                }
                onSuccess("Returns the sprint's issue list (scoped to you when current_user_only=true).")
                onFailure("sprint_id not numeric", "Explicit error before network.")
                onFailure("sprint not found", "404 surfaced.")
                example("'what's in this sprint'") {
                    param("action", "get_sprint_issues")
                    param("sprint_id", "123")
                    outcome("Returns N issues in the sprint with summary, status, assignee.")
                }
                verdict {
                    keep("Sprint-scoped queries are common in retro/standup contexts.", VerdictSeverity.NORMAL)
                }
            }
            action("get_board_issues") {
                description {
                    technical("Lists issues on a board. Largely overlaps with running JQL via search_tickets.")
                    plain("Lists tickets on a board — basically the board's default view.")
                }
                whenLLMUses("Rarely. Most LLM queries that look like 'show me all open tickets' translate to JQL via search_tickets, which is more flexible.")
                params {
                    required("board_id", "string") {
                        llmSeesIt("Board ID — for get_sprints, get_boards (optional filter), get_board_issues")
                        humanReadable("The numeric board id.")
                        whenPresent("Parsed as integer.")
                        constraint("must be a numeric string convertible via toIntOrNull")
                        example("42")
                    }
                }
                onSuccess("Returns board-filter-applied issue list.")
                onFailure("board_id not numeric", "Explicit error.")
                onFailure("board not found", "404.")
                example("board snapshot") {
                    param("action", "get_board_issues")
                    param("board_id", "42")
                    outcome("Returns issues matching the board's saved filter.")
                }
                verdict {
                    drop("Drop candidate. Overlaps semantically with search_tickets, which is strictly more powerful (JQL > board filter). Removing this action saves a description line and removes a 'which to use?' decision for the LLM.", VerdictSeverity.NORMAL)
                }
            }
            action("search_issues") {
                description {
                    technical("Free-text issue search. Defaults: max_results=20, current_user_only=true. Server translates to a `text ~ '...'` JQL roughly.")
                    plain("Search Jira for tickets matching free text. Defaults to 'mine, recent, top 20'.")
                }
                whenLLMUses("When the user has natural-language intent ('find my open auth tickets') rather than a JQL string.")
                params {
                    required("text", "string") {
                        llmSeesIt("Search text — for search_issues")
                        humanReadable("What to search for.")
                        whenPresent("Used as the search text.")
                        constraint("must be non-blank")
                        example("auth bug")
                        example("OAuth refresh failing")
                    }
                    optional("max_results", "string") {
                        llmSeesIt("Max results (default 20 for search_issues, default 8 for search_tickets)")
                        humanReadable("Cap on how many results to return.")
                        whenPresent("Parsed as int; cap applied server-side.")
                        whenAbsent("Defaults to 20.")
                        constraint("must be a numeric string")
                        example("50")
                    }
                    optional("current_user_only", "string") {
                        llmSeesIt("Filter to the current user's tickets only ('true'/'false'). search_issues defaults 'true'; get_sprint_issues defaults 'false' (whole sprint) and applies it server-side as assignee=currentUser().")
                        humanReadable("Only include tickets where the current user is reporter or assignee.")
                        whenPresent("If 'false', searches across all users; otherwise scopes to current user.")
                        whenAbsent("Defaults to 'true' (current user only).")
                        enumValue("true", "false")
                    }
                }
                onSuccess("Returns matched issues with key, summary, status.")
                onFailure("blank text", "Rejected by validateNotBlank before the request.")
                onFailure("Jira search service down (5xx)", "Network error surfaced; retry once.")
                example("find my auth bugs") {
                    param("action", "search_issues")
                    param("text", "auth")
                    outcome("Returns up to 20 of the current user's tickets containing 'auth'.")
                }
                example("broader search") {
                    param("action", "search_issues")
                    param("text", "OAuth refresh")
                    param("current_user_only", "false")
                    param("max_results", "50")
                    outcome("Returns up to 50 tickets across all users matching the phrase.")
                }
                verdict {
                    keep("Natural-language entry point is what most LLM-driven queries use.", VerdictSeverity.STRONG)
                }
            }
            action("search_tickets") {
                description {
                    technical("Raw JQL pass-through. Default max_results=8 (smaller than search_issues to keep token cost down on broad queries).")
                    plain("Run a JQL query directly. For when you know JQL and want exact control.")
                }
                whenLLMUses("When the LLM has a JQL string in mind — typically because the prompt mentions specific JQL operators, dates, or status names.")
                params {
                    required("jql", "string") {
                        llmSeesIt("Raw JQL query string — for search_tickets")
                        humanReadable("A JQL expression — Jira's query language.")
                        whenPresent("Sent verbatim to Jira.")
                        constraint("must be non-blank; valid JQL syntax (validation is server-side)")
                        example("project = PROJ AND status = \"In Review\" AND updated >= -7d")
                        example("assignee = currentUser() AND resolution = Unresolved")
                    }
                    optional("max_results", "string") {
                        llmSeesIt("Max results (default 20 for search_issues, default 8 for search_tickets)")
                        humanReadable("Cap on results returned.")
                        whenPresent("Cap applied.")
                        whenAbsent("Defaults to 8.")
                        constraint("must be a numeric string")
                        example("25")
                    }
                }
                onSuccess("Returns issues matching the JQL.")
                onFailure("invalid JQL syntax", "Jira returns a 400 with the syntax error message; surface verbatim to LLM so it can fix the query.")
                onFailure("blank jql", "Rejected by validateNotBlank.")
                example("recent in-review tickets") {
                    param("action", "search_tickets")
                    param("jql", "project = PROJ AND status = \"In Review\" AND updated >= -7d")
                    outcome("Returns tickets in PROJ in 'In Review' updated within the last week.")
                }
                example("my unresolved") {
                    param("action", "search_tickets")
                    param("jql", "assignee = currentUser() AND resolution = Unresolved ORDER BY priority DESC")
                    param("max_results", "25")
                    outcome("Returns my unresolved tickets, highest priority first.")
                }
                verdict {
                    keep("JQL is the most powerful Jira primitive; LLMs are surprisingly good at writing it.", VerdictSeverity.STRONG)
                }
            }
            action("get_dev_branches") {
                description {
                    technical("Returns Dev Status branch list for a ticket. Subset of `get_ticket(include_dev_status=true)`.")
                    plain("Lists Git branches linked to this ticket.")
                }
                whenLLMUses("When narrowly asked 'what branches are on this ticket' — e.g. before deciding which branch to check out.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket. `issue_key` and `issue_id` aliases accepted.")
                        whenPresent("Used as the issue identifier.")
                        constraint("must be non-blank")
                        example("PROJ-1234")
                    }
                }
                onSuccess("Returns branch list (name, URL).")
                onFailure("ticket not found", "404.")
                onFailure("Application Links not configured", "Empty list.")
                example("which branch should I check out") {
                    param("action", "get_dev_branches")
                    param("key", "PROJ-1234")
                    outcome("Returns linked branches; LLM picks the most recent.")
                }
                verdict {
                    keep("Useful for branch-discovery workflow.", VerdictSeverity.NORMAL)
                    drop("Overlaps with `get_ticket(include_dev_status=true)`. Could fold into that.", VerdictSeverity.WEAK)
                }
            }
            action("start_work") {
                description {
                    technical("Composite action: creates a Git branch off source_branch and (per JiraService semantics) typically transitions the ticket to In Progress.")
                    plain("'Start work' on a ticket — creates a branch named after it AND moves the ticket to In Progress in one step.")
                }
                whenLLMUses("When the user says 'start work on PROJ-123' or 'I'm picking up X' — saves three sequential calls (create branch, transition, comment).")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket to start work on. `issue_key`/`issue_id` aliases accepted.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    required("branch_name", "string") {
                        llmSeesIt("Branch name to create — for start_work")
                        humanReadable("The new branch's name. Convention: `feature/PROJ-1234-short-slug`.")
                        whenPresent("Branch is created off source_branch.")
                        constraint("must be non-blank; should follow Git ref naming rules")
                        example("feature/PROJ-1234-fix-timezone")
                    }
                    required("source_branch", "string") {
                        llmSeesIt("Source branch — for start_work")
                        humanReadable("The branch to fork off (usually `main`, `develop`, or a release branch).")
                        whenPresent("Used as the new branch's parent.")
                        constraint("must be non-blank; must exist in the repo")
                        example("main")
                        example("develop")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of this action shown to user in approval dialog — recommended for transition, comment, start_work")
                        humanReadable("Description for the approval dialog.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval dialog falls back to action+key.")
                        example("Start work on PROJ-1234 (branch off main)")
                    }
                }
                precondition("Jira ticket exists and is transitionable to In Progress")
                precondition("Git repo is configured and source_branch exists")
                precondition("user has Transition Issues permission")
                onSuccess("Returns confirmation: branch created, ticket transitioned (when applicable).")
                onFailure("source_branch not found", "Git error surfaced; LLM should re-confirm with user (often a typo or default branch name mismatch).")
                onFailure("branch already exists", "Git error; LLM should pick a different name.")
                onFailure("transition forbidden", "Branch may still be created; surface the partial-success state to user.")
                example("standard start") {
                    param("action", "start_work")
                    param("key", "PROJ-1234")
                    param("branch_name", "feature/PROJ-1234-fix-tz")
                    param("source_branch", "main")
                    param("description", "Start work on PROJ-1234 — fix tz bug")
                    outcome("Branch created off main; ticket moved to In Progress.")
                }
                verdict {
                    keep("High-value composition. Replaces 3 calls with 1 and matches the daily 'start work' workflow.", VerdictSeverity.STRONG)
                }
            }
            action("download_attachment") {
                description {
                    technical("Downloads an attachment to {sessionDir}/downloads/jira-{attachmentId}/{filename}. For image MIMEs (when PluginSettings.enableToolImageAutoload + whitelist match), bytes also load into the per-session AttachmentStore as imageRefs so BrainRouter routes the next call through /.api/completions/stream. For document classes (PDF/DOC/DOCX/etc.), appends a `read_document` hint paragraph.")
                    plain("Downloads a Jira attachment to disk. If it's an image and image-autoload is on, the agent can also see it directly. If it's a PDF/Word/Excel/etc., the response includes a hint pointing at `read_document` to extract text.")
                }
                whenLLMUses("When the user references a screenshot, design doc, or report attached to a ticket and asks the agent to use it.")
                params {
                    required("key", "string") {
                        llmSeesIt("Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue")
                        humanReadable("Which ticket the attachment belongs to.")
                        whenPresent("Validated as a Jira key.")
                        constraint("must match Jira issue-key format")
                        example("PROJ-1234")
                    }
                    required("attachment_id", "string") {
                        llmSeesIt("Jira attachment ID — for download_attachment")
                        humanReadable("The numeric attachment id from the ticket. Get it from `get_ticket` (which lists attachments).")
                        whenPresent("Used as the attachment identifier in the GET request.")
                        constraint("must be non-blank; typically numeric in Jira DC")
                        example("10042")
                    }
                }
                precondition("ticket exists and the attachment_id belongs to it")
                precondition("user has Browse permission on the ticket")
                precondition("for image autoload: PluginSettings.enableToolImageAutoload is true AND mime is in toolImageAutoloadMimeWhitelist AND the agent loop is wrapped by AgentLoopAttachmentScope (provides SessionAttachmentAccess.current())")
                onSuccess("Returns: filepath on disk, filename, mime, size. For images that pass autoload: response includes imageRefs so the next LLM turn sees the image. For documents: appends a `read_document` hint paragraph with the absolute path.")
                onFailure("attachment not found", "404 surfaced; LLM should re-fetch get_ticket to get a fresh attachment list.")
                onFailure("403", "Browse permission missing.")
                onFailure("autoload off / mime not whitelisted / no session store", "Falls back to text-only download confirmation; no imageRefs added. The file is still on disk; LLM can `read_file` it directly for text formats or `read_document` for documents.")
                onFailure("disk write fails", "Returns isError=true with the IO error.")
                example("read a PDF design doc") {
                    param("action", "download_attachment")
                    param("key", "PROJ-1234")
                    param("attachment_id", "10042")
                    outcome("File saved at {sessionDir}/downloads/jira-10042/design.pdf; response includes a `read_document` hint paragraph for PDF extraction.")
                }
                example("read a screenshot (autoload on)") {
                    param("action", "download_attachment")
                    param("key", "PROJ-1234")
                    param("attachment_id", "10043")
                    outcome("File saved + imageRefs populated; next LLM call sees the image via BrainRouter's stream route.")
                    notes("Requires PluginSettings.enableToolImageAutoload=true and image/png in the whitelist.")
                }
                verdict {
                    keep("Without this, screenshots/design-docs attached to tickets are invisible to the agent. The image-autoload integration is what makes 'look at this screenshot on the ticket' a one-call workflow.", VerdictSeverity.STRONG)
                }
            }
            action("my_worklogs") {
                description {
                    technical("Lists worklogs authored by the current user across all tickets. Supports optional date-range filtering via `since`/`until`.")
                    plain("Shows all time entries logged by you — across all tickets — optionally scoped to a date window.")
                }
                whenLLMUses("When the user asks 'what did I log this week' or wants a summary of their own time entries.")
                params {
                    optional("since", "string") {
                        llmSeesIt("Optional ISO date (YYYY-MM-DD or full ISO 8601) lower bound — for get_worklogs, my_worklogs. Inclusive.")
                        humanReadable("Only include worklogs started on or after this date. Inclusive.")
                        whenPresent("Worklogs whose `started` field is on or after this value are included.")
                        whenAbsent("No lower bound applied; all dates included.")
                        example("2026-05-01")
                    }
                    optional("until", "string") {
                        llmSeesIt("Optional ISO date (YYYY-MM-DD or full ISO 8601) upper bound — for get_worklogs, my_worklogs. Inclusive.")
                        humanReadable("Only include worklogs started on or before this date. Inclusive.")
                        whenPresent("Worklogs whose `started` field is on or before this value are included.")
                        whenAbsent("No upper bound applied; all dates included.")
                        example("2026-05-31")
                    }
                }
                onSuccess("Returns the current user's worklog entries (ticket key, time spent, started date, comment) across all projects.")
                onFailure("no worklogs found", "Returns an empty list; not an error.")
                example("this week's log") {
                    param("action", "my_worklogs")
                    param("since", "2026-05-19")
                    param("until", "2026-05-23")
                    outcome("Returns time entries the current user logged between Mon and Fri of the week.")
                }
                verdict {
                    keep("Useful for quick 'what did I log this week' queries without needing a ticket key.", VerdictSeverity.NORMAL)
                }
            }
            action("user_search") {
                description {
                    technical("Searches Jira users by display name, username, or email. Returns a list of matching user profiles.")
                    plain("Finds Jira users by name or email — useful before populating assignee/reviewer fields.")
                }
                whenLLMUses("When the LLM needs to resolve a human name ('John Doe') to a Jira username/accountId before setting an assignee or reviewer field.")
                params {
                    required("query", "string") {
                        llmSeesIt("Search query for user lookup — for user_search. Matches displayName, username, and email.")
                        humanReadable("A name fragment, username prefix, or email address to search for.")
                        whenPresent("Sent as the search query to Jira's user-search endpoint.")
                        constraint("must be non-blank")
                        example("John")
                        example("jdoe@example.com")
                    }
                }
                onSuccess("Returns matching user profiles (displayName, username/accountId, email, avatar URL).")
                onFailure("no users found", "Empty list — either the query is too specific or the user doesn't exist.")
                onFailure("403", "User-search may require Browse Users permission.")
                example("resolve an assignee name") {
                    param("action", "user_search")
                    param("query", "Jane Smith")
                    outcome("Returns profile(s) matching 'Jane Smith'; LLM picks the right username for the transition `fields` param.")
                }
                verdict {
                    keep("Required precondition for setting user-picker fields in `transition`. Without it the LLM guesses usernames and gets 400 errors.", VerdictSeverity.NORMAL)
                }
            }
        }
        verdict {
            keep("Without `jira` the LLM has to shell out via curl with the user's bearer token in the command line — a security regression that bypasses PasswordSafe. The 17 actions consolidated into one tool (with action-enum dispatch) cost ~1 schema entry instead of ~17, saving ~3-5K tokens per iteration once activated. Conditional registration means zero cost when Jira isn't configured. The MissingFields typed contract on transitions is a meaningfully better experience than parsing raw 400 bodies.", VerdictSeverity.STRONG)
        }
        observation("17 actions in one tool is at the top end of what's reasonable. By rough usage intuition, ~5 actions (`get_ticket`, `search_tickets`, `search_issues`, `transition`, `comment`) carry 80%+ of the value. The remaining 12 are tail-frequency.")
        observation("`get_dev_branches` and `get_linked_prs` are narrower slices of `get_ticket(include_dev_status=true)`. Could be removed and replaced by guidance to use the include flag — saves ~2 action description lines.")
        observation("`get_board_issues` overlaps semantically with `search_tickets`. JQL is strictly more powerful; the board-issue listing is mostly a UI affordance.")
        observation("`get_worklogs` is plausibly the lowest-frequency action. Time-log readback is rarely the LLM's job.")
        observation("`key`, `issue_key`, and `issue_id` are all accepted as aliases for the same parameter on several actions (get_worklogs, get_linked_prs, get_dev_branches, start_work). Defensive but undocumented; LLMs may use any of the three based on prior prompts.")
        mergeOpportunity("Fold `get_dev_branches` and `get_linked_prs` into `get_ticket(include_dev_status=true)` with optional sub-flags (`dev_status_only=branches|prs`). Saves 2 action slots; tradeoff is the include-flag signature growing.")
        mergeOpportunity("Replace `get_board_issues` with guidance to use `search_tickets` + JQL `sprint in openSprints()` or similar.")
        mergeOpportunity("`search_issues` and `search_tickets` could be merged via a `query_kind: text|jql` discriminator. Tradeoff: small token savings; clarity slightly worse.")
        removableParam("`description` is recommended-but-not-required across write actions (transition, comment, start_work). Could either be required (forcing better approval-dialog UX) or removed (relying on action+key fallback).")
        related("bitbucket_pr", Relationship.COMPOSE_WITH, "Tickets often embed PR links; use `jira(get_linked_prs)` then `bitbucket_pr(get_pr_detail)` to bridge the two systems.")
        related("bitbucket_repo", Relationship.COMPOSE_WITH, "After `jira(start_work)` creates a branch, use `bitbucket_repo` to push and `bitbucket_pr(create_pr)` to open a PR.")
        related("sonar", Relationship.COMPLEMENT, "When a quality gate fails on a PR linked to a ticket, the agent reads sonar issues + jira ticket to compose the fix.")
        related("bamboo_builds", Relationship.COMPLEMENT, "Build status feeds into the ticket's Dev Status panel — visible via `get_ticket(include_dev_status=true)`.")
        related("ask_followup_question", Relationship.COMPOSE_WITH, "On `transition` MissingFields, the LLM uses ask_followup_question for each missing field, then retries `transition`.")
        related("read_document", Relationship.COMPOSE_WITH, "After `download_attachment` of a PDF/DOC/etc., the LLM uses read_document on the returned filepath to extract text.")
        downside("Depends on Jira being available and the user's token being valid. Network outages and 401s are common failure modes that the LLM has to handle gracefully (don't retry 401).")
        downside("Each Jira instance has different custom fields. Custom-field IDs (`customfield_10412`) are project-specific and only discoverable via the MissingFields payload — the LLM cannot guess them from the field's display name.")
        downside("Workflows differ per project — what's transition_id=31 for one project may not exist for another. `get_transitions` is mandatory before `transition`.")
        downside("Jira Cloud and Jira DC have subtly different APIs (notably user identification: Cloud uses accountId, DC uses name). The current implementation targets DC; Cloud projects may surface format mismatches in field values.")
        downside("Rate limiting: Jira Cloud throttles aggressively on 429; DC rarely does. The LLM has no rate-limit awareness and may spam retries on 429.")
        downside("`description` parameter is marked recommended for write actions but isn't enforced — the approval dialog shows action+key when it's missing, which is less informative than a per-call description.")
        downside("Transition `comment` parameter and `comment` action's `body` parameter are different things — easy to confuse.")
        narrative("jira")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        return when (action) {
            "get_ticket" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                val includeDevStatus = params["include_dev_status"]?.jsonPrimitive?.content?.lowercase() == "true"
                val includeRemoteLinks = params["include_remote_links"]?.jsonPrimitive?.content?.lowercase() == "true"
                val includeHistory = params["include_history"]?.jsonPrimitive?.content?.lowercase() == "true"
                val includePermissions = params["include_permissions"]?.jsonPrimitive?.content?.lowercase() == "true"
                val anyInclude = includeDevStatus || includeRemoteLinks || includeHistory || includePermissions
                if (!anyInclude) {
                    service.getTicket(key).toAgentToolResult()
                } else {
                    coroutineScope {
                        val ticketDeferred = async { service.getTicket(key) }
                        val devStatusDeferred = if (includeDevStatus) async { service.getFullDevStatus(key) } else null
                        val remoteLinksDeferred = if (includeRemoteLinks) async { service.getRemoteLinks(key) } else null
                        val historyDeferred = if (includeHistory) async { service.getTicketHistory(key) } else null
                        val projectKey = key.substringBefore("-").takeIf { it != key }
                        val permsDeferred = if (includePermissions) async { service.getMyPermissions(projectKey) } else null

                        val ticketResult = ticketDeferred.await()
                        if (ticketResult.isError) return@coroutineScope ticketResult.toAgentToolResult()
                        val ticketAgent = ticketResult.toAgentToolResult()

                        val blocks = mutableListOf(ticketAgent.content)
                        val summaries = mutableListOf(ticketAgent.summary)

                        devStatusDeferred?.await()?.takeIf { !it.isError }?.let { ds ->
                            val data = ds.data!!
                            blocks += formatDevStatusBundle(key, data)
                            summaries += data.summaryLine()
                        }
                        remoteLinksDeferred?.await()?.takeIf { !it.isError }?.let { rl ->
                            blocks += formatRemoteLinks(rl.data!!)
                            summaries += rl.summary
                        }
                        historyDeferred?.await()?.takeIf { !it.isError }?.let { h ->
                            blocks += formatTicketHistory(h.data!!)
                            summaries += h.summary
                        }
                        permsDeferred?.await()?.takeIf { !it.isError }?.let { p ->
                            blocks += formatPermissions(p.data!!)
                            summaries += p.summary
                        }

                        val combined = blocks.joinToString("\n\n")
                        ToolResult(combined, summaries.joinToString(" · "), TokenEstimator.estimate(combined))
                    }
                }
            }

            "get_transitions" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.getTransitions(key).toAgentToolResult()
            }

            "transition" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                val transitionId = params["transition_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("transition_id")
                val comment = params["comment"]?.jsonPrimitive?.content
                ToolValidation.validateJiraKey(key)?.let { return it }

                // Parse optional fields map from JSON object parameter
                val fieldValues = run {
                    val fieldsElement = params["fields"]
                    if (fieldsElement == null || fieldsElement is kotlinx.serialization.json.JsonNull) {
                        emptyMap()
                    } else {
                        try {
                            parseFieldsJson(fieldsElement.jsonObject)
                        } catch (_: Exception) {
                            emptyMap()
                        }
                    }
                }

                // Delegate to TicketTransitionService which owns field validation and MissingFields errors
                val transitionSvc = ServiceLookup.ticketTransition(project)
                if (transitionSvc != null) {
                    val input = TransitionInput(
                        transitionId = transitionId,
                        fieldValues = fieldValues,
                        comment = comment
                    )
                    val result = transitionSvc.executeTransition(key, input)
                    if (result.isError) {
                        // Surface the structured payload (MissingFields, InvalidTransition, etc.)
                        // so the agent's ReAct loop can read it and act on it
                        val payloadInfo = when (val p = result.payload) {
                            is TransitionError.MissingFields -> {
                                // Disambiguate "didn't pass field at all" vs "passed field but value
                                // was unresolvable" — feedback.md §16. Without this hint, the LLM
                                // sees the same "X is required" error in both cases and gets stuck.
                                val fieldsList = p.payload.fields.joinToString("\n") { f ->
                                    val passed = f.id in fieldValues.keys
                                    val statusHint = if (passed) {
                                        " ← VALUE PROVIDED but rejected — likely unresolvable user/value or wrong format"
                                    } else ""
                                    "  - ${f.id} (${f.name}): required=${f.required}, schema=${f.schema::class.simpleName}$statusHint"
                                }
                                val anyPassed = p.payload.fields.any { it.id in fieldValues.keys }
                                val extraHint = if (anyPassed) {
                                    "\nNote: at least one rejected field was passed in the call. The Jira API does not " +
                                        "distinguish 'missing' from 'invalid' in this response — common causes are: " +
                                        "(1) user-picker field given a username Jira can't resolve (try jira(action=user_search) first), " +
                                        "(2) option field given a free-text value instead of {\"id\":\"...\"}, " +
                                        "(3) cascading select missing the 'child' branch."
                                } else ""
                                "\npayload_type: missing_required_fields\nfields:\n$fieldsList\nguidance: ${p.payload.guidance}$extraHint"
                            }
                            is TransitionError.InvalidTransition -> "\npayload_type: invalid_transition\nreason: ${p.reason}"
                            is TransitionError.RequiresInteraction -> "\npayload_type: requires_interaction\ntransition: ${p.meta.name} (id=${p.meta.id})"
                            is TransitionError.Forbidden -> "\npayload_type: forbidden\nreason: ${p.reason}"
                            is TransitionError.Network -> "\npayload_type: network_error\ncause: ${p.cause.message}"
                            else -> ""
                        }
                        val content = result.summary + payloadInfo
                        ToolResult(
                            content = content,
                            summary = result.summary,
                            tokenEstimate = TokenEstimator.estimate(content),
                            isError = true
                        )
                    } else {
                        val outcome = result.data!!
                        val content = "Transitioned $key: ${outcome.fromStatus.name} → ${outcome.toStatus.name}. ${result.summary}"
                        ToolResult(
                            content = content,
                            summary = "Transitioned $key to ${outcome.toStatus.name}",
                            tokenEstimate = TokenEstimator.estimate(content)
                        )
                    }
                } else {
                    // Fallback: TicketTransitionService not registered — use legacy JiraService path
                    val ticketResult = service.getTicket(key)
                    if (ticketResult.isError) {
                        return ToolResult(
                            content = "Cannot transition: ticket $key not found.\n${ticketResult.summary}",
                            summary = "Ticket $key not found",
                            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                    }
                    val currentStatus = ticketResult.data!!.status

                    val transitionsResult = service.getTransitions(key)
                    if (!transitionsResult.isError) {
                        val transitions = transitionsResult.data!!
                        val match = transitions.find { it.id == transitionId }
                        if (match == null) {
                            val availableList = transitions.joinToString("\n") { "  ID ${it.id}: ${it.name} → ${it.toStatus}" }
                            val content = "Cannot transition $key: transition ID '$transitionId' is not available from current status '$currentStatus'.\n\nAvailable transitions:\n$availableList"
                            return ToolResult(
                                content = content,
                                summary = "Invalid transition ID '$transitionId' from status '$currentStatus'",
                                tokenEstimate = TokenEstimator.estimate(content),
                                isError = true
                            )
                        }
                    }

                    val result = service.transition(key, transitionId, comment = comment)
                    if (result.isError) {
                        result.toAgentToolResult()
                    } else {
                        val content = "Transitioned $key from '$currentStatus'. ${result.summary}"
                        ToolResult(
                            content = content,
                            summary = "Transitioned $key",
                            tokenEstimate = TokenEstimator.estimate(content)
                        )
                    }
                }
            }

            "comment" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                val body = params["body"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("body")
                ToolValidation.validateJiraKey(key)?.let { return it }
                ToolValidation.validateNotBlank(body, "body")?.let { return it }

                // Pre-flight: verify ticket exists
                val ticketResult = service.getTicket(key)
                if (ticketResult.isError) {
                    return ToolResult(
                        content = "Cannot add comment: ticket $key not found.\n${ticketResult.summary}",
                        summary = "Ticket $key not found",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                service.addComment(key, body).toAgentToolResult()
            }

            "get_comments" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.getComments(key).toAgentToolResult()
            }

            "log_work" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                val timeSpent = params["time_spent"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("time_spent")
                val comment = params["comment"]?.jsonPrimitive?.content
                ToolValidation.validateJiraKey(key)?.let { return it }
                ToolValidation.validateTimeSpent(timeSpent)?.let { return it }

                // Parse optional `started` datetime — feedback.md §14. Accepts either
                // a full ISO 8601 ("2026-05-10T09:00:00+00:00") or a bare date ("2026-05-10")
                // which we promote to start-of-day in the system zone.
                val startedRaw = params["started"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val started: java.time.OffsetDateTime? = startedRaw?.let { raw ->
                    parseStartedDateTime(raw) ?: return ToolResult(
                        "Error: 'started' must be an ISO 8601 datetime (e.g. '2026-05-10T09:00:00+00:00') " +
                            "or a bare date (e.g. '2026-05-10'). Got: '$raw'",
                        "log_work: invalid 'started' value",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true,
                    )
                }

                // Pre-flight: verify ticket exists
                val ticketResult = service.getTicket(key)
                if (ticketResult.isError) {
                    return ToolResult(
                        content = "Cannot log work: ticket $key not found.\n${ticketResult.summary}",
                        summary = "Ticket $key not found",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                service.logWork(key, timeSpent, comment, started).toAgentToolResult()
            }

            "get_worklogs" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateJiraKey(issueKey)?.let { return it }
                val author = params["author"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val since = params["since"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { parseStartedDateTime(it) }
                val until = params["until"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { parseStartedDateTime(it) }
                val result = service.getWorklogs(issueKey)
                if (result.isError || author == null && since == null && until == null) {
                    return result.toAgentToolResult()
                }
                // Post-filter the worklog list — pre-fix the LLM had to fetch all worklogs and
                // grep client-side, which is slow on tickets with hundreds of entries. Filtering
                // here keeps the rest of the toAgentToolResult formatting intact.
                val filtered = result.data.orEmpty().filter { wl ->
                    val matchesAuthor = author == null || worklogMatchesAuthor(wl, author)
                    val started = worklogStarted(wl)
                    val matchesSince = since == null || (started != null && !started.isBefore(since))
                    val matchesUntil = until == null || (started != null && !started.isAfter(until))
                    matchesAuthor && matchesSince && matchesUntil
                }
                val filteredSummary = "${filtered.size} worklog(s) on $issueKey" +
                    (author?.let { " by $it" } ?: "") +
                    (since?.let { " since ${it.toLocalDate()}" } ?: "") +
                    (until?.let { " until ${it.toLocalDate()}" } ?: "")
                val content = buildString {
                    appendLine(filteredSummary)
                    filtered.forEach { appendLine(it.toString()) }
                }
                ToolResult(content, filteredSummary, TokenEstimator.estimate(content))
            }

            "my_worklogs" -> {
                val sinceStr = params["since"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("since")
                val untilStr = params["until"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val since = parseStartedDateTime(sinceStr)
                    ?: return ToolResult(
                        "Error: 'since' must be ISO 8601 date or datetime. Got: '$sinceStr'",
                        "my_worklogs: invalid 'since'", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true,
                    )
                val until = untilStr?.let { parseStartedDateTime(it) }
                // Find tickets the current user logged time on in the window, then aggregate.
                // JQL: worklogAuthor = currentUser() AND worklogDate >= since [AND worklogDate <= until]
                val jql = buildString {
                    append("worklogAuthor = currentUser() AND worklogDate >= \"${since.toLocalDate()}\"")
                    if (until != null) append(" AND worklogDate <= \"${until.toLocalDate()}\"")
                    append(" ORDER BY updated DESC")
                }
                val ticketsResult = service.searchTickets(jql, 50)
                if (ticketsResult.isError) return ticketsResult.toAgentToolResult()
                val tickets = ticketsResult.data.orEmpty()
                if (tickets.isEmpty()) {
                    return ToolResult(
                        "No worklogs found in the requested window.",
                        "my_worklogs: 0 tickets",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                    )
                }
                // Resolve the current user once so we can post-filter worklogs by author on
                // each ticket (a ticket the user touched may also have entries from teammates).
                val myselfResult = service.getMyselfExpanded()
                val myselfName = myselfResult.data?.name
                // Fetch worklogs in parallel but BOUNDED by a Semaphore — Jira Cloud rate-limits
                // around ~50 req/10s per token, so 50 simultaneous getWorklogs calls would hit
                // 429 and all fail together. Code-review concern.
                val concurrencyLimit = kotlinx.coroutines.sync.Semaphore(8)
                val perTicket = coroutineScope {
                    tickets.map { ticket ->
                        async {
                            concurrencyLimit.withPermit {
                                val key = extractTicketKey(ticket) ?: return@withPermit null
                                key to service.getWorklogs(key).data.orEmpty()
                                    .filter { wl ->
                                        val started = worklogStarted(wl) ?: return@filter false
                                        val inWindow = !started.isBefore(since) &&
                                            (until == null || !started.isAfter(until))
                                        val byMe = myselfName == null || worklogMatchesAuthor(wl, myselfName)
                                        inWindow && byMe
                                    }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val total = perTicket.sumOf { it.second.size }
                val content = buildString {
                    appendLine("$total worklog(s) across ${perTicket.count { it.second.isNotEmpty() }} ticket(s) " +
                        "from ${since.toLocalDate()}${until?.let { " to ${it.toLocalDate()}" } ?: ""}:")
                    if (myselfName == null) {
                        // Without /myself we can't filter to the current user, so any teammates'
                        // worklogs on the same tickets are also included. Flag this to the LLM
                        // so it can decide whether to retry or proceed with mixed-author data.
                        appendLine("Note: could not resolve current user identity — results may include " +
                            "worklogs by other users on these tickets.")
                    }
                    perTicket.filter { it.second.isNotEmpty() }.forEach { (key, logs) ->
                        appendLine()
                        appendLine("$key (${logs.size} log(s)):")
                        logs.forEach { appendLine("  - $it") }
                    }
                }
                ToolResult(content, "my_worklogs: $total log(s) across ${perTicket.size} ticket(s)",
                    TokenEstimator.estimate(content))
            }

            "user_search" -> {
                val query = params["query"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("query")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                val searchSvc = ServiceLookup.jiraSearch(project)
                    ?: return ServiceLookup.notConfigured("Jira (user search)")
                searchSvc.searchUsers(query, maxResults).toAgentToolResult()
            }

            "get_sprints" -> {
                val boardIdStr = params["board_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("board_id")
                val boardId = boardIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'board_id' must be an integer, got '$boardIdStr'", "Error: invalid board_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                service.getAvailableSprints(boardId).toAgentToolResult()
            }

            "get_linked_prs" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateNotBlank(issueKey, "key")?.let { return it }
                service.getLinkedPullRequests(issueKey).toAgentToolResult()
            }

            "get_boards" -> {
                val type = params["type"]?.jsonPrimitive?.content
                val nameFilter = params["name_filter"]?.jsonPrimitive?.content
                service.getBoards(type, nameFilter).toAgentToolResult()
            }

            "get_sprint_issues" -> {
                val sprintIdStr = params["sprint_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("sprint_id")
                val sprintId = sprintIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'sprint_id' must be an integer, got '$sprintIdStr'", "Error: invalid sprint_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                // current_user_only routes through the service's server-side
                // assignee=currentUser() filter (the probed Sprint-tab path) — no
                // client-side string matching. Default false = whole sprint.
                val currentUserOnly = params["current_user_only"]?.jsonPrimitive?.content?.lowercase() == "true"
                service.getSprintIssues(sprintId, currentUserOnly).toAgentToolResult()
            }

            "get_board_issues" -> {
                val boardIdStr = params["board_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("board_id")
                val boardId = boardIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'board_id' must be an integer, got '$boardIdStr'", "Error: invalid board_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                service.getBoardIssues(boardId).toAgentToolResult()
            }

            "search_issues" -> {
                val text = params["text"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("text")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                val currentUserOnly = params["current_user_only"]?.jsonPrimitive?.content?.lowercase() != "false"
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                service.searchIssues(text, maxResults, currentUserOnly).toAgentToolResult()
            }

            "get_dev_branches" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                ToolValidation.validateNotBlank(issueKey, "key")?.let { return it }
                service.getDevStatusBranches(issueKey).toAgentToolResult()
            }

            "start_work" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                val branchName = params["branch_name"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("branch_name")
                val sourceBranch = params["source_branch"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("source_branch")
                ToolValidation.validateJiraKey(issueKey)?.let { return it }
                ToolValidation.validateNotBlank(branchName, "branch_name")?.let { return it }
                ToolValidation.validateNotBlank(sourceBranch, "source_branch")?.let { return it }
                service.startWork(issueKey, branchName, sourceBranch).toAgentToolResult()
            }

            "search_tickets" -> {
                val jql = params["jql"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("jql")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8
                ToolValidation.validateNotBlank(jql, "jql")?.let { return it }
                service.searchTickets(jql, maxResults).toAgentToolResult()
            }

            "download_attachment" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("key")
                val attachmentId = params["attachment_id"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("attachment_id")
                ToolValidation.validateJiraKey(key)?.let { return it }
                val coreResult = service.downloadAttachment(key, attachmentId)
                val base = coreResult.toAgentToolResult()
                if (base.isError) return base
                val attachment = coreResult.data!!
                val policy = resolveAutoLoadPolicy(project)
                val withImages = autoLoadImageIfApplicable(attachment, base, policy)
                // Append a read_document hint for document-class files so the LLM
                // knows it can extract text content without guessing the next step.
                val hint = buildReadDocumentHint(attachment.mimeType, attachment.filename, attachment.filePath)
                if (hint == null) withImages
                else withImages.copy(content = withImages.content + "\n\n" + hint)
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ---- Fields coercion helpers ----

    /**
     * Coerce a single [JsonElement] into a [FieldValue].
     *
     * Follows the Jira field-value conventions documented in the tool description:
     *   - String primitives → [FieldValue.Text]
     *   - Number primitives → [FieldValue.Number]
     *   - Boolean primitives → [FieldValue.Text] (stringified)
     *   - Array of strings → [FieldValue.LabelList]
     *   - Array of objects with "id" → [FieldValue.Options]
     *   - Array of objects with "name" → [FieldValue.UserRefs]
     *   - Object with "id" → [FieldValue.Option]
     *   - Object with "name" → [FieldValue.UserRef]
     *   - Object with "value" (+ optional "child") → [FieldValue.Cascade]
     */
    private fun coerceFieldValueJson(element: kotlinx.serialization.json.JsonElement): FieldValue? = when (element) {
        is JsonPrimitive -> when {
            element.isString -> FieldValue.Text(element.content)
            element.content.toBooleanStrictOrNull() != null -> FieldValue.Text(element.content)
            element.content.toDoubleOrNull() != null -> FieldValue.Number(element.content.toDouble())
            else -> FieldValue.Text(element.content)
        }
        is JsonArray -> {
            val first = element.firstOrNull()
            when {
                first == null -> null
                first is JsonPrimitive && first.isString ->
                    FieldValue.LabelList(element.filterIsInstance<JsonPrimitive>().map { it.content })
                first is JsonObject && first["id"] != null ->
                    FieldValue.Options(element.filterIsInstance<JsonObject>()
                        .mapNotNull { it["id"]?.jsonPrimitive?.content })
                first is JsonObject && first["name"] != null ->
                    FieldValue.UserRefs(element.filterIsInstance<JsonObject>()
                        .mapNotNull { it["name"]?.jsonPrimitive?.content })
                else -> null
            }
        }
        is JsonObject -> when {
            element["id"] is JsonPrimitive -> FieldValue.Option(element["id"]!!.jsonPrimitive.content)
            element["name"] is JsonPrimitive -> FieldValue.UserRef(element["name"]!!.jsonPrimitive.content)
            element["value"] is JsonPrimitive -> {
                val parentVal = element["value"]!!.jsonPrimitive.content
                val childVal = (element["child"] as? JsonObject)?.get("value")?.jsonPrimitive?.content
                FieldValue.Cascade(parentVal, childVal)
            }
            else -> null
        }
        else -> null
    }

    /**
     * Parse a [JsonObject] representing the `fields` parameter into a typed map.
     * Keys are Jira field IDs; values are coerced via [coerceFieldValueJson].
     * Entries whose values cannot be coerced are silently skipped.
     */
    internal fun parseFieldsJson(obj: JsonObject): Map<String, FieldValue> {
        val out = mutableMapOf<String, FieldValue>()
        for ((rawKey, rawVal) in obj.entries) {
            val fv = coerceFieldValueJson(rawVal) ?: continue
            out[rawKey] = fv
        }
        return out
    }

    /**
     * Package-private test entry point for download_attachment — bypasses IntelliJ service lookup.
     * Tests can call this directly with a mocked [JiraService] to verify hint injection
     * without requiring the IntelliJ platform.
     *
     * Auto-load policy defaults to disabled so existing hint-only tests stay green;
     * the [policy] parameter overload exercises the Phase 4 auto-load path.
     */
    internal suspend fun executeDownloadAttachmentForTest(
        key: String,
        attachmentId: String,
        service: com.workflow.orchestrator.core.services.JiraService,
        policy: AutoLoadPolicy = AutoLoadPolicy.DISABLED,
    ): ToolResult {
        val coreResult = service.downloadAttachment(key, attachmentId)
        val base = coreResult.toAgentToolResult()
        if (base.isError) return base
        val attachment = coreResult.data!!
        val withImages = autoLoadImageIfApplicable(attachment, base, policy)
        val hint = buildReadDocumentHint(attachment.mimeType, attachment.filename, attachment.filePath)
        return if (hint == null) withImages else withImages.copy(content = withImages.content + "\n\n" + hint)
    }

    /**
     * Auto-load policy for tool-produced image attachments. Constructed from
     * [com.workflow.orchestrator.core.settings.PluginSettings] in production
     * via [resolveAutoLoadPolicy]; tests pass it directly so they don't have
     * to mock IntelliJ's project-scoped service lookup.
     */
    internal data class AutoLoadPolicy(
        val enabled: Boolean,
        val mimeWhitelist: Set<String>,
    ) {
        companion object {
            /** Policy used when auto-load must not engage (test default + IntelliJ-services-unavailable fallback). */
            val DISABLED = AutoLoadPolicy(enabled = false, mimeWhitelist = emptySet())
        }
    }

    /**
     * Reads the per-project auto-load policy from [PluginSettings]. Wrapped in a
     * try/catch because tools may run outside the IntelliJ runtime in some test
     * paths; in that case auto-load is silently disabled (best-effort).
     */
    private fun resolveAutoLoadPolicy(project: Project): AutoLoadPolicy {
        return try {
            val state = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
            AutoLoadPolicy(
                enabled = state.enableToolImageAutoload,
                mimeWhitelist = state.toolImageAutoloadMimeWhitelist.toSet()
            )
        } catch (_: Exception) {
            AutoLoadPolicy.DISABLED
        }
    }

    /**
     * If [data] is an image whose MIME passes [policy], read the bytes from
     * [com.workflow.orchestrator.core.model.jira.AttachmentContentData.filePath]
     * and store them in the active session's [com.workflow.orchestrator.agent.session.AttachmentStore].
     * Returns [base] augmented with an `imageRefs` entry so the AgentLoop emits
     * `ContentBlock.ImageRef` blocks alongside the text result.
     *
     * Best-effort: any failure (settings off, MIME not whitelisted, file
     * unreadable, no session store on coroutine context) falls back to [base]
     * unchanged so the LLM still sees the text-only download confirmation.
     */
    private suspend fun autoLoadImageIfApplicable(
        data: com.workflow.orchestrator.core.model.jira.AttachmentContentData,
        base: ToolResult,
        policy: AutoLoadPolicy,
    ): ToolResult {
        if (!policy.enabled) return base
        val mime = data.mimeType.orEmpty()
        if (!mime.startsWith("image/")) return base
        if (mime !in policy.mimeWhitelist) return base
        if (data.filePath.isEmpty()) return base

        val bytes = try {
            java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(data.filePath))
        } catch (_: Exception) {
            return base
        }
        if (bytes.isEmpty()) return base

        val store = com.workflow.orchestrator.agent.tool.SessionAttachmentAccess.current()
            ?: return base
        val ref = try {
            store.store(bytes, mime, data.filename)
        } catch (_: Exception) {
            return base
        }
        return base.copy(
            imageRefs = base.imageRefs + com.workflow.orchestrator.core.services.ToolResult.ImageRefData(
                sha256 = ref.sha256,
                mime = ref.mime,
                size = ref.size,
                originalFilename = ref.originalFilename,
            )
        )
    }

    /**
     * Package-private test entry point — bypasses IntelliJ service lookup.
     * Tests can call this directly after constructing the tool with an injected service.
     */
    internal suspend fun executeTransitionForTest(
        key: String,
        transitionId: String,
        fieldsJson: JsonObject?,
        comment: String?,
        transitionSvc: com.workflow.orchestrator.core.services.jira.TicketTransitionService
    ): com.workflow.orchestrator.core.services.ToolResult<*> {
        val fieldValues = fieldsJson?.let {
            try { parseFieldsJson(it) } catch (_: Exception) { emptyMap() }
        } ?: emptyMap()

        val input = TransitionInput(
            transitionId = transitionId,
            fieldValues = fieldValues,
            comment = comment
        )
        return transitionSvc.executeTransition(key, input)
    }

    /**
     * Returns a `read_document` hint paragraph when [mimeType] or [filename] extension
     * indicates a document format that the `read_document` tool can handle.
     *
     * Plain text, CSV, and HTML are excluded — those work with `read_file` directly.
     * Images, archives, and unknown types return null.
     *
     * @param mimeType MIME type from the attachment metadata (may be null)
     * @param filename Original attachment filename (used for extension fallback)
     * @param filePath Absolute path of the downloaded file on disk
     */
    internal fun buildReadDocumentHint(mimeType: String?, filename: String, filePath: String): String? {
        val documentClass = resolveDocumentClass(mimeType, filename) ?: return null
        return "Hint: This file is a $documentClass. You can extract its text content by calling " +
            "`read_document` with path=\"$filePath\"."
    }

    private fun resolveDocumentClass(mimeType: String?, filename: String): String? {
        // Prefer MIME type; fall back to extension when MIME is null or generic.
        val mime = mimeType?.lowercase()?.trim()
        val ext = filename.substringAfterLast('.', "").lowercase()

        return when {
            mime == "application/pdf" || ext == "pdf" -> "PDF"
            mime == "application/msword" || ext == "doc" -> "document"
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || ext == "docx" -> "document"
            mime == "application/rtf" || ext == "rtf" -> "document"
            mime == "application/vnd.oasis.opendocument.text" || ext == "odt" -> "document"
            mime == "application/epub+zip" || ext == "epub" -> "document"
            mime == "application/vnd.ms-excel" || ext == "xls" -> "spreadsheet"
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || ext == "xlsx" -> "spreadsheet"
            mime == "application/vnd.ms-powerpoint" || ext == "ppt" -> "presentation"
            mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" || ext == "pptx" -> "presentation"
            else -> null
        }
    }

    private fun formatPermissions(perms: com.workflow.orchestrator.core.model.jira.MyPermissionsData): String {
        if (perms.permissions.isEmpty()) return "Permissions: (none)"
        val lines = perms.permissions.values
            .filter { !it.deprecated }
            .sortedBy { it.key }
            .map { flag ->
                val state = if (flag.havePermission) "granted" else "denied"
                "  • ${flag.key} (${flag.name}): $state"
            }
        return "Permissions:\n" + lines.joinToString("\n")
    }

    private fun formatRemoteLinks(links: List<com.workflow.orchestrator.core.model.jira.RemoteLinkData>): String {
        if (links.isEmpty()) return "Remote Links: (none)"
        val cap = 20
        val lines = links.take(cap).map { "• [${it.applicationName ?: it.applicationType ?: "link"}] ${it.title ?: "(no title)"} → ${it.url}" }
        val tail = if (links.size > cap) "\n  …(${links.size - cap} more)" else ""
        return "Remote Links (showing ${minOf(cap, links.size)} of ${links.size}):\n" + lines.joinToString("\n") + tail
    }

    private fun formatTicketHistory(history: List<com.workflow.orchestrator.core.model.jira.TicketHistoryEntry>): String {
        if (history.isEmpty()) return "History: (none)"
        val lines = history.take(20).map { entry ->
            val change = "${entry.field}: ${entry.oldValue ?: "(none)"} → ${entry.newValue ?: "(none)"}"
            "• ${entry.createdAt} · ${entry.actorDisplayName} · $change"
        }
        val truncated = if (history.size > 20) "\n  …(${history.size - 20} more entries)" else ""
        return "History (last ${minOf(20, history.size)} of ${history.size}):\n" + lines.joinToString("\n") + truncated
    }

    /**
     * Package-private test entry point for get_ticket N-way fan-out — bypasses IntelliJ service lookup.
     * Tests pass a mocked [JiraService] directly so IntelliJ platform is not required.
     */
    internal suspend fun executeGetTicketForTest(
        key: String,
        includeDevStatus: Boolean,
        includeRemoteLinks: Boolean,
        includeHistory: Boolean,
        service: com.workflow.orchestrator.core.services.JiraService,
        includePermissions: Boolean = false,
    ): ToolResult {
        val anyInclude = includeDevStatus || includeRemoteLinks || includeHistory || includePermissions
        if (!anyInclude) {
            return service.getTicket(key).toAgentToolResult()
        }
        return kotlinx.coroutines.coroutineScope {
            val ticketDeferred = async { service.getTicket(key) }
            val devStatusDeferred = if (includeDevStatus) async { service.getFullDevStatus(key) } else null
            val remoteLinksDeferred = if (includeRemoteLinks) async { service.getRemoteLinks(key) } else null
            val historyDeferred = if (includeHistory) async { service.getTicketHistory(key) } else null
            val projectKey = key.substringBefore("-").takeIf { it != key }
            val permsDeferred = if (includePermissions) async { service.getMyPermissions(projectKey) } else null

            val ticketResult = ticketDeferred.await()
            if (ticketResult.isError) return@coroutineScope ticketResult.toAgentToolResult()
            val ticketAgent = ticketResult.toAgentToolResult()

            val blocks = mutableListOf(ticketAgent.content)
            val summaries = mutableListOf(ticketAgent.summary)

            devStatusDeferred?.await()?.takeIf { !it.isError }?.let { ds ->
                val data = ds.data!!
                blocks += formatDevStatusBundle(key, data)
                summaries += data.summaryLine()
            }
            remoteLinksDeferred?.await()?.takeIf { !it.isError }?.let { rl ->
                blocks += formatRemoteLinks(rl.data!!)
                summaries += rl.summary
            }
            historyDeferred?.await()?.takeIf { !it.isError }?.let { h ->
                blocks += formatTicketHistory(h.data!!)
                summaries += h.summary
            }
            permsDeferred?.await()?.takeIf { !it.isError }?.let { p ->
                blocks += formatPermissions(p.data!!)
                summaries += p.summary
            }

            val combined = blocks.joinToString("\n\n")
            ToolResult(combined, summaries.joinToString(" · "), TokenEstimator.estimate(combined))
        }
    }

    /**
     * Parses either a bare ISO date (YYYY-MM-DD) or a full ISO 8601 datetime into an
     * OffsetDateTime. Bare dates are promoted to start-of-day at the system zone offset,
     * which matches Jira's worklog convention for date-only `worklogDate` JQL filters.
     */
    internal fun parseStartedDateTime(raw: String): java.time.OffsetDateTime? = try {
        java.time.OffsetDateTime.parse(raw)
    } catch (_: Exception) {
        try {
            val date = java.time.LocalDate.parse(raw)
            date.atStartOfDay().atOffset(java.time.OffsetDateTime.now().offset)
        } catch (_: Exception) {
            null
        }
    }

    internal fun worklogStarted(wl: com.workflow.orchestrator.core.model.jira.WorklogData): java.time.OffsetDateTime? =
        parseStartedDateTime(wl.started)

    /** Loose author match — Jira returns displayName / accountId / username inconsistently across versions. */
    internal fun worklogMatchesAuthor(
        wl: com.workflow.orchestrator.core.model.jira.WorklogData,
        author: String,
    ): Boolean = wl.author.equals(author, ignoreCase = true) ||
        wl.author.contains(author, ignoreCase = true)

    /** Extract the ticket key from a JiraTicketData — defensive against changing model shape. */
    internal fun extractTicketKey(
        ticket: com.workflow.orchestrator.core.model.jira.JiraTicketData,
    ): String? = ticket.key.takeIf { it.isNotBlank() }

    private fun formatDevStatusBundle(issueKey: String, bundle: com.workflow.orchestrator.core.model.jira.DevStatusBundle): String = buildString {
        appendLine("Dev Status for $issueKey: ${bundle.summaryLine()}")
        if (bundle.fetchErrors > 0) appendLine("Note: ${bundle.fetchErrors} of 6 feeds errored — result may be incomplete.")
        if (bundle.branches.isNotEmpty()) {
            appendLine("\nBranches (${bundle.branches.size}):")
            bundle.branches.forEach { appendLine("  - ${it.name}${if (it.url.isNotBlank()) " (${it.url})" else ""}") }
        }
        if (bundle.pullRequests.isNotEmpty()) {
            appendLine("\nPull Requests (${bundle.pullRequests.size}):")
            bundle.pullRequests.forEach { appendLine("  - [${it.status}] ${it.name}${if (it.url.isNotBlank()) " (${it.url})" else ""}") }
        }
        if (bundle.commits.isNotEmpty()) {
            appendLine("\nCommits (${bundle.commits.size}):")
            bundle.commits.take(10).forEach { appendLine("  - ${it.displayId}: ${it.message.take(72)}${if (it.merge) " [merge]" else ""}") }
            if (bundle.commits.size > 10) appendLine("  ... and ${bundle.commits.size - 10} more")
        }
        if (bundle.builds.isNotEmpty()) {
            appendLine("\nBuilds (${bundle.builds.size}):")
            bundle.builds.forEach { appendLine("  - [${it.state}] ${it.name}${if (!it.description.isNullOrBlank()) ": ${it.description}" else ""}${if (it.url.isNotBlank()) " (${it.url})" else ""}") }
        }
        if (bundle.deployments.isNotEmpty()) {
            appendLine("\nDeployments (${bundle.deployments.size}):")
            bundle.deployments.forEach { appendLine("  - [${it.state}] ${it.displayName}${if (!it.environmentName.isNullOrBlank()) " → ${it.environmentName}" else ""}${if (it.url.isNotBlank()) " (${it.url})" else ""}") }
        }
        if (bundle.reviews.isNotEmpty()) {
            appendLine("\nReviews (${bundle.reviews.size}):")
            bundle.reviews.forEach { appendLine("  - [${it.state}] ${it.name}${if (it.url.isNotBlank()) " (${it.url})" else ""}") }
        }
    }.trim()
}
