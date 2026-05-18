package com.workflow.orchestrator.agent.checkpoint

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SessionCheckpointStore(private val sessionDir: File) {

    private val checkpointsDir: File = File(sessionDir, "checkpoints").apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun beginUserMessage(messageTs: Long, userText: String) {
        val dir = msgDir(messageTs).apply { mkdirs() }
        val meta = CheckpointMeta(
            messageTs = messageTs,
            userText = userText,
            createdAt = System.currentTimeMillis(),
        )
        File(dir, "meta.json").writeText(json.encodeToString(meta))
        File(dir, "files").mkdirs()
    }

    fun listMessageCheckpoints(): List<CheckpointMeta> {
        val dirs = checkpointsDir.listFiles { f -> f.isDirectory && f.name.startsWith("msg-") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val metaFile = File(d, "meta.json")
            if (!metaFile.exists()) null
            else try { json.decodeFromString<CheckpointMeta>(metaFile.readText()) } catch (_: Exception) { null }
        }.sortedBy { it.messageTs }
    }

    private fun msgDir(messageTs: Long): File = File(checkpointsDir, "msg-$messageTs")
}
