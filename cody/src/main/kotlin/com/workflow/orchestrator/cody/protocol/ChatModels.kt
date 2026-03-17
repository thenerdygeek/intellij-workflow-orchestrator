package com.workflow.orchestrator.cody.protocol

data class ChatSubmitParams(
    val id: String,
    val message: ChatMessage
)

data class ChatMessage(
    val command: String = "submit",
    val text: String,
    val submitType: String = "user",
    val addEnhancedContext: Boolean = false,
    val contextItems: List<ContextFile> = emptyList()
)

data class ChatResponse(
    val type: String = "",
    val messages: List<TranscriptMessage> = emptyList()
)

data class TranscriptMessage(
    val speaker: String = "",
    val text: String? = null
)

/**
 * Context file reference passed to the Cody agent.
 *
 * The agent expects `uri` as an object with `fsPath` and `path` fields
 * (matching VS Code's Uri structure), NOT a plain string.
 * See codypy reference: `Context(type="file", uri=Uri(fsPath=path, path=path))`
 */
data class ContextFile(
    val type: String = "file",
    val uri: ContextFileUri,
    val range: Range? = null
) {
    companion object {
        /**
         * Convenience factory: creates a ContextFile from an absolute file path.
         *
         * Both `fsPath` and `path` must be the raw absolute path (NOT file:// URI).
         * The `scheme` field already indicates the URI type.
         * Matches the reference implementations (cody_agentic_tool, codypy).
         */
        fun fromPath(path: String, range: Range? = null): ContextFile {
            val absPath = if (path.startsWith("file://")) path.removePrefix("file://") else path
            return ContextFile(
                uri = ContextFileUri(fsPath = absPath, path = absPath),
                range = range
            )
        }
    }
}

data class ContextFileUri(
    val scheme: String = "file",
    val fsPath: String,
    val path: String
)
