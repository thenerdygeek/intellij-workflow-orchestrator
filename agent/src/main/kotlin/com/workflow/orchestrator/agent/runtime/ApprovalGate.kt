package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.security.CommandRisk
import com.workflow.orchestrator.agent.security.CommandSafetyAnalyzer
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/**
 * Risk levels for agent actions. Determines whether user approval is needed.
 */
enum class RiskLevel {
    NONE,        // Read-only: auto-approve always
    LOW,         // Non-destructive write (Jira comment): configurable
    MEDIUM,      // File edit: show diff, require accept/reject
    HIGH,        // Shell command, PR creation: always require approval
    DESTRUCTIVE  // File deletion, force push: block + typed confirmation
}

/**
 * Result of an approval check.
 */
sealed class ApprovalResult {
    object Approved : ApprovalResult()
    data class Rejected(val reason: String = "Rejected by user") : ApprovalResult()
    data class Pending(val description: String, val riskLevel: RiskLevel) : ApprovalResult()
}

/**
 * Audit log entry recording every approval decision with context.
 */
data class AuditEntry(
    val toolName: String,
    val riskLevel: RiskLevel,
    val params: Map<String, Any?>,
    val timestamp: Instant,
    var result: ApprovalResult? = null
)

/**
 * Gates agent actions based on risk level and user preferences.
 *
 * The approval gate checks each tool action against the user's configured
 * autonomy level and either auto-approves, queues for approval, or blocks.
 *
 * Supports two modes:
 * - **Synchronous** (legacy): Uses [onApprovalNeeded] callback for immediate decisions.
 * - **Asynchronous** (blocking): Uses [CompletableDeferred] + [respondToApproval] for UI-driven approval.
 *   Waits indefinitely until user responds.
 */
