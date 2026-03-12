package com.workflow.orchestrator.mockserver.sonar

object SonarDataFactory {

    fun createDefaultState(): SonarState {
        val state = SonarState()
        state.projects = mutableListOf(SonarProject("com.example:service", "Example Service"))

        state.qualityGate = SonarQualityGate(
            status = "WARN",
            conditions = listOf(
                SonarCondition("OK", "coverage", "LT", errorThreshold = "80", actualValue = "82.3"),
                SonarCondition("WARN", "security_rating", "GT", warningThreshold = "1", actualValue = "3"),
                SonarCondition("ERROR", "new_coverage", "LT", errorThreshold = "80", actualValue = "45.0"),
            ),
        )

        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "82.3"),
            SonarMeasure("com.example:service", "line_coverage", "85.1"),
            SonarMeasure("com.example:service", "branch_coverage", "71.2"),
            SonarMeasure("com.example:service", "uncovered_lines", "42"),
            // uncovered_conditions deliberately OMITTED
            SonarMeasure("com.example:service", "security_rating", "3.0"),
            SonarMeasure("com.example:service", "reliability_rating", "1.0"),
        )

        state.issues = mutableListOf(
            SonarIssue("issue-1", "java:S1135", "MAJOR", "CODE_SMELL", "Complete the task associated with this TODO comment.", "com.example:service:src/main/java/Service.java", 42),
            SonarIssue("issue-2", "java:S2259", "BLOCKER", "BUG", "A NullPointerException could be thrown.", "com.example:service:src/main/java/Service.java", 87),
            SonarIssue("issue-3", "java:S5122", "CRITICAL", "VULNERABILITY", "Make sure that enabling CORS is safe here.", "com.example:service:src/main/java/Controller.java", 15),
            SonarIssue("issue-4", "java:S4790", "CRITICAL_SECURITY", "SECURITY_HOTSPOT", "Use a stronger hashing algorithm.", "com.example:service:src/main/java/Crypto.java", 33),
            SonarIssue("issue-5", "custom:AUDIT1", "MAJOR", "SECURITY_AUDIT", "Manual security review required.", "com.example:service:src/main/java/Auth.java", 12),
            SonarIssue("issue-6", "java:S1192", "MINOR", "CODE_SMELL", "Define a constant instead of duplicating this literal.", "com.example:service:src/main/java/Config.java", 28),
            SonarIssue("issue-7", "java:S106", "INFO", "CODE_SMELL", "Replace this use of System.out or System.err.", "com.example:service:src/main/java/Debug.java", 5),
            SonarIssue("issue-8", "java:S2095", "BLOCKER", "BUG", "Use try-with-resources for this AutoCloseable.", "com.example:service:src/main/java/Dao.java", 91),
            SonarIssue("issue-9", "java:S3776", "CRITICAL", "CODE_SMELL", "Refactor this method to reduce Cognitive Complexity.", "com.example:service:src/main/java/Processor.java", 114),
            SonarIssue("issue-10", "java:S2187", "MAJOR", "CODE_SMELL", "Add some tests to this class.", "com.example:service:src/test/java/ProcessorTest.java", null),
            SonarIssue("issue-11", "java:S5131", "BLOCKER", "VULNERABILITY", "This value is tainted and could lead to XSS.", "com.example:service:src/main/java/TemplateEngine.java", 67),
            SonarIssue("issue-12", "java:S4830", "CRITICAL_SECURITY", "VULNERABILITY", "Disable server certificate validation here.", "com.example:service:src/main/java/HttpClient.java", 44),
        )

        return state
    }

    fun createQualityGateWarnState(): SonarState {
        return createDefaultState()
    }

    fun createMetricsMissingState(): SonarState {
        val state = createDefaultState()
        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "62.1"),
        )
        return state
    }

    fun createAuthInvalidState(): SonarState {
        val state = createDefaultState()
        state.authValid = false
        return state
    }

    fun createHappyPathState(): SonarState {
        val state = SonarState()
        state.projects = mutableListOf(SonarProject("com.example:service", "Example Service"))
        state.qualityGate = SonarQualityGate(
            status = "OK",
            conditions = listOf(
                SonarCondition("OK", "coverage", "LT", errorThreshold = "80", actualValue = "92.0"),
            ),
        )
        state.measures = mutableListOf(
            SonarMeasure("com.example:service", "coverage", "92.0"),
            SonarMeasure("com.example:service", "line_coverage", "94.0"),
            SonarMeasure("com.example:service", "branch_coverage", "88.0"),
            SonarMeasure("com.example:service", "uncovered_lines", "8"),
            SonarMeasure("com.example:service", "uncovered_conditions", "3"),
        )
        state.issues = mutableListOf(
            SonarIssue("issue-1", "java:S1135", "MAJOR", "CODE_SMELL", "Complete the task.", "com.example:service:src/main/java/Service.java", 42),
            SonarIssue("issue-2", "java:S2259", "BLOCKER", "BUG", "NPE possible.", "com.example:service:src/main/java/Service.java", 87),
        )
        return state
    }
}
