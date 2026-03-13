package com.workflow.orchestrator.cody.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.agent.CodyAgentProviderService
import com.workflow.orchestrator.cody.protocol.*
import kotlinx.coroutines.future.await

class CodyEditService(private val project: Project) {

    private fun providerService() = CodyAgentProviderService.getInstance(project)

    suspend fun requestFix(
        filePath: String,
        range: Range,
        instruction: String,
        contextFiles: List<ContextFile> = emptyList()
    ): EditTask {
        val providerSvc = providerService()
        val server = providerSvc.ensureRunning()
        // Set pending instruction so editTask/getUserInput handler can respond
        setPendingEditInstruction(providerSvc, instruction)
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        return server.editCommandsCode(
            EditCommandsCodeParams(
                instruction = instruction,
                mode = "edit",
                range = range,
                contextFiles = contextFiles.takeIf { it.isNotEmpty() }
            )
        ).await()
    }

    suspend fun requestTestGeneration(
        filePath: String,
        targetRange: Range,
        existingTestFile: String? = null
    ): EditTask {
        val providerSvc = providerService()
        val server = providerSvc.ensureRunning()
        server.textDocumentDidFocus(TextDocumentIdentifier(filePath))
        val instruction = buildString {
            append("Generate a unit test covering the code at lines ")
            append("${targetRange.start.line}-${targetRange.end.line}")
            if (existingTestFile != null) {
                append(". Add to existing test file: $existingTestFile")
            }
        }
        setPendingEditInstruction(providerSvc, instruction)
        return server.editCommandsCode(
            EditCommandsCodeParams(instruction = instruction, mode = "edit", range = targetRange)
        ).await()
    }

    private fun setPendingEditInstruction(
        providerSvc: CodyAgentProviderService,
        instruction: String
    ) {
        providerSvc.getClient()?.pendingEditInstruction = instruction
    }

    suspend fun acceptEdit(taskId: String) {
        val server = providerService().ensureRunning()
        server.editTaskAccept(EditTaskParams(taskId)).await()
    }

    suspend fun undoEdit(taskId: String) {
        val server = providerService().ensureRunning()
        server.editTaskUndo(EditTaskParams(taskId)).await()
    }

    suspend fun cancelEdit(taskId: String) {
        val server = providerService().ensureRunning()
        server.editTaskCancel(EditTaskParams(taskId)).await()
    }
}
