package com.workflow.orchestrator.agent.e2e

import com.workflow.orchestrator.agent.runtime.WorkerType

/**
 * Defines an agent-level evaluation scenario for pass@3 consistency testing.
 *
 * These are scenario DEFINITIONS — not runnable e2e tests. Running them requires
 * a live LLM connection. Each scenario describes:
 * - What the user asks
 * - What tools the agent should use
 * - How many iterations are acceptable
 * - How to evaluate success
 *
 * Use [EvalScenarios.findById] to look up a scenario by ID.
 */
data class EvalScenario(
    /** Unique identifier for this scenario (e.g., "fix-compile", "explore-code"). */
    val id: String,
    /** Human-readable description of what the scenario tests. */
    val description: String,
    /** The user message that kicks off the agent session. */
    val userMessage: String,
    /** Expected worker type if delegation should occur, null if the main agent handles it. */
    val expectedWorkerType: WorkerType?,
    /** Set of tool names that should appear in the execution trace. */
    val expectedToolsUsed: Set<String>,
    /** Maximum acceptable iterations before the task should complete. */
    val maxIterations: Int,
    /** Human-readable description of what constitutes a successful outcome. */
    val successCriteria: String
)

/**
 * Result of running a single evaluation scenario.
 */
data class EvalResult(
    /** The scenario that was evaluated. */
    val scenario: EvalScenario,
    /** Whether the scenario passed its success criteria. */
    val passed: Boolean,
    /** Number of ReAct loop iterations used. */
    val iterations: Int,
    /** Total tokens consumed during the run. */
    val tokensUsed: Int,
    /** Worker types that were spawned during execution. */
    val workerTypesUsed: Set<WorkerType>,
    /** Tool names that were called during execution. */
    val toolsUsed: Set<String>,
    /** Optional failure reason if [passed] is false. */
    val failureReason: String? = null
)

/**
 * Registry of 10 agent-level evaluation scenarios covering the breadth
 * of the agent's capabilities.
 *
 * These scenarios are designed for pass@3 consistency testing: run each
 * scenario 3 times and check that at least 2 out of 3 runs pass.
 *
 * Categories covered:
 * 1. Single file fix (compile error)
 * 2. Codebase exploration (architecture question)
 * 3. Plan + execute (multi-step feature)
 * 4. PR review (code review)
 * 5. Jira integration (ticket workflow)
 * 6. Debug scenario (runtime error)
 * 7. Multi-file refactor (rename across files)
 * 8. Test generation (unit tests)
 * 9. Build trigger (CI/CD)
 * 10. Security review (vulnerability scan)
 */
object EvalScenarios {

    val ALL: List<EvalScenario> = listOf(
        // Scenario 1: Single file fix — agent reads a file with a compile error and fixes it
        EvalScenario(
            id = "fix-compile",
            description = "Fix a compilation error in a single file",
            userMessage = "Fix the error in UserService.kt: unresolved reference 'findById'",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("read_file", "edit_file", "diagnostics"),
            maxIterations = 10,
            successCriteria = "diagnostics returns 0 errors after edit"
        ),

        // Scenario 2: Codebase exploration — agent explores and answers an architecture question
        EvalScenario(
            id = "explore-code",
            description = "Explore codebase to answer an architecture question",
            userMessage = "How does authentication work in this project? Trace the flow from login to token validation.",
            expectedWorkerType = WorkerType.ANALYZER,
            expectedToolsUsed = setOf("search_code", "read_file", "find_definition", "find_references"),
            maxIterations = 15,
            successCriteria = "Response mentions auth-related classes with file paths and describes the flow"
        ),

        // Scenario 3: Plan + execute — agent creates a plan, gets approval, then executes
        EvalScenario(
            id = "plan-execute",
            description = "Create a plan for a multi-step feature and execute it",
            userMessage = "Add a new REST endpoint GET /api/users/{id}/activity that returns the user's recent activity log. " +
                "Create a plan first, then implement it.",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("create_plan", "read_file", "edit_file", "search_code"),
            maxIterations = 25,
            successCriteria = "Plan created with at least 3 steps, code files created/edited, endpoint is functional"
        ),

        // Scenario 4: PR review — agent reviews a pull request and provides feedback
        EvalScenario(
            id = "pr-review",
            description = "Review a pull request and provide structured feedback",
            userMessage = "Review PR #42 in the main repository. Check for bugs, style issues, and missing tests.",
            expectedWorkerType = WorkerType.REVIEWER,
            expectedToolsUsed = setOf("bitbucket_get_pr_detail", "bitbucket_get_pr_diff", "bitbucket_get_pr_changes"),
            maxIterations = 15,
            successCriteria = "Response includes structured review with specific line references and actionable feedback"
        ),

        // Scenario 5: Jira integration — agent works with a Jira ticket
        EvalScenario(
            id = "jira-workflow",
            description = "Fetch a Jira ticket, start work, and update status",
            userMessage = "Start working on PROJ-123. Read the ticket, create a branch, and transition it to In Progress.",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("jira_get_ticket", "jira_transition", "jira_start_work"),
            maxIterations = 10,
            successCriteria = "Ticket fetched, branch created or referenced, ticket transitioned to In Progress"
        ),

        // Scenario 6: Debug — agent investigates a runtime error
        EvalScenario(
            id = "debug-error",
            description = "Debug a NullPointerException by tracing the call chain",
            userMessage = "I'm getting a NullPointerException at OrderService.kt:42. " +
                "Find the root cause and fix it.",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("read_file", "find_definition", "find_references", "edit_file", "diagnostics"),
            maxIterations = 15,
            successCriteria = "Root cause identified with explanation, null check or fix applied, diagnostics pass"
        ),

        // Scenario 7: Multi-file refactor — rename a class across the codebase
        EvalScenario(
            id = "multi-file-refactor",
            description = "Rename a class and update all references across multiple files",
            userMessage = "Rename the class 'UserDTO' to 'UserResponse' everywhere in the project. " +
                "Update all imports, references, and variable names.",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("search_code", "read_file", "edit_file", "find_references"),
            maxIterations = 20,
            successCriteria = "All occurrences of UserDTO replaced with UserResponse, no compile errors"
        ),

        // Scenario 8: Test generation — generate unit tests for an existing class
        EvalScenario(
            id = "generate-tests",
            description = "Generate comprehensive unit tests for an existing service class",
            userMessage = "Write unit tests for PaymentService.kt. Cover happy paths, edge cases, and error scenarios.",
            expectedWorkerType = WorkerType.CODER,
            expectedToolsUsed = setOf("read_file", "edit_file", "search_code", "file_structure"),
            maxIterations = 20,
            successCriteria = "Test file created with at least 5 test methods covering different scenarios, tests compile"
        ),

        // Scenario 9: Build trigger — trigger and monitor a CI build
        EvalScenario(
            id = "trigger-build",
            description = "Trigger a Bamboo build and report the result",
            userMessage = "Trigger a build for the main branch on the PROJ-MAIN plan and tell me the status.",
            expectedWorkerType = null,
            expectedToolsUsed = setOf("bamboo_trigger_build", "bamboo_build_status"),
            maxIterations = 10,
            successCriteria = "Build triggered successfully, status reported back to user"
        ),

        // Scenario 10: Security review — scan for security issues
        EvalScenario(
            id = "security-review",
            description = "Scan the project for common security vulnerabilities",
            userMessage = "Review the project for security issues: hardcoded credentials, SQL injection, " +
                "path traversal, and insecure deserialization. Check the most critical files first.",
            expectedWorkerType = WorkerType.REVIEWER,
            expectedToolsUsed = setOf("search_code", "read_file", "glob_files", "sonar_issues"),
            maxIterations = 20,
            successCriteria = "Report with specific findings per category, file paths, and severity ratings"
        )
    )

