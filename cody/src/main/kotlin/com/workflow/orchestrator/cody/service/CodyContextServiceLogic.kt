package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.Range

class CodyContextServiceLogic {

    fun buildFixInstruction(issueType: String, issueMessage: String, ruleKey: String): String =
        """Fix the following SonarQube $issueType issue (rule: $ruleKey):
           |$issueMessage
           |
           |Provide a minimal fix that addresses the issue without changing behavior.""".trimMargin()

    fun buildTestInstruction(range: Range, existingTestFile: String?): String = buildString {
        append("Generate a unit test covering the code at lines ")
        append("${range.start.line}-${range.end.line}. ")
        append("Use JUnit 5 with standard assertions. ")
        if (existingTestFile != null) {
            append("Add to the existing test file: $existingTestFile. ")
            append("Match the existing test style and imports.")
        } else {
            append("Create a new test class with proper package and imports.")
        }
    }

    fun resolveTestFile(sourceFilePath: String): String? {
        val normalized = sourceFilePath.replace('\\', '/')
        if (!normalized.contains("src/main/")) return null
        val testPath = normalized.replace("src/main/", "src/test/")
        val ext = testPath.substringAfterLast(".")
        val nameWithoutExt = testPath.substringBeforeLast(".")
        return "${nameWithoutExt}Test.$ext"
    }
}
