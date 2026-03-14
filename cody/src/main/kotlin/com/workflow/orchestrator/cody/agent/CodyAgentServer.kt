package com.workflow.orchestrator.cody.agent

import com.workflow.orchestrator.cody.protocol.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

interface CodyAgentServer {

    @JsonRequest("initialize")
    fun initialize(params: ClientInfo): CompletableFuture<ServerInfo>

    @JsonNotification("initialized")
    fun initialized(params: Void? = null)

    @JsonRequest("shutdown")
    fun shutdown(params: Void? = null): CompletableFuture<Unit?>

    @JsonNotification("exit")
    fun exit(params: Void? = null)

    @JsonRequest("editCommands/code")
    fun editCommandsCode(params: EditCommandsCodeParams): CompletableFuture<EditTask>

    @JsonRequest("editTask/accept")
    fun editTaskAccept(params: EditTaskParams): CompletableFuture<Unit?>

    @JsonRequest("editTask/undo")
    fun editTaskUndo(params: EditTaskParams): CompletableFuture<Unit?>

    @JsonRequest("editTask/cancel")
    fun editTaskCancel(params: EditTaskParams): CompletableFuture<Unit?>

    @JsonRequest("chat/new")
    fun chatNew(params: Void? = null): CompletableFuture<String>

    @JsonRequest("chat/submitMessage")
    fun chatSubmitMessage(params: ChatSubmitParams): CompletableFuture<ChatResponse>

    @JsonNotification("textDocument/didOpen")
    fun textDocumentDidOpen(params: ProtocolTextDocument)

    @JsonNotification("textDocument/didChange")
    fun textDocumentDidChange(params: ProtocolTextDocument)

    @JsonNotification("textDocument/didFocus")
    fun textDocumentDidFocus(params: TextDocumentIdentifier)

    @JsonNotification("textDocument/didClose")
    fun textDocumentDidClose(params: ProtocolTextDocument)

    @JsonRequest("codeActions/provide")
    fun codeActionsProvide(params: CodeActionsProvideParams): CompletableFuture<CodeActionsProvideResult>

    @JsonRequest("codeActions/trigger")
    fun codeActionsTrigger(params: CodeActionsTriggerParams): CompletableFuture<EditTask>

    @JsonRequest("commands/test")
    fun commandsTest(params: Void? = null): CompletableFuture<EditTask>

    @JsonRequest("command/execute")
    fun commandExecute(params: ExecuteCommandParams): CompletableFuture<Any>
}
