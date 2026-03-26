package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.CoreMemory
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Read the current core memory block.
 * Core memory is always in the system prompt — this tool lets the agent inspect it explicitly.
 */
class CoreMemoryReadTool : AgentTool {
    override val name = "core_memory_read"
    override val description = "Read the current core memory. Core memory is a small (4KB) block always present in your system prompt containing project context, user preferences, and active constraints. Use this to see what you currently remember."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val coreMemory = CoreMemory.forProject(basePath)
        val content = coreMemory.render()

        return if (content != null) {
            val capacity = coreMemory.remainingCapacity()
            ToolResult(
                content = "Core Memory (${coreMemory.entryCount()} entries, ${capacity} chars remaining):\n\n$content",
                summary = "Core memory: ${coreMemory.entryCount()} entries",
                tokenEstimate = content.length / 4
            )
        } else {
            ToolResult(
                content = "Core memory is empty. Use core_memory_append to add entries.",
                summary = "Core memory: empty",
                tokenEstimate = 5
            )
        }
    }
}

/**
 * Add or update an entry in core memory.
 * Core memory is small (4KB) — use for frequently-needed project context.
 */
class CoreMemoryAppendTool : AgentTool {
    override val name = "core_memory_append"
    override val description = "Add or update an entry in core memory. Core memory is a small (4KB) block always present in your system prompt. Use for project-specific context you need on every turn: build system quirks, key file paths, user preferences, active constraints. For long-term knowledge, use archival_memory_insert instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "Short key for the entry (e.g., 'build-system', 'test-db', 'user-pref')"),
            "value" to ParameterProperty(type = "string", description = "The value to store. Keep concise — core memory is limited to 4KB total.")
        ),
        required = listOf("key", "value")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val value = params["value"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'value' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val coreMemory = CoreMemory.forProject(basePath)
        val error = coreMemory.append(key, value)

        return if (error != null) {
            ToolResult(error, "Core memory error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } else {
            ToolResult(
                content = "Core memory updated: [$key] = $value (${coreMemory.remainingCapacity()} chars remaining)",
                summary = "Updated core memory: $key",
                tokenEstimate = 5
            )
        }
    }
}

/**
 * Replace an existing core memory entry.
 */
class CoreMemoryReplaceTool : AgentTool {
    override val name = "core_memory_replace"
    override val description = "Replace an existing entry in core memory with a new value, or remove it entirely. Use to update stale information or free up space."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "key" to ParameterProperty(type = "string", description = "The key of the entry to replace"),
            "new_value" to ParameterProperty(type = "string", description = "The new value. Omit or set to empty string to delete the entry.")
        ),
        required = listOf("key")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val key = params["key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'key' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newValue = params["new_value"]?.jsonPrimitive?.content
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val coreMemory = CoreMemory.forProject(basePath)

        if (newValue.isNullOrBlank()) {
            val removed = coreMemory.remove(key)
            return if (removed) {
                ToolResult("Removed '$key' from core memory. ${coreMemory.remainingCapacity()} chars remaining.", "Removed core memory: $key", 5)
            } else {
                ToolResult("Key '$key' not found in core memory", "Key not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }

        val error = coreMemory.replace(key, newValue)
        return if (error != null) {
            ToolResult(error, "Core memory error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } else {
            ToolResult(
                content = "Core memory replaced: [$key] = $newValue",
                summary = "Replaced core memory: $key",
                tokenEstimate = 5
            )
        }
    }
}
