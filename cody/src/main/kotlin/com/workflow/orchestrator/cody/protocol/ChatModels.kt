package com.workflow.orchestrator.cody.protocol

data class ChatSubmitParams(
    val id: String,
    val message: ChatMessage
)

data class ChatMessage(
    val command: String = "submit",
    val text: String,
    val submitType: String = "user",
    val addEnhancedContext: Boolean = true,
    val contextFiles: List<ContextFile> = emptyList()
)

data class ChatResponse(
    val type: String = "",
    val messages: List<TranscriptMessage> = emptyList()
)

data class TranscriptMessage(
    val speaker: String = "",
    val text: String? = null
)

data class ContextFile(
    val uri: String,
    val range: Range? = null
)
