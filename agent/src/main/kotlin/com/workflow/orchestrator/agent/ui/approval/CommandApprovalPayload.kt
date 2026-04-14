package com.workflow.orchestrator.agent.ui.approval

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.process.ProcessEnvironment
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Build the structured `commandPreview` JSON payload surfaced to the approval UI
 * for `run_command` tool calls.
 *
 * The payload contains the exact command the LLM proposed, the resolved shell,
 * working directory, and any LLM-provided env vars after filtering through the
 * same blocklist the real spawn path uses ([ProcessEnvironment.filterUserEnv]).
 *
 * Pure — no IntelliJ services touched beyond `project.basePath` / the public
 * `ShellResolver` / `ProcessEnvironment` helpers.
 */
object CommandApprovalPayload {

    data class Result(
        val description: String,
        val commandPreviewJson: String,
    )

    /**
     * @param parsedArgs the parsed `run_command` arguments from the LLM.
     * @param project    the active project, used for cwd fallback and shell settings.
     */
    fun build(parsedArgs: JsonObject?, project: Project?): Result {
        val command = parsedArgs?.get("command")?.jsonPrimitive?.content ?: "unknown"
        val requestedShell = parsedArgs?.get("shell")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val resolvedShell = try {
            ShellResolver.resolve(requestedShell, project).executable
        } catch (_: Throwable) {
            requestedShell ?: "/bin/sh"
        }
        val cwd = parsedArgs?.get("cwd")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: project?.basePath
            ?: "."
        val separateStderr = parsedArgs?.get("separate_stderr")?.jsonPrimitive?.booleanOrNull ?: false

        val rawEnv: Map<String, String> = parsedArgs?.get("env")?.let { el ->
            runCatching {
                el.jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val (allowedEnv, _) = ProcessEnvironment.filterUserEnv(rawEnv)

        val cmdDesc = parsedArgs?.get("description")?.jsonPrimitive?.content
        val description = cmdDesc ?: "Run command"

        val json = buildJsonObject {
            put("command", command)
            put("shell", resolvedShell)
            put("cwd", cwd)
            put("separateStderr", separateStderr)
            put(
                "env",
                buildJsonArray {
                    allowedEnv.forEach { (k, v) ->
                        addJsonObject {
                            put("key", k)
                            put("value", v)
                        }
                    }
                },
            )
        }
        return Result(description, Json.encodeToString(JsonObject.serializer(), json))
    }
}
