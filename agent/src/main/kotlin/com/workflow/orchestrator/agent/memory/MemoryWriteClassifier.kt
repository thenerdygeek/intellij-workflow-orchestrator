// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure classifier: is a tool call a WRITE into the agent's file-based memory dir?
 * Used by [com.workflow.orchestrator.agent.loop.AgentLoop] to gate memory writes
 * per-invocation. Reads are never memory writes. OS-independent string match
 * (normalizes separators) so it is testable cross-platform and matches the runtime
 * OS's own separators.
 */
object MemoryWriteClassifier {
    private val MEMORY_WRITE_TOOLS = setOf("create_file", "edit_file", "delete_file")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isMemoryWrite(toolName: String, argsJson: String, memoryDir: String?): Boolean {
        if (toolName !in MEMORY_WRITE_TOOLS || memoryDir.isNullOrBlank()) return false
        val path = extractPath(argsJson) ?: return false
        val pn = path.replace('\\', '/').trimEnd('/')
        val dn = memoryDir.replace('\\', '/').trimEnd('/')
        return pn == dn || pn.startsWith("$dn/")
    }

    private fun extractPath(argsJson: String): String? = try {
        val obj = json.parseToJsonElement(argsJson) as? JsonObject ?: return null
        (obj["path"] ?: obj["file_path"])?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
}
