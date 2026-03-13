package com.workflow.orchestrator.cody.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.cody.protocol.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class CodyAgentClient(private val project: Project) {

    private val log = Logger.getInstance(CodyAgentClient::class.java)

    val editListeners = CopyOnWriteArrayList<(WorkspaceEditParams) -> Boolean>()
    val editTaskStateListeners = CopyOnWriteArrayList<(EditTask) -> Unit>()

    @JsonRequest("workspace/edit")
    fun workspaceEdit(params: WorkspaceEditParams): CompletableFuture<Boolean> {
        log.info("Received workspace/edit with ${params.operations.size} operations")
        return CompletableFuture.supplyAsync {
            editListeners.any { listener -> listener(params) }
        }
    }

    @JsonRequest("textDocument/edit")
    fun textDocumentEdit(params: TextDocumentEditParams): CompletableFuture<Boolean> {
        log.info("Received textDocument/edit for ${params.uri}")
        val asWorkspaceEdit = WorkspaceEditParams(
            operations = listOf(
                WorkspaceEditOperation(
                    type = "edit-file",
                    uri = params.uri,
                    edits = params.edits
                )
            )
        )
        return workspaceEdit(asWorkspaceEdit)
    }

    @JsonNotification("editTaskState/didChange")
    fun editTaskStateDidChange(params: EditTask) {
        log.info("Edit task ${params.id} state changed to ${params.state}")
        editTaskStateListeners.forEach { it(params) }
    }

    @JsonNotification("progress/start")
    fun progressStart(params: ProgressStartParams) {
        log.debug("Progress start: ${params.id} — ${params.options?.title}")
    }

    @JsonNotification("progress/report")
    fun progressReport(params: ProgressReportParams) {
        log.debug("Progress: ${params.id} — ${params.message}")
    }

    @JsonNotification("progress/end")
    fun progressEnd(params: ProgressEndParams) {
        log.debug("Progress end: ${params.id}")
    }

    @JsonRequest("window/showMessage")
    fun windowShowMessage(params: ShowMessageParams): CompletableFuture<String?> {
        log.info("Agent message: ${params.message}")
        return CompletableFuture.completedFuture(null)
    }

    /**
     * Handler for editTask/getUserInput — the agent asks for edit instructions.
     * Auto-respond with the stored instruction (set by CodyEditService before requesting an edit).
     */
    @JsonRequest("editTask/getUserInput")
    fun editTaskGetUserInput(params: Any?): CompletableFuture<EditTaskUserInputResponse> {
        log.info("Agent requesting user input for edit task")
        val instruction = pendingEditInstruction ?: "Fix the issue"
        return CompletableFuture.completedFuture(
            EditTaskUserInputResponse(instruction = instruction)
        )
    }

    /** Set by CodyEditService before requesting an edit, consumed by editTask/getUserInput handler. */
    @Volatile
    var pendingEditInstruction: String? = null
}

data class EditTaskUserInputResponse(
    val instruction: String,
    val selectedModelId: String? = null
)
