package com.workflow.orchestrator.agent.ui

/**
 * Pure extension → MIME-type fallback for chat attachments (Phase 3 cut — extracted from
 * `AgentController.inferMimeType`). [java.nio.file.Files.probeContentType] returns null on
 * macOS/JBR for common types (PNG, JPEG, PDF, …); a null would route an image as a plain file and
 * miss the vision path. The caller probes first and falls back to [fromExtension] on a null/blank
 * result. Dependency-free so the routing-critical mapping is unit-testable.
 */
object AttachmentMimeTypes {

    /** Map a file extension (case-insensitive) to a MIME type; unknown → `application/octet-stream`. */
    fun fromExtension(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "application/yaml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "rtf" -> "application/rtf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "epub" -> "application/epub+zip"
        else -> "application/octet-stream"
    }
}
