package com.workflow.orchestrator.mockserver.jira

object JiraDataFactory {

    private val STATUS_OPEN = JiraStatus("1", "Open", JiraStatusCategory(1, "new", "To Do"))
    private val STATUS_WIP = JiraStatus("2", "WIP", JiraStatusCategory(2, "in_flight", "In Progress"))
    private val STATUS_PEER_REVIEW = JiraStatus("3", "Peer Review", JiraStatusCategory(2, "in_flight", "In Progress"))
    private val STATUS_QA_TESTING = JiraStatus("4", "QA Testing", JiraStatusCategory(3, "verification", "Verification"))
    private val STATUS_APPROVED = JiraStatus("5", "Approved", JiraStatusCategory(4, "done", "Done"))
    private val STATUS_CLOSED = JiraStatus("6", "Closed", JiraStatusCategory(4, "done", "Done"))
    private val STATUS_BLOCKED = JiraStatus("7", "Blocked", JiraStatusCategory(5, "blocked", "Blocked"))
    private val STATUS_INVESTIGATING = JiraStatus("8", "Investigating", JiraStatusCategory(6, "indeterminate", "In Progress"))

    private val ALL_STATUSES = listOf(
        STATUS_OPEN, STATUS_WIP, STATUS_PEER_REVIEW, STATUS_QA_TESTING,
        STATUS_APPROVED, STATUS_CLOSED, STATUS_BLOCKED, STATUS_INVESTIGATING,
    )

    private val TRANSITIONS = mapOf(
        "1" to listOf(
            JiraTransition("11", "Start Working", STATUS_WIP,
                mapOf("assignee" to TransitionField(required = true, name = "Assignee"))),
            JiraTransition("12", "Block", STATUS_BLOCKED, emptyMap()),
            JiraTransition("18", "Investigate", STATUS_INVESTIGATING, emptyMap()),
        ),
        "2" to listOf(
            JiraTransition("21", "Move to Peer Review", STATUS_PEER_REVIEW,
                mapOf("comment" to TransitionField(required = true, name = "Review Notes"))),
            JiraTransition("22", "Block", STATUS_BLOCKED, emptyMap()),
            JiraTransition("23", "Reopen", STATUS_OPEN, emptyMap()),
        ),
        "3" to listOf(
            JiraTransition("31", "Send to QA", STATUS_QA_TESTING, emptyMap()),
            JiraTransition("32", "Reject", STATUS_WIP, emptyMap()),
        ),
        "4" to listOf(
            JiraTransition("41", "Approve", STATUS_APPROVED, emptyMap()),
            JiraTransition("42", "Reject", STATUS_WIP,
                mapOf("comment" to TransitionField(required = true, name = "Rejection Reason"))),
        ),
        "5" to listOf(
            JiraTransition("51", "Close", STATUS_CLOSED, emptyMap()),
        ),
        "7" to listOf(
            JiraTransition("71", "Unblock", STATUS_OPEN,
                mapOf("comment" to TransitionField(required = true, name = "Resolution"))),
        ),
        "8" to listOf(
            JiraTransition("81", "Start Working", STATUS_WIP, emptyMap()),
            JiraTransition("82", "Block", STATUS_BLOCKED, emptyMap()),
        ),
    )

    fun createDefaultState(): JiraState {
        val state = JiraState()
        state.boards = mutableListOf(JiraBoard(42, "Project Board", "scrum"))
        state.sprints = mutableListOf(
            JiraSprint(7, "Sprint 2026.11", "active", 42),
            JiraSprint(6, "Sprint 2026.10", "closed", 42),
        )
        state.statuses = ALL_STATUSES
        state.transitionMap = TRANSITIONS
        state.issues = createDefaultIssues()
        return state
    }

