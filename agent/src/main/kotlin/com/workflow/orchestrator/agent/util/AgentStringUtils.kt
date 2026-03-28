package com.workflow.orchestrator.agent.util

/**
 * Shared string utilities for the agent module.
 *
 * Centralizes patterns that recur across context management, runtime,
 * and UI layers: external-data tag stripping and file-path extraction.
 */
object AgentStringUtils {

    /**
     * Strip `<external_data>` wrapper tags from tool result content.
     * Handles both `\n`-padded and tight variants.
     */
    fun unwrapExternalData(content: String): String =
        content.removePrefix("<external_data>\n").removeSuffix("\n</external_data>")
            .removePrefix("<external_data>").removeSuffix("</external_data>")

    /** Matches file paths in free text (e.g., "src/main/Foo.kt") */
    val FILE_PATH_REGEX = Regex("""[\w./\\-]+\.\w{1,10}""")

    /** Extracts path from JSON tool args like {"path": "..."} -- matches path, file_path, file keys */
    val JSON_FILE_PATH_REGEX = Regex(""""(?:path|file_path|file)"\s*:\s*"([^"]+)"""")
}
