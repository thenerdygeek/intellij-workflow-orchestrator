package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject

/**
 * Tools implement this to support launching into BackgroundPool. v1 implementer:
 * RunCommandTool. Designed so future tools (build, test runners, bamboo trigger)
 * can slot in without BackgroundPool changing.
 */
interface BackgroundCapable {
    /**
     * Launch a new background unit for [sessionId] with tool-specific [params].
     * Implementation is responsible for spawning, pre-spawn validation, and
     * registering its handle in the pool via BackgroundPool.register().
     *
     * @return the new bgId, or null if pre-spawn validation failed (caller handles
     *         the user-facing error separately).
     */
    suspend fun launchBackground(
        sessionId: String,
        params: JsonObject,
        project: Project,
    ): String?
}
