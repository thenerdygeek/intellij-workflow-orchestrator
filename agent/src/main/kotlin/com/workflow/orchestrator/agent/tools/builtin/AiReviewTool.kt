package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.prreview.AnchorSide
import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStoreImpl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Local PR-review findings store for the code-reviewer persona.
 *
 * Writes only to local disk — no Bitbucket API calls. Findings are staged locally and
 * pushed to Bitbucket by the user from the AI Review sub-tab.
 *
 * 3 actions: add_finding, list_findings, clear_findings
 */
class AiReviewTool(
    private val project: Project,
    private val storeProvider: () -> PrReviewFindingsStore = { project.service<PrReviewFindingsStoreImpl>() },
) : AgentTool {

    override val name: String = "ai_review"

    override val description: String = """
Local PR-review findings store for the code-reviewer persona during a PR review session.
Actions: add_finding, list_findings, clear_findings.
Writes only to local disk — no Bitbucket API calls.
Called by the code-reviewer persona to stage findings BEFORE pushing to Bitbucket (push is the user's action from the AI Review sub-tab).

Actions:
- add_finding(pr_id, session_id, severity, message, [file, line_start, line_end, anchor_side, suggestion]) → Add a review finding
- list_findings(pr_id, [session_id, include_archived]) → List staged findings
- clear_findings(pr_id, session_id) → Remove all findings for a session
""".trimIndent()

    override val parameters: FunctionParameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("add_finding", "list_findings", "clear_findings")
            ),
            "pr_id" to ParameterProperty(
                type = "string",
                description = "Pull request ID — required for all actions"
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Agent session ID — required for add_finding and clear_findings; optional filter for list_findings"
            ),
            "severity" to ParameterProperty(
                type = "string",
                description = "Finding severity: NORMAL or BLOCKER — required for add_finding",
                enumValues = listOf("NORMAL", "BLOCKER")
            ),
            "message" to ParameterProperty(
                type = "string",
                description = "Review comment text — required for add_finding"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path for inline finding — optional for add_finding"
            ),
            "line_start" to ParameterProperty(
                type = "string",
                description = "Starting line number (integer) — optional for add_finding"
            ),
            "line_end" to ParameterProperty(
                type = "string",
                description = "Ending line number (integer) — optional for add_finding"
            ),
            "anchor_side" to ParameterProperty(
                type = "string",
                description = "Diff side: ADDED, REMOVED, or CONTEXT — optional for add_finding",
                enumValues = listOf("ADDED", "REMOVED", "CONTEXT")
            ),
            "suggestion" to ParameterProperty(
                type = "string",
                description = "Suggested replacement code — optional for add_finding. " +
                    "Rendered as a Bitbucket suggestion block on push."
            ),
            "include_archived" to ParameterProperty(
                type = "string",
                description = "Include archived findings: true/false — optional for list_findings (default false)"
            ),
        ),
        required = listOf("action")
    )

    override val allowedWorkers: Set<WorkerType> = setOf(
        WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR,
        WorkerType.ANALYZER,
    )

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    override fun documentation(): ToolDocumentation = toolDoc("ai_review") {
        summary {
            technical(
                "Multi-action local staging store for PR review findings produced by the code-reviewer " +
                    "persona. Three actions: add_finding (writes a PrReviewFinding to PrReviewFindingsStoreImpl " +
                    "on local disk), list_findings (reads staged findings filtered by pr_id / session_id / " +
                    "include_archived), clear_findings (removes all findings for a session). No Bitbucket API " +
                    "calls — findings live in ~/.workflow-orchestrator/{proj}/agent/pr-review-findings/ until " +
                    "the user pushes them via the AI Review sub-tab of the PR tab. Hook-exempt: bypasses " +
                    "PreToolUse/PostToolUse so the LLM can stage findings without approval gates. Allowed " +
                    "workers: REVIEWER, ORCHESTRATOR, ANALYZER."
            )
            plain(
                "Like a notepad the AI code-reviewer fills in while reading a PR — it jots down every " +
                    "issue it finds (with file, line, severity, and an optional suggested fix) without " +
                    "immediately posting anything to Bitbucket. When the reviewer is done, the user looks at " +
                    "the AI Review tab, picks which findings to keep, and clicks 'Push to Bitbucket' to post " +
                    "them as real PR comments. The tool has three pages: 'add a finding', 'show me all " +
                    "findings so far', and 'clear the notepad for this session'."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without ai_review, the code-reviewer persona has no persistent staging store — it would " +
                "have to include every finding inline in its chat response. Findings would be lost if the " +
                "session was compacted, could not be selectively posted to Bitbucket, and the user would " +
                "have no one-click push path. The AI Review sub-tab in the PR panel exists specifically to " +
                "consume staged findings from this store; without the tool the tab would always be empty."
        )
        actions {
            action("add_finding") {
                description {
                    technical(
                        "Creates a PrReviewFinding (UUID id, pr_id, session_id, file?, lineStart?, lineEnd?, " +
                            "anchorSide?, severity, message, createdAt) and persists it via " +
                            "PrReviewFindingsStoreImpl. When a 'suggestion' param is provided it is appended " +
                            "to the message as a Bitbucket suggestion block (```suggestion\\n...\\n```) so the " +
                            "user can apply it one-click in the PR UI. Writes to local disk only."
                    )
                    plain(
                        "Add one finding to the notepad. At minimum you give it a PR ID, a session ID, a " +
                            "severity (NORMAL or BLOCKER), and the comment text. Optionally pin it to a specific " +
                            "file and line range so it appears as an inline comment in the diff. You can also " +
                            "supply a suggested replacement snippet — Bitbucket renders it as a one-click 'Apply " +
                            "suggestion' button."
                    )
                }
                whenLLMUses(
                    "After reading a file or diff chunk and identifying a code issue, smell, or " +
                        "improvement opportunity. Called once per finding, not once per PR."
                )
                params {
                    required("action", "string") {
                        llmSeesIt("Operation to perform")
                        humanReadable("Discriminator that routes to this action.")
                        whenPresent("Must be the literal string \"add_finding\".")
                        enumValue("add_finding")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID — required for all actions")
                        humanReadable(
                            "The numeric or slug ID of the pull request being reviewed. " +
                                "Acts as the partition key for the findings store."
                        )
                        whenPresent("Finding is stored under this PR ID.")
                        example("42")
                        example("PR-1337")
                    }
                    required("session_id", "string") {
                        llmSeesIt(
                            "Agent session ID — required for add_finding and clear_findings; " +
                                "optional filter for list_findings"
                        )
                        humanReadable(
                            "The current agent session's UUID. Groups findings by review session so " +
                                "older sessions can be archived or cleared independently."
                        )
                        whenPresent("Finding is tagged with this session, enabling per-session filtering and clearing.")
                        example("a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    }
                    required("severity", "string") {
                        llmSeesIt("Finding severity: NORMAL or BLOCKER — required for add_finding")
                        humanReadable(
                            "BLOCKER = must-fix before merge (security holes, crashes, data corruption). " +
                                "NORMAL = should-fix or nice-to-have (style, minor logic, documentation)."
                        )
                        whenPresent("Finding is stored with this severity, shown as a badge in the AI Review tab.")
                        enumValue("NORMAL", "BLOCKER")
                        example("BLOCKER")
                        example("NORMAL")
                    }
                    required("message", "string") {
                        llmSeesIt("Review comment text — required for add_finding")
                        humanReadable(
                            "The human-readable review comment. Should explain WHY it is a problem and " +
                                "WHAT to do about it, not just describe what the code does."
                        )
                        whenPresent(
                            "Stored as the finding's message. If 'suggestion' is also provided, " +
                                "the suggestion block is appended automatically."
                        )
                        constraint("non-blank; tool returns error if blank")
                    }
                    optional("file", "string") {
                        llmSeesIt("File path for inline finding — optional for add_finding")
                        humanReadable(
                            "Relative file path within the repo. When present the finding is linked to " +
                                "a specific file so it can post as an inline diff comment on Bitbucket."
                        )
                        whenPresent("Finding is anchored to this file path.")
                        whenAbsent("Finding is a general PR-level comment (not anchored to any file).")
                        example("src/main/kotlin/com/example/service/UserService.kt")
                    }
                    optional("line_start", "string") {
                        llmSeesIt("Starting line number (integer) — optional for add_finding")
                        humanReadable(
                            "First line of the region being commented on. Parsed as an integer " +
                                "(tool accepts both string '42' and number 42 from the LLM)."
                        )
                        whenPresent("Finding is anchored to a line range starting here.")
                        whenAbsent("Finding covers the whole file (if file is set) or the whole PR.")
                        constraint("must be a parseable integer ≥ 1")
                        example("42")
                    }
                    optional("line_end", "string") {
                        llmSeesIt("Ending line number (integer) — optional for add_finding")
                        humanReadable(
                            "Last line of the region. Typically line_start == line_end for single-line " +
                                "comments; a range is used for multi-line blocks."
                        )
                        whenPresent("Finding spans from line_start to line_end.")
                        whenAbsent("Finding is a single-line comment at line_start (if set).")
                        constraint("must be a parseable integer ≥ line_start")
                        example("48")
                    }
                    optional("anchor_side", "string") {
                        llmSeesIt("Diff side: ADDED, REMOVED, or CONTEXT — optional for add_finding")
                        humanReadable(
                            "Which side of the diff the comment lands on when pushed to Bitbucket. " +
                                "ADDED = new code (right pane), REMOVED = deleted code (left pane), " +
                                "CONTEXT = unchanged context lines shown in both panes."
                        )
                        whenPresent(
                            "Finding is anchored to the specified diff side. Passed through to " +
                                "Bitbucket's inline comment API as the anchor_side field."
                        )
                        whenAbsent("Bitbucket defaults apply; typically ADDED for new-code reviews.")
                        enumValue("ADDED", "REMOVED", "CONTEXT")
                        example("ADDED")
                    }
                    optional("suggestion", "string") {
                        llmSeesIt(
                            "Suggested replacement code — optional for add_finding. " +
                                "Rendered as a Bitbucket suggestion block on push."
                        )
                        humanReadable(
                            "The replacement code snippet the reviewer proposes. The tool wraps it in a " +
                                "Bitbucket suggestion fence (```suggestion\\n...\\n```) and appends it to the " +
                                "message. The user can then apply it with one click in the Bitbucket PR UI."
                        )
                        whenPresent("Appended to message as a suggestion block; enables one-click apply in Bitbucket.")
                        whenAbsent("Finding is posted as a plain comment with no inline suggestion.")
                        example("return userRepository.findById(id).orElseThrow { NotFoundException(id) }")
                    }
                }
                precondition("pr_id must identify a PR that the user has open in the PR tab.")
                precondition("session_id must be the current agent session ID (available from the agent's own context).")
                onSuccess(
                    "Returns the JSON representation of the stored PrReviewFinding (id, prId, sessionId, " +
                        "file, lineStart, lineEnd, anchorSide, severity, message, createdAt). The finding ID " +
                        "can be used to reference the finding in follow-up tool calls or audit notes."
                )
                onFailure(
                    "pr_id is blank",
                    "Returns error 'pr_id is required for add_finding'. LLM should supply the PR identifier."
                )
                onFailure(
                    "session_id is blank",
                    "Returns error 'session_id is required for add_finding'. LLM should use its own session ID."
                )
                onFailure(
                    "severity is not NORMAL or BLOCKER",
                    "Returns error 'invalid severity'. LLM must use exactly 'NORMAL' or 'BLOCKER' (case-insensitive)."
                )
                onFailure(
                    "message is blank",
                    "Returns error 'message is required for add_finding'. LLM must provide a non-empty comment."
                )
                onFailure(
                    "anchor_side is not ADDED, REMOVED, or CONTEXT",
                    "Returns error 'invalid anchor_side'. LLM must use one of the three enumerated values."
                )
                onFailure(
                    "Disk write failure (disk full, permissions)",
                    "PrReviewFindingsStoreImpl returns an error ToolResult; tool surfaces it as " +
                        "'failed to add finding — <cause>'. LLM can retry; user may need to check disk space."
                )
                example("Add a BLOCKER for a SQL injection risk") {
                    param("action", "add_finding")
                    param("pr_id", "42")
                    param("session_id", "a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    param("severity", "BLOCKER")
                    param("file", "src/main/kotlin/com/example/UserRepository.kt")
                    param("line_start", "87")
                    param("line_end", "87")
                    param("anchor_side", "ADDED")
                    param("message", "String interpolation directly into SQL query — SQL injection risk. Use parameterised query instead.")
                    param("suggestion", "val user = jdbcTemplate.queryForObject(\"SELECT * FROM users WHERE id = ?\", arrayOf(id), UserRowMapper())")
                    outcome(
                        "Stores a BLOCKER finding anchored to line 87 with a one-click suggestion block. " +
                            "Returns the finding JSON including the generated UUID id."
                    )
                    notes("Suggestion is automatically wrapped in a Bitbucket ```suggestion fence.")
                }
                example("Add a NORMAL inline style comment") {
                    param("action", "add_finding")
                    param("pr_id", "42")
                    param("session_id", "a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    param("severity", "NORMAL")
                    param("file", "src/main/kotlin/com/example/UserService.kt")
                    param("line_start", "34")
                    param("message", "Function is 120 lines; consider extracting the validation block into a private helper.")
                    outcome("Stores a NORMAL finding anchored to line 34 with no suggestion.")
                }
                example("Add a general (non-inline) PR-level finding") {
                    param("action", "add_finding")
                    param("pr_id", "42")
                    param("session_id", "a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    param("severity", "NORMAL")
                    param("message", "No integration tests added for the new payment gateway path. Unit tests alone are insufficient here.")
                    outcome("Stores a NORMAL finding with no file anchor — will be posted as a top-level PR comment.")
                }
            }
            action("list_findings") {
                description {
                    technical(
                        "Reads staged findings from PrReviewFindingsStoreImpl for the given pr_id, " +
                            "optionally filtered to a specific session_id. When include_archived=true also " +
                            "returns findings from archived (pushed) sessions. Returns a JSON array of " +
                            "PrReviewFinding objects."
                    )
                    plain(
                        "Show me what's on the notepad for this PR. You get a list of every finding the " +
                            "AI has staged — or just the ones from this session if you filter by session_id."
                    )
                }
                whenLLMUses(
                    "At the start of a review session (to check for existing findings from a prior " +
                        "session), after a batch of add_finding calls (to confirm what was staged), or when " +
                        "asked to summarise the review findings for the user."
                )
                params {
                    required("action", "string") {
                        llmSeesIt("Operation to perform")
                        humanReadable("Discriminator that routes to this action.")
                        whenPresent("Must be the literal string \"list_findings\".")
                        enumValue("list_findings")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID — required for all actions")
                        humanReadable("PR whose findings to list.")
                        whenPresent("Only findings for this pr_id are returned.")
                        example("42")
                    }
                    optional("session_id", "string") {
                        llmSeesIt(
                            "Agent session ID — required for add_finding and clear_findings; " +
                                "optional filter for list_findings"
                        )
                        humanReadable(
                            "When provided, returns only findings tagged with this session. When omitted, " +
                                "returns findings for all sessions for the PR."
                        )
                        whenPresent("Filters findings to this session only.")
                        whenAbsent("Returns findings from all sessions for the pr_id.")
                        example("a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    }
                    optional("include_archived", "string") {
                        llmSeesIt(
                            "Include archived findings: true/false — optional for list_findings (default false)"
                        )
                        humanReadable(
                            "Whether to include findings from sessions that have already been pushed to " +
                                "Bitbucket and archived. Useful for auditing what was previously posted."
                        )
                        whenPresent("Includes archived findings when value is 'true' or true (boolean).")
                        whenAbsent("Only active (un-pushed) findings are returned.")
                        enumValue("true", "false")
                        example("false")
                    }
                }
                onSuccess(
                    "Returns a JSON array of PrReviewFinding objects. An empty array means no findings " +
                        "have been staged yet. Each finding includes id, prId, sessionId, file (nullable), " +
                        "lineStart, lineEnd, anchorSide, severity, message, createdAt."
                )
                onFailure(
                    "pr_id is blank",
                    "Returns error 'pr_id is required for list_findings'. LLM should supply the PR identifier."
                )
                onFailure(
                    "Disk read failure",
                    "Store returns an error ToolResult; tool surfaces it as 'failed to list findings — <cause>'."
                )
                rejectsParam("severity", "Severity is not a filter param for list_findings — it is only used in add_finding.")
                rejectsParam("message", "Message is only relevant for add_finding.")
                example("List all findings for a PR") {
                    param("action", "list_findings")
                    param("pr_id", "42")
                    outcome("Returns all staged findings across every session for PR 42.")
                }
                example("List only findings from the current session") {
                    param("action", "list_findings")
                    param("pr_id", "42")
                    param("session_id", "a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    outcome("Returns only findings tagged with the current session ID.")
                }
            }
            action("clear_findings") {
                description {
                    technical(
                        "Removes all PrReviewFinding records for the given pr_id + session_id pair from " +
                            "PrReviewFindingsStoreImpl. Useful when re-running a review from scratch within " +
                            "the same session, or after the user has pushed findings and the session should " +
                            "be cleaned up. Non-recoverable — there is no undo."
                    )
                    plain(
                        "Wipe the notepad for this session. All findings staged during this review session " +
                            "are deleted from local disk. This does NOT affect what is already posted to Bitbucket."
                    )
                }
                whenLLMUses(
                    "When asked to restart a review from scratch, or as cleanup after the user has " +
                        "pushed findings to Bitbucket and wants a clean slate for the next pass."
                )
                params {
                    required("action", "string") {
                        llmSeesIt("Operation to perform")
                        humanReadable("Discriminator that routes to this action.")
                        whenPresent("Must be the literal string \"clear_findings\".")
                        enumValue("clear_findings")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID — required for all actions")
                        humanReadable("PR whose findings to clear.")
                        whenPresent("Only findings for this pr_id are cleared.")
                        example("42")
                    }
                    required("session_id", "string") {
                        llmSeesIt(
                            "Agent session ID — required for add_finding and clear_findings; " +
                                "optional filter for list_findings"
                        )
                        humanReadable(
                            "The session whose findings to delete. Only findings tagged with this session_id " +
                                "are removed — findings from other sessions are untouched."
                        )
                        whenPresent("All findings for (pr_id, session_id) are deleted.")
                        example("a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    }
                }
                onSuccess(
                    "Returns a confirmation string: 'Findings cleared for PR {prId} / session {sessionId}.' " +
                        "The store is now empty for this PR+session pair."
                )
                onFailure(
                    "pr_id is blank",
                    "Returns error 'pr_id is required for clear_findings'. LLM should supply the PR identifier."
                )
                onFailure(
                    "session_id is blank",
                    "Returns error 'session_id is required for clear_findings'. LLM should supply the session ID."
                )
                onFailure(
                    "Disk write failure",
                    "Store returns an error ToolResult; tool surfaces it as 'failed to clear findings — <cause>'."
                )
                rejectsParam("severity", "Severity is not used by clear_findings.")
                rejectsParam("message", "Message is not used by clear_findings.")
                rejectsParam("file", "File path is not used by clear_findings; clear always applies to the whole session.")
                example("Clear the current session's findings before re-reviewing") {
                    param("action", "clear_findings")
                    param("pr_id", "42")
                    param("session_id", "a3f1c2d8-0041-4e9b-8b32-1234abcd5678")
                    outcome("All findings for session a3f1c2d8... on PR 42 are removed from local disk.")
                    notes("Safe to call even if the store is already empty — returns success either way.")
                }
            }
        }
        verdict {
            keep(
                "Essential to the PR Review Workflow — this tool is the only write path from the " +
                    "code-reviewer persona to the local staging store that backs the AI Review sub-tab. " +
                    "Removing it would make the AI Review tab permanently empty and break the one-click " +
                    "push-to-Bitbucket flow that separates agent-reviewed findings from inline chat noise. " +
                    "Hook-exemption is intentional: staging findings is internal bookkeeping, not a " +
                    "user-observable side effect.",
                VerdictSeverity.STRONG
            )
        }
        llmMistake(
            "Omits 'session_id' for add_finding or clear_findings. Both actions require it. The LLM " +
                "sometimes treats session_id as optional because it is optional for list_findings, but " +
                "for write actions it is mandatory — the store uses (pr_id, session_id) as the compound key."
        )
        llmMistake(
            "Uses the wrong action name for listing, e.g. 'get_findings' or 'fetch_findings'. The only " +
                "valid actions are add_finding, list_findings, and clear_findings — typos return an " +
                "'unknown action' error."
        )
        llmMistake(
            "Passes severity as 'blocker' or 'normal' (lowercase). The tool upcases before parsing " +
                "FindingSeverity.valueOf(), so lowercase works — but if the LLM uses a synonym like " +
                "'critical' or 'error' it gets an invalid severity error. Only 'NORMAL' and 'BLOCKER' are valid."
        )
        llmMistake(
            "Stages findings then immediately calls bitbucket_review to push them. That is the user's " +
                "action from the AI Review tab — the LLM should NOT call bitbucket_review after ai_review. " +
                "The correct flow is: review files → ai_review(add_finding) per issue → ai_review(list_findings) " +
                "to summarise → call attempt_completion. The user then decides which findings to push."
        )
        llmMistake(
            "Passes line numbers as floats (e.g. 42.0 from a JSON number field). The tool parses via " +
                "jsonPrimitive.intOrNull then toIntOrNull — floats will parse as null and the finding " +
                "will be stored without a line anchor. LLM should emit integer JSON numbers or digit-only strings."
        )
        llmMistake(
            "Calls add_finding once per file rather than once per distinct issue. This creates a single " +
                "general finding instead of granular inline findings. Better pattern: one add_finding call " +
                "per issue, each pinned to the exact file + line where it occurs."
        )
        related(
            "bitbucket_review",
            Relationship.SEE_ALSO,
            "CONTRAST — bitbucket_review posts comments directly to Bitbucket in real-time with no " +
                "staging step. ai_review is the staging path: findings accumulate locally and the user " +
                "pushes them selectively from the AI Review tab. Use ai_review (not bitbucket_review) " +
                "during code review; use bitbucket_review only for direct one-off comments."
        )
        related(
            "bitbucket_pr",
            Relationship.COMPLEMENT,
            "Use bitbucket_pr to fetch the PR diff and changed files before calling ai_review(add_finding). " +
                "The typical pipeline is: bitbucket_pr(get_pr_detail) → read_file per changed file → " +
                "ai_review(add_finding) per issue found."
        )
        related(
            "read_file",
            Relationship.COMPOSE_WITH,
            "Read each changed file in the PR, identify issues, then stage them with ai_review(add_finding). " +
                "read_file is the gather step; ai_review is the record step."
        )
        related(
            "attempt_completion",
            Relationship.SEE_ALSO,
            "Call attempt_completion after ai_review(list_findings) to present the review summary to the " +
                "user. The user then pushes findings from the AI Review tab — attempt_completion should NOT " +
                "be called immediately after every add_finding."
        )
        downside(
            "No built-in deduplication — if the LLM calls add_finding twice for the same issue (e.g. " +
                "across a compacted context where the first call was forgotten), duplicate findings will be " +
                "stored and appear as duplicates in the AI Review tab. The user must remove duplicates manually " +
                "before pushing."
        )
        downside(
            "Findings are scoped to (pr_id, session_id). If the agent session is reset or a new session " +
                "is started for the same PR, old session findings persist but the new session starts empty " +
                "(list_findings without a session_id filter shows all sessions). The user must manage " +
                "multi-session accumulation manually."
        )
        downside(
            "FILE_WRITE classification means this tool is blocked in plan mode (plan mode blocks all " +
                "WRITE_TOOLS). A code-reviewer persona running in plan mode cannot stage findings — it would " +
                "have to switch to act mode first. In practice the code-reviewer persona always runs in act mode."
        )
        downside(
            "The tool is hook-exempt (bypasses PreToolUse/PostToolUse) which means no hook can veto or " +
                "observe individual add_finding calls. This is intentional for performance but means external " +
                "hook-based audit trails do not capture individual finding additions."
        )
        observation(
            "ai_review is not in WRITE_TOOLS (edit_file, create_file, run_command, etc.) — it bypasses " +
                "the approval gate entirely. The hook-exemption and non-membership in WRITE_TOOLS are " +
                "complementary: no gate, no hook, no plan-mode block. The rationale is that disk writes to " +
                "the local findings store are equivalent to internal bookkeeping (same reasoning as task_create)."
        )
        mergeOpportunity(
            "add_finding and clear_findings share the (pr_id, session_id) required pair. If a future " +
                "action needs batch-adding multiple findings, a 'batch_add' action with a JSON array param " +
                "would reduce round-trips during large code reviews."
        )
        observation(
            "The 'suggestion' param automatically wraps content in a Bitbucket suggestion fence. This " +
                "is invisible to the LLM — it only sees the plain replacement code. The wrapping happens " +
                "inside addFinding() before persistence. This means the stored message already contains " +
                "the fence; if the user edits the finding before pushing the fence is part of the stored text."
        )
        flowchart(
            """
            flowchart TD
                A[LLM calls ai_review] --> B{action param}
                B -- add_finding --> C{required params present?}
                C -- no pr_id / session_id / severity / message --> X1[ToolResult.error]
                C -- yes --> D{valid severity?}
                D -- no --> X2[ToolResult.error invalid severity]
                D -- yes --> E{suggestion param present?}
                E -- yes --> F[Append suggestion fence to message]
                E -- no --> G[Use message as-is]
                F --> G
                G --> H{valid anchor_side if present?}
                H -- no --> X3[ToolResult.error invalid anchor_side]
                H -- yes / absent --> I[Build PrReviewFinding with UUID id]
                I --> J[PrReviewFindingsStoreImpl.add]
                J -- ok --> K[Return JSON of stored finding]
                J -- error --> X4[ToolResult.error from store]
                B -- list_findings --> L{pr_id present?}
                L -- no --> X5[ToolResult.error]
                L -- yes --> M[PrReviewFindingsStoreImpl.list pr_id session_id? includeArchived]
                M -- ok --> N[Return JSON array of findings]
                M -- error --> X6[ToolResult.error from store]
                B -- clear_findings --> O{pr_id + session_id present?}
                O -- no --> X7[ToolResult.error]
                O -- yes --> P[PrReviewFindingsStoreImpl.clear]
                P -- ok --> Q[Return confirmation string]
                P -- error --> X8[ToolResult.error from store]
                B -- unknown --> X9[ToolResult.error unknown action]
            """
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Error: 'action' parameter required", "ai_review: missing action")

        return when (action) {
            "add_finding" -> addFinding(params)
            "list_findings" -> listFindings(params)
            "clear_findings" -> clearFindings(params)
            else -> ToolResult.error(
                "Error: unknown action '$action'. Valid actions: add_finding, list_findings, clear_findings",
                "ai_review: unknown action '$action'"
            )
        }
    }

    private suspend fun addFinding(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for add_finding", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'session_id' is required for add_finding", "ai_review: missing session_id")

        val severityStr = params["severity"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'severity' is required for add_finding (NORMAL or BLOCKER)", "ai_review: missing severity")

        val severity = runCatching { FindingSeverity.valueOf(severityStr.uppercase()) }.getOrNull()
            ?: return ToolResult.error(
                "Error: invalid severity '$severityStr'. Must be NORMAL or BLOCKER",
                "ai_review: invalid severity '$severityStr'"
            )

        val baseMessage = params["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'message' is required for add_finding", "ai_review: missing message")

        val suggestion = params["suggestion"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val message = if (suggestion != null) {
            "$baseMessage\n\n```suggestion\n$suggestion\n```"
        } else {
            baseMessage
        }

        val file = params["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val lineStart = params["line_start"]?.jsonPrimitive?.intOrNull
            ?: params["line_start"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        val lineEnd = params["line_end"]?.jsonPrimitive?.intOrNull
            ?: params["line_end"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        val anchorSideStr = params["anchor_side"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val anchorSide = anchorSideStr?.let {
            runCatching { AnchorSide.valueOf(it.uppercase()) }.getOrNull()
                ?: return ToolResult.error(
                    "Error: invalid anchor_side '$it'. Must be ADDED, REMOVED, or CONTEXT",
                    "ai_review: invalid anchor_side '$it'"
                )
        }

        val finding = PrReviewFinding(
            id = UUID.randomUUID().toString(),
            prId = prId,
            sessionId = sessionId,
            file = file,
            lineStart = lineStart,
            lineEnd = lineEnd,
            anchorSide = anchorSide,
            severity = severity,
            message = message,
            createdAt = System.currentTimeMillis(),
        )

        val result = storeProvider().add(finding)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to add finding — ${result.summary}",
                "ai_review: add_finding failed"
            )
        } else {
            val finding = result.data!!
            val encoded = json.encodeToString(finding)
            ToolResult(
                content = "Finding added.\n$encoded",
                summary = "Added finding ${finding.id} (${severity.name})",
                tokenEstimate = encoded.length / 4,
            )
        }
    }

    private suspend fun listFindings(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for list_findings", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val includeArchived = params["include_archived"]?.jsonPrimitive?.booleanOrNull
            ?: params["include_archived"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true)
            ?: false

        val result = storeProvider().list(prId, sessionId, includeArchived)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to list findings — ${result.summary}",
                "ai_review: list_findings failed"
            )
        } else {
            val encoded = json.encodeToString(result.data)
            ToolResult(
                content = encoded,
                summary = result.summary,
                tokenEstimate = encoded.length / 4,
            )
        }
    }

    private suspend fun clearFindings(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for clear_findings", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'session_id' is required for clear_findings", "ai_review: missing session_id")

        val result = storeProvider().clear(prId, sessionId)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to clear findings — ${result.summary}",
                "ai_review: clear_findings failed"
            )
        } else {
            ToolResult(
                content = "Findings cleared for PR $prId / session $sessionId.",
                summary = result.summary,
                tokenEstimate = 5,
            )
        }
    }
}
