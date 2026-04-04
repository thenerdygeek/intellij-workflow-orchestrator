package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Simple JSON file persistence for sessions.
 * One file per session: {baseDir}/sessions/{sessionId}.json
 */
class SessionStore(private val baseDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sessionsDir: File get() = File(baseDir, "sessions")

    fun save(session: Session) {
        sessionsDir.mkdirs()
        val file = File(sessionsDir, "${session.id}.json")
        file.writeText(json.encodeToString(session))
    }

    fun load(sessionId: String): Session? {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Session>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<Session> {
        if (!sessionsDir.exists()) return emptyList()
        return sessionsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    json.decodeFromString<Session>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
}