class ApprovalGate(
    private val approvalRequired: Boolean = true,
    private val onApprovalNeeded: ((String, RiskLevel) -> ApprovalResult)? = null,
    private val approvalCallback: ((String, RiskLevel, Map<String, Any?>) -> Unit)? = null
) {
    private val log = Logger.getInstance(ApprovalGate::class.java)
    /**
     * Audit log recording every approval decision.
     * Thread-safe — backed by [java.util.Collections.synchronizedList].
     */
    val auditLog: MutableList<AuditEntry> = java.util.Collections.synchronizedList(mutableListOf())

    /** Tools that the user has approved for the rest of this session. */
    private val sessionAllowedTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * The pending deferred for the current approval request.
     * Completed by [respondToApproval] from the UI thread.
     */
    @Volatile
    private var pendingApproval: CompletableDeferred<ApprovalResult>? = null

    /**
     * Legacy synchronous check for backward compatibility.
     * @param toolName The tool being called
     * @param description Human-readable description of what the tool will do
     * @param riskLevel The risk level of this action
     * @return ApprovalResult indicating whether to proceed
     */
    fun check(toolName: String, description: String, riskLevel: RiskLevel): ApprovalResult {
        // Read-only actions always proceed
        if (riskLevel == RiskLevel.NONE) return ApprovalResult.Approved

        // If approval not required in settings, auto-approve LOW and MEDIUM
        if (!approvalRequired && riskLevel <= RiskLevel.MEDIUM) return ApprovalResult.Approved

        // HIGH and DESTRUCTIVE always require approval regardless of settings
        if (riskLevel >= RiskLevel.HIGH || (approvalRequired && riskLevel >= RiskLevel.MEDIUM)) {
            val result = onApprovalNeeded?.invoke(description, riskLevel)
                ?: ApprovalResult.Rejected("No approval callback — blocked by default")

            // Record in audit log
            auditLog.add(AuditEntry(
                toolName = toolName,
                riskLevel = riskLevel,
                params = emptyMap(),
                timestamp = Instant.now(),
                result = result
            ))

            return result
        }

        return ApprovalResult.Approved
    }

    /**
     * Blocking approval check — the primary method for agentic execution.
     *
     * Uses [CompletableDeferred] to block indefinitely until the UI calls [respondToApproval].
     * The agent will wait as long as needed for the user to approve or reject.
     *
     * @param toolName The tool being called
     * @param params Tool parameters — used for context-aware risk classification
     * @return ApprovalResult indicating whether to proceed
     */
    suspend fun check(toolName: String, params: Map<String, Any?> = emptyMap()): ApprovalResult {
        val risk = classifyRisk(toolName, params)

        // Read-only actions always proceed
        if (risk == RiskLevel.NONE) {
            recordAudit(toolName, risk, params, ApprovalResult.Approved)
            return ApprovalResult.Approved
        }

        // If approval not required, auto-approve LOW
        if (!approvalRequired && risk <= RiskLevel.LOW) {
            recordAudit(toolName, risk, params, ApprovalResult.Approved)
            return ApprovalResult.Approved
        }

        // Session-level per-tool auto-approve
        if (toolName in sessionAllowedTools) {
            log.info("[ApprovalGate] Session-allowed tool: $toolName")
            recordAudit(toolName, risk, params, ApprovalResult.Approved)
            return ApprovalResult.Approved
        }

        // Record pending audit entry
        val auditEntry = AuditEntry(
            toolName = toolName,
            riskLevel = risk,
            params = params,
            timestamp = Instant.now(),
            result = null
        )
        auditLog.add(auditEntry)

        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApproval = deferred

        // Notify UI via callback
        approvalCallback?.invoke(toolName, risk, params)

        // Wait indefinitely until user responds
        return try {
            val result = deferred.await()
            auditEntry.result = result
            result
        } finally {
            pendingApproval = null
        }
    }

    /**
     * Complete the pending approval from the UI thread.
     * Called when the user approves or rejects an action in the approval dialog.
     */
    fun respondToApproval(result: ApprovalResult) {
        pendingApproval?.complete(result)
    }

    /**
     * Mark a tool as auto-approved for the remainder of this session.
     * Also resolves any pending approval for this tool immediately.
     */
    fun allowToolForSession(toolName: String) {
        sessionAllowedTools.add(toolName)
        log.info("[ApprovalGate] Tool allowed for session: $toolName")
        // Also resolve any pending approval for this tool
        pendingApproval?.complete(ApprovalResult.Approved)
    }

    /**
     * Reset session-level state (allowed tools, pending approval).
     */
    fun reset() {
        sessionAllowedTools.clear()
        pendingApproval = null
    }

    private fun recordAudit(toolName: String, risk: RiskLevel, params: Map<String, Any?>, result: ApprovalResult) {
        auditLog.add(AuditEntry(
            toolName = toolName,
            riskLevel = risk,
            params = params,
            timestamp = Instant.now(),
            result = result
        ))
    }

    companion object {
        /**
         * Tools that are ALWAYS NONE risk — auto-approved, no dialog.
         * These are read-only tools, meta-tools, and reasoning tools.
         */
        private val NONE_RISK_TOOLS = setOf(
            // Meta-tools (no side effects at all)
            "request_tools", "think", "activate_skill", "deactivate_skill",

            // Planning (creates plan context, doesn't modify files)
            "create_plan", "update_plan_step", "ask_questions",

            // Core read-only
            "read_file", "search_code", "glob_files", "file_structure",
            "find_definition", "find_references", "type_hierarchy", "call_hierarchy",
            "find_implementations", "diagnostics",

            // Read-only meta-tools (all actions within these are read-only)
            "sonar", "spring", "build",

            // IDE read-only (inspections, annotations, quickfixes, method bodies)
            "get_annotations", "get_method_body", "list_quickfixes", "run_inspections",
            "problem_view",

            // PSI analysis read-only
            "type_inference", "structural_search", "dataflow_analysis",
            "read_write_access", "test_finder",

            // Process interaction (kill is safe, ask_user_input is user-driven)
            "kill_process", "ask_user_input",

            // Subagent spawning (runs in isolated context)
            "agent", "delegate_task",

            // Change tracking + project context (read-only)
            "list_changes", "project_context"
        )

        /**
         * Tools that are LOW risk — auto-approved unless user has strict settings.
         * These create content but don't modify existing project files.
         */
        private val LOW_RISK_TOOLS = setOf(
            // IDE non-destructive
            "format_code", "optimize_imports"
        )

        /**
         * Tools that are MEDIUM risk — require approval when setting is enabled.
         * These modify project files or code.
         */
        private val MEDIUM_RISK_TOOLS = setOf(
            "edit_file",
            "create_file",
            "send_stdin",
            "refactor_rename",

            // Meta-tools with mixed read/write actions — classified at MEDIUM
            // (action-aware classification in classifyRisk upgrades read-only actions to NONE)
            "jira", "bamboo", "bitbucket", "git", "runtime", "debug"
        )

        // Everything else is HIGH: run_command, etc.

        /** Meta-tools that contain both read-only and write actions. */
        private val META_TOOLS_WITH_MIXED_RISK = setOf("jira", "bamboo", "bitbucket", "git", "runtime", "debug")

        /** Read-only actions per meta-tool — auto-approved (NONE risk). */
        private val META_TOOL_READ_ONLY_ACTIONS = mapOf(
            "jira" to setOf("get_ticket", "get_transitions", "get_comments", "get_worklogs",
                "get_sprints", "get_linked_prs", "get_boards", "get_sprint_issues",
                "get_board_issues", "search_issues", "get_dev_branches"),
            "bamboo" to setOf("build_status", "get_build", "get_build_log", "get_test_results",
                "recent_builds", "get_plans", "get_project_plans", "search_plans",
                "get_plan_branches", "get_running_builds", "get_build_variables",
                "get_plan_variables", "get_artifacts"),
            "bitbucket" to setOf("get_pr_commits", "get_file_content", "get_branches",
                "get_my_prs", "get_reviewing_prs", "get_pr_detail", "get_pr_activities",
                "get_pr_changes", "get_pr_diff", "get_build_statuses",
                "check_merge_status", "search_users", "list_repos"),
            "git" to setOf("status", "diff", "log", "blame", "branches",
                "show_file", "show_commit", "stash_list", "merge_base", "file_history"),
            "runtime" to setOf("get_run_configurations", "get_running_processes",
                "get_run_output", "get_test_results"),
            "debug" to setOf("list_breakpoints", "get_state", "get_stack_frames",
                "get_variables", "thread_dump", "memory_view")
        )

        /** Low-risk actions — non-destructive writes, verification, comments. */
        private val META_TOOL_LOW_RISK_ACTIONS = mapOf(
            "jira" to setOf("comment", "log_work", "start_work"),
            "bitbucket" to setOf("add_inline_comment", "reply_to_comment", "add_reviewer",
                "add_pr_comment"),
            "git" to setOf("shelve"),  // list/list_shelves/create/shelve/unshelve — all low risk
            "runtime" to setOf("run_tests", "compile_module"),  // verification, doesn't modify source
            "debug" to setOf("add_breakpoint", "remove_breakpoint", "step_over",
                "step_into", "step_out", "resume", "pause", "run_to_cursor")
        )

        /** Classify a meta-tool action based on read/write semantics. */
        private fun classifyMetaToolAction(toolName: String, action: String): RiskLevel {
            val readOnly = META_TOOL_READ_ONLY_ACTIONS[toolName]
            if (readOnly != null && action in readOnly) return RiskLevel.NONE

            val lowRisk = META_TOOL_LOW_RISK_ACTIONS[toolName]
            if (lowRisk != null && action in lowRisk) return RiskLevel.LOW

            // Write/destructive actions within the meta-tool
            return RiskLevel.MEDIUM
        }

        /** Determine risk level for a given tool (static classification, no params). */
        fun riskLevelFor(toolName: String): RiskLevel = when {
            toolName in NONE_RISK_TOOLS -> RiskLevel.NONE
            toolName in LOW_RISK_TOOLS -> RiskLevel.LOW
            toolName in MEDIUM_RISK_TOOLS -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }

        /**
         * Context-aware risk classification that considers tool parameters.
         *
         * For tools like `edit_file`, the risk depends on WHAT is being edited:
         * - Test files and docs (.md, .txt) are LOW risk
         * - Production source code (/main/) is MEDIUM risk
         *
         * For `run_command`, the risk depends on the command content:
         * - Read-only commands (ls, grep, git status) are LOW risk
         * - Risky commands (git push, docker build) are HIGH risk
         * - Dangerous commands (rm -rf, curl|bash, DROP TABLE) are DESTRUCTIVE
         *
         * @param toolName The tool being called
         * @param params Tool parameters for context-aware classification
         * @return The assessed risk level
         */
        fun classifyRisk(toolName: String, params: Map<String, Any?> = emptyMap()): RiskLevel {
            // Static classification for known-safe tools
            if (toolName in NONE_RISK_TOOLS) return RiskLevel.NONE
            if (toolName in LOW_RISK_TOOLS) return RiskLevel.LOW

            // Context-aware classification for edit_file and create_file
            if (toolName == "edit_file" || toolName == "create_file") {
                val path = params["path"] as? String ?: return RiskLevel.MEDIUM
                return when {
                    path.contains("/test/") || path.endsWith("Test.kt") || path.endsWith("Test.java") -> RiskLevel.LOW
                    path.endsWith(".md") || path.endsWith(".txt") -> RiskLevel.LOW
                    path.contains("/main/") -> RiskLevel.MEDIUM
                    else -> RiskLevel.MEDIUM
                }
            }

            // 5D: Context-aware classification for run_command — delegates to CommandSafetyAnalyzer
            if (toolName == "run_command") {
                val command = params["command"] as? String ?: return RiskLevel.HIGH
                return when (CommandSafetyAnalyzer.classify(command)) {
                    CommandRisk.SAFE -> RiskLevel.LOW
                    CommandRisk.RISKY -> RiskLevel.HIGH
                    CommandRisk.DANGEROUS -> RiskLevel.DESTRUCTIVE
                }
            }

            // Action-aware classification for meta-tools with mixed read/write actions
            if (toolName in META_TOOLS_WITH_MIXED_RISK) {
                val action = params["action"] as? String ?: return RiskLevel.MEDIUM
                return classifyMetaToolAction(toolName, action)
            }

            // Fall through to static classification for remaining tools
            if (toolName in MEDIUM_RISK_TOOLS) return RiskLevel.MEDIUM
            return RiskLevel.HIGH
        }

    }
}
