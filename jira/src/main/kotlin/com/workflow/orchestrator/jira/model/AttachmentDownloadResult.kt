package com.workflow.orchestrator.jira.model

import java.io.File

data class AttachmentDownloadResult(
    val file: File,
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val attachmentId: String,
    val sourceUrl: String
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
    val isText: Boolean get() = mimeType?.let {
        it.startsWith("text/") || it in listOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/x-sh"
        )
    } ?: false
}
