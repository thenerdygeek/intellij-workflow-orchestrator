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

        state.hotspots = mutableListOf(
            SonarHotspot(
                key = "hotspot-1",
                message = "Make sure this permission is safe.",
                component = "com.example:service:src/main/java/SecurityConfig.java",
                securityCategory = "others",
                vulnerabilityProbability = "HIGH",
                status = "TO_REVIEW",
                line = 48,
                creationDate = "2026-03-15T10:22:00+0000",
                updateDate = "2026-03-15T10:22:00+0000",
                author = "dev@example.com",
            ),
            SonarHotspot(
                key = "hotspot-2",
                message = "Make sure that using a pseudorandom number generator is safe here.",
                component = "com.example:service:src/main/java/TokenGenerator.java",
                securityCategory = "insecure-conf",
                vulnerabilityProbability = "MEDIUM",
                status = "TO_REVIEW",
                line = 22,
                creationDate = "2026-03-10T14:30:00+0000",
                updateDate = "2026-03-12T09:15:00+0000",
                author = "dev@example.com",
            ),
            SonarHotspot(
                key = "hotspot-3",
                message = "Make sure this debug feature is deactivated in production.",
                component = "com.example:service:src/main/java/Debug.java",
                securityCategory = "others",
                vulnerabilityProbability = "LOW",
                status = "REVIEWED",
                resolution = "SAFE",
                line = 10,
                creationDate = "2026-02-20T08:00:00+0000",
                updateDate = "2026-03-01T11:45:00+0000",
                author = "senior@example.com",
            ),
        )

        state.duplications = mapOf(
            "com.example:service:src/main/java/Service.java" to listOf(
                SonarDuplication(
                    blocks = listOf(
                        SonarDuplicationBlock(ref = "1", from = 30, size = 15),
                        SonarDuplicationBlock(ref = "2", from = 80, size = 15),
                    ),
                ),
            ),
        )
        state.duplicationFiles = mapOf(
            "1" to SonarDuplicationFile(
                key = "com.example:service:src/main/java/Service.java",
                name = "Service.java",
                projectName = "Example Service",
            ),
            "2" to SonarDuplicationFile(
                key = "com.example:service:src/main/java/ServiceHelper.java",
                name = "ServiceHelper.java",
                projectName = "Example Service",
            ),
        )

        state.branches = mutableListOf(
            SonarBranch(
                name = "main",
                isMain = true,
                type = "BRANCH",
                qualityGateStatus = "OK",
                bugs = 2,
                vulnerabilities = 1,
                codeSmells = 14,
                analysisDate = "2026-03-30T18:45:00+0000",
            ),
            SonarBranch(
                name = "feature/PROJ-123-add-auth",
                isMain = false,
                type = "BRANCH",
                qualityGateStatus = "WARN",
                bugs = 0,
                vulnerabilities = 2,
                codeSmells = 3,
                analysisDate = "2026-03-31T09:12:00+0000",
            ),
            SonarBranch(
                name = "release/1.2.0",
                isMain = false,
                type = "BRANCH",
                qualityGateStatus = "ERROR",
                bugs = 5,
                vulnerabilities = 0,
                codeSmells = 8,
                analysisDate = "2026-03-29T22:00:00+0000",
            ),
        )

        state.ceTasks = mutableListOf(
            SonarCeTask(
                id = "AU-TpxcA-iU5OvuD2FLz",
                type = "REPORT",
                componentKey = "com.example:service",
                status = "SUCCESS",
                branch = "main",
                branchType = "BRANCH",
                submittedAt = "2026-03-30T18:40:00+0000",
                startedAt = "2026-03-30T18:40:05+0000",
                executedAt = "2026-03-30T18:45:00+0000",
                executionTimeMs = 295000,
                hasScannerContext = true,
            ),
            SonarCeTask(
                id = "AU-TpxcA-iU5OvuD2FMa",
                type = "REPORT",
                componentKey = "com.example:service",
                status = "FAILED",
                branch = "feature/PROJ-123-add-auth",
                branchType = "BRANCH",
                submittedAt = "2026-03-31T09:10:00+0000",
                startedAt = "2026-03-31T09:10:02+0000",
                executedAt = "2026-03-31T09:12:00+0000",
                executionTimeMs = 118000,
                errorMessage = "Quality Gate failed: new_coverage is less than 80%",
                hasErrorStacktrace = true,
            ),
            SonarCeTask(
                id = "AU-TpxcA-iU5OvuD2FNb",
                type = "REPORT",
                componentKey = "com.example:service",
                status = "IN_PROGRESS",
                branch = "main",
                branchType = "BRANCH",
                submittedAt = "2026-04-01T08:00:00+0000",
                startedAt = "2026-04-01T08:00:03+0000",
            ),
        )

        state.newCodePeriod = SonarNewCodePeriod(
            projectKey = "com.example:service",
            branchKey = "",
            type = "PREVIOUS_VERSION",
            value = "1.1.0",
            effectiveValue = "2026-03-01T00:00:00+0000",
            inherited = false,
        )

        state.projectMeasures = mutableListOf(
            SonarProjectMeasure("sqale_index", "320"),
            SonarProjectMeasure("sqale_rating", "2.0"),
            SonarProjectMeasure("duplicated_lines_density", "4.7"),
            SonarProjectMeasure("cognitive_complexity", "142"),
            SonarProjectMeasure("reliability_rating", "1.0"),
            SonarProjectMeasure("security_rating", "3.0"),
            SonarProjectMeasure("coverage", "82.3"),
            SonarProjectMeasure("branch_coverage", "71.2"),
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

        state.hotspots = mutableListOf()

        state.branches = mutableListOf(
            SonarBranch(
                name = "main",
                isMain = true,
                type = "BRANCH",
                qualityGateStatus = "OK",
                bugs = 0,
                vulnerabilities = 0,
                codeSmells = 2,
                analysisDate = "2026-03-30T18:45:00+0000",
            ),
        )

        state.ceTasks = mutableListOf(
            SonarCeTask(
                id = "AU-Happy-001",
                type = "REPORT",
                componentKey = "com.example:service",
                status = "SUCCESS",
                branch = "main",
                branchType = "BRANCH",
                submittedAt = "2026-03-30T18:40:00+0000",
                startedAt = "2026-03-30T18:40:05+0000",
                executedAt = "2026-03-30T18:45:00+0000",
                executionTimeMs = 295000,
                hasScannerContext = true,
            ),
        )

        state.newCodePeriod = SonarNewCodePeriod(
            projectKey = "com.example:service",
            branchKey = "",
            type = "PREVIOUS_VERSION",
            value = "1.0.0",
            effectiveValue = "2026-02-15T00:00:00+0000",
            inherited = false,
        )

        state.projectMeasures = mutableListOf(
            SonarProjectMeasure("sqale_index", "45"),
            SonarProjectMeasure("sqale_rating", "1.0"),
            SonarProjectMeasure("duplicated_lines_density", "1.2"),
            SonarProjectMeasure("cognitive_complexity", "38"),
            SonarProjectMeasure("reliability_rating", "1.0"),
            SonarProjectMeasure("security_rating", "1.0"),
            SonarProjectMeasure("coverage", "92.0"),
            SonarProjectMeasure("branch_coverage", "88.0"),
        )

        return state
    }
}
