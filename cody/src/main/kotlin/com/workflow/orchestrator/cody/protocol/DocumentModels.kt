package com.workflow.orchestrator.cody.protocol

data class ProtocolTextDocument(
    val uri: String,
    val content: String? = null,
    val selection: Range? = null,
    val visibleRange: Range? = null
)

data class TextDocumentIdentifier(
    val uri: String
)
