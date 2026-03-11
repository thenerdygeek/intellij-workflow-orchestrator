package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range

class CodyContextService(private val project: Project) {

    private val logic = CodyContextServiceLogic()

    data class FixContext(
        val instruction: String,
        val contextFiles: List<ContextFile>
    )

    data class TestContext(
        val instruction: String,
        val contextFiles: List<ContextFile>,
        val existingTestFile: String?
    )

    fun gatherFixContext(
        filePath: String,
        issueRange: Range,
        issueType: String,
        issueMessage: String,
        ruleKey: String
    ): FixContext {
        val instruction = logic.buildFixInstruction(issueType, issueMessage, ruleKey)
        val contextFiles = listOf(
            ContextFile(uri = filePath, range = issueRange)
        )
        return FixContext(instruction, contextFiles)
    }

    fun gatherTestContext(
        filePath: String,
        targetRange: Range
    ): TestContext {
        val existingTestFile = logic.resolveTestFile(filePath)
        val instruction = logic.buildTestInstruction(targetRange, existingTestFile)
        val contextFiles = mutableListOf(
            ContextFile(uri = filePath, range = targetRange)
        )
        if (existingTestFile != null) {
            contextFiles.add(ContextFile(uri = existingTestFile))
        }
        return TestContext(instruction, contextFiles, existingTestFile)
    }
}
