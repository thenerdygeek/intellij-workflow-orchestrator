package com.workflow.orchestrator.agent.util

/**
 * Shared string utilities for the agent module.
 */
object AgentStringUtils {

    /** Extracts path from JSON tool args like {"path": "..."} -- matches path, file_path, file keys */
    val JSON_FILE_PATH_REGEX = Regex(""""(?:path|file_path|file)"\s*:\s*"([^"]+)"""")
}
