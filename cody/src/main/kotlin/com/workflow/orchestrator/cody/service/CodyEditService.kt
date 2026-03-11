package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentManager
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyEditService(private val project: Project) {

    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = range)
        ).await()
    }

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        val instruction = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
        }
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = targetRange)
        ).await()
    }

    suspend fun acceptEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskAccept(EditTaskParams(taskId)).await()
    }

    suspend fun undoEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskUndo(EditTaskParams(taskId)).await()
    }

    suspend fun cancelEdit(taskId: String) {
        val server = CodyAgentManager.getInstance(project).ensureRunning()
        server.editTaskCancel(EditTaskParams(taskId)).await()
    }
}
