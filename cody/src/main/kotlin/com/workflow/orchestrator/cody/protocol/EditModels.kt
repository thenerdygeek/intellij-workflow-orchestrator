package com.workflow.orchestrator.cody.protocol

// --- Edit Commands ---

data class EditCommandsCodeParams(
    val instruction: String,
    val model: String? = null,
    val mode: String = "edit",
    val range: Range? = null,
    val contextFiles: List<ContextFile>? = null
)

data class EditTask(
    val id: String = "",
    val state: String = "Idle",
    val error: CodyError? = null,
    val selectionRange: Range? = null,
    val instruction: String? = null
)

data class CodyError(
    val message: String = "",
    val code: Int? = null
)

data class EditTaskParams(
    val id: String
)

// --- Code Actions ---

data class CodeActionsProvideParams(
    val location: ProtocolLocation,
    val triggerKind: String = "Invoke"
)

data class ProtocolLocation(
    val uri: String,
    val range: Range
)

data class CodeActionsProvideResult(
    val codeActions: List<ProtocolCodeAction> = emptyList()
)

data class ProtocolCodeAction(
    val id: String? = null,
    val commandID: String? = null,
    val title: String = "",
    val diagnostics: List<ProtocolDiagnostic>? = null
)

data class ProtocolDiagnostic(
    val range: Range,
    val message: String = "",
    val severity: String? = null,
    val source: String? = null
)

data class CodeActionsTriggerParams(
    val id: String
)

// --- Commands ---

data class ExecuteCommandParams(
    val command: String,
    val arguments: List<Any>? = null
)

// --- Workspace Edit (server -> client) ---

data class WorkspaceEditParams(
    val operations: List<WorkspaceEditOperation> = emptyList(),
    val metadata: WorkspaceEditMetadata? = null
)

data class WorkspaceEditMetadata(
    val isRestricted: Boolean? = null
)

data class WorkspaceEditOperation(
    val type: String = "",
    val uri: String? = null,
    val textContents: String? = null,
    val edits: List<TextEdit>? = null
)

data class TextEdit(
    val type: String = "",
    val range: Range? = null,
    val value: String? = null,
    val position: Position? = null
)

data class TextDocumentEditParams(
    val uri: String,
    val edits: List<TextEdit> = emptyList()
)

// --- Shared geometry ---

data class Range(
    val start: Position = Position(),
    val end: Position = Position()
)

data class Position(
    val line: Int = 0,
    val character: Int = 0
)