    /**
     * Find a scenario by its unique ID.
     *
     * @param id The scenario ID (e.g., "fix-compile")
     * @return The matching scenario, or null if not found
     */
    fun findById(id: String): EvalScenario? = ALL.find { it.id == id }

    /**
     * Find all scenarios that expect a specific worker type.
     *
     * @param workerType The worker type to filter by, or null for main-agent scenarios
     * @return List of matching scenarios
     */
    fun findByWorkerType(workerType: WorkerType?): List<EvalScenario> =
        ALL.filter { it.expectedWorkerType == workerType }

    /**
     * Find all scenarios that expect a specific tool to be used.
     *
     * @param toolName The tool name to search for
     * @return List of scenarios that include this tool in their expected tools
     */
    fun findByTool(toolName: String): List<EvalScenario> =
        ALL.filter { toolName in it.expectedToolsUsed }

    /**
     * Get scenario IDs as a list, useful for parameterized test runners.
     */
    fun allIds(): List<String> = ALL.map { it.id }

    /**
     * Evaluate whether a run's results satisfy the basic structural criteria
     * of a scenario (iteration count, expected tools present).
     *
     * This is a lightweight check — the [successCriteria] string requires
     * human or LLM judgment to evaluate fully.
     *
     * @param scenario The scenario definition
     * @param iterations How many iterations the run took
     * @param toolsUsed Which tools were called during the run
     * @param workerTypesUsed Which worker types were spawned
     * @return EvalResult with pass/fail and details
     */
    fun evaluateStructural(
        scenario: EvalScenario,
        iterations: Int,
        toolsUsed: Set<String>,
        workerTypesUsed: Set<WorkerType>,
        tokensUsed: Int
    ): EvalResult {
        val reasons = mutableListOf<String>()

        // Check iteration budget
        if (iterations > scenario.maxIterations) {
            reasons.add("Exceeded max iterations: $iterations > ${scenario.maxIterations}")
        }

        // Check expected tools
        val missingTools = scenario.expectedToolsUsed - toolsUsed
        if (missingTools.isNotEmpty()) {
            reasons.add("Missing expected tools: $missingTools")
        }

        // Check expected worker type (if any)
        if (scenario.expectedWorkerType != null && scenario.expectedWorkerType !in workerTypesUsed) {
            reasons.add("Expected worker type ${scenario.expectedWorkerType} not used")
        }

        return EvalResult(
            scenario = scenario,
            passed = reasons.isEmpty(),
            iterations = iterations,
            tokensUsed = tokensUsed,
            workerTypesUsed = workerTypesUsed,
            toolsUsed = toolsUsed,
            failureReason = if (reasons.isNotEmpty()) reasons.joinToString("; ") else null
        )
    }
}