    private fun createDefaultIssues(): java.util.concurrent.ConcurrentHashMap<String, JiraIssue> {
        val map = java.util.concurrent.ConcurrentHashMap<String, JiraIssue>()
        val issues = listOf(
            JiraIssue("PROJ-101", "Implement user authentication flow", STATUS_OPEN, "Story",
                "mock.user", "High",
                issueLinks = listOf(JiraIssueLink("relates-to", "PROJ-102", null))),
            JiraIssue("PROJ-102", "Fix payment gateway timeout", STATUS_WIP, "Defect",
                "mock.user", "Critical",
                issueLinks = listOf(JiraIssueLink("blocked-by", null, "PROJ-105"))),
            JiraIssue("PROJ-103", "", STATUS_PEER_REVIEW, "Spike",
                "mock.user", "Medium"),
            JiraIssue("PROJ-104", "Database migration for audit tables", STATUS_QA_TESTING, "Tech Debt",
                "mock.user", "Low"),
            JiraIssue("PROJ-105", "Evaluate caching strategies", STATUS_INVESTIGATING, "Spike",
                "mock.user", "Medium"),
            JiraIssue("PROJ-106", "Update dependencies to latest", STATUS_BLOCKED, "Tech Debt",
                "mock.user", "Low",
                issueLinks = listOf(JiraIssueLink("blocked-by", null, "EXT-999"))),
        )
        issues.forEach { map[it.key] = it }
        return map
    }

    fun createEmptySprintState(): JiraState {
        val state = createDefaultState()
        state.issues.clear()
        return state
    }

    fun createLargeSprintState(): JiraState {
        val state = createDefaultState()
        val types = listOf("Story", "Defect", "Spike", "Tech Debt")
        val priorities = listOf("Critical", "High", "Medium", "Low")
        for (i in 200..250) {
            val status = ALL_STATUSES[i % ALL_STATUSES.size]
            state.issues["PROJ-$i"] = JiraIssue(
                key = "PROJ-$i",
                summary = "Auto-generated ticket $i for load testing",
                status = status,
                issueType = types[i % types.size],
                assignee = "mock.user",
                priority = priorities[i % priorities.size],
            )
        }
        return state
    }

    fun createNoActiveSprintState(): JiraState {
        val state = createDefaultState()
        state.sprints = mutableListOf(
            JiraSprint(6, "Sprint 2026.10", "closed", 42),
            JiraSprint(8, "Sprint 2026.12", "future", 42),
        )
        return state
    }

    fun createTransitionBlockedState(): JiraState {
        val state = createDefaultState()
        val blockedTransitions = TRANSITIONS.mapValues { (_, transitions) ->
            transitions.map { t ->
                t.copy(fields = t.fields + mapOf(
                    "comment" to TransitionField(required = true, name = "Justification"),
                    "assignee" to TransitionField(required = true, name = "Assignee"),
                ))
            }
        }
        state.transitionMap = blockedTransitions
        return state
    }

    fun createHappyPathState(): JiraState {
        val standardOpen = JiraStatus("1", "To Do", JiraStatusCategory(1, "new", "To Do"))
        val standardInProgress = JiraStatus("2", "In Progress", JiraStatusCategory(2, "indeterminate", "In Progress"))
        val standardDone = JiraStatus("3", "Done", JiraStatusCategory(3, "done", "Done"))
        val state = JiraState()
        state.boards = mutableListOf(JiraBoard(42, "Project Board", "scrum"))
        state.sprints = mutableListOf(JiraSprint(7, "Sprint 2026.11", "active", 42))
        state.statuses = listOf(standardOpen, standardInProgress, standardDone)
        state.transitionMap = mapOf(
            "1" to listOf(JiraTransition("11", "In Progress", standardInProgress)),
            "2" to listOf(JiraTransition("21", "Done", standardDone)),
        )
        val map = java.util.concurrent.ConcurrentHashMap<String, JiraIssue>()
        map["PROJ-101"] = JiraIssue("PROJ-101", "Standard ticket", standardOpen, "Story", "mock.user", "High")
        map["PROJ-102"] = JiraIssue("PROJ-102", "Another ticket", standardInProgress, "Bug", "mock.user", "Medium")
        state.issues = map
        return state
    }
}
