package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object SessionMigrator {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val MIGRATION_VERSION = 3
    private const val MIGRATION_MARKER = ".migrated-v$MIGRATION_VERSION"

    fun migrate(baseDir: File) {
        val sessionsDir = File(baseDir, "sessions")
        if (!sessionsDir.exists()) return

        // Skip migration entirely if already completed
        val marker = File(sessionsDir, MIGRATION_MARKER)
        if (marker.exists()) return

        val historyItems = mutableListOf<HistoryItem>()

        // Find old-format sessions: those with messages.jsonl but no api_conversation_history.json
        sessionsDir.listFiles { f -> f.isDirectory }?.forEach { sessionDir ->
            val oldJsonl = File(sessionDir, "messages.jsonl")
            val newApiFile = File(sessionDir, "api_conversation_history.json")

            if (oldJsonl.exists() && !newApiFile.exists()) {
                migrateSession(baseDir, sessionDir, oldJsonl, historyItems)
            } else if (newApiFile.exists()) {
                // Already migrated — just add to index if not present
                addToIndex(baseDir, sessionDir, historyItems)
            }
        }

        // Write global index
        if (historyItems.isNotEmpty()) {
            val indexFile = File(baseDir, "sessions.json")
            val existing = try {
                if (indexFile.exists()) json.decodeFromString<List<HistoryItem>>(indexFile.readText())
                else emptyList()
            } catch (_: Exception) { emptyList() }

            val merged = (historyItems + existing).distinctBy { it.id }.sortedByDescending { it.ts }
            AtomicFileWriter.write(indexFile, json.encodeToString(merged))
        }

        // Mark migration as complete so subsequent startups skip this entirely
        try { marker.createNewFile() } catch (_: Exception) { /* best-effort */ }
    }

    private fun migrateSession(baseDir: File, sessionDir: File, oldJsonl: File, items: MutableList<HistoryItem>) {
        val oldMessages = try {
            oldJsonl.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                try { json.decodeFromString<ChatMessage>(line) } catch (_: Exception) { null }
            }
        } catch (_: Exception) { return }

        if (oldMessages.isEmpty()) return

        // Convert ChatMessages to ApiMessages
        val apiMessages = oldMessages.map { it.toApiMessage() }

        // Generate basic UI messages from API messages
        val uiMessages = apiMessages.mapIndexed { idx, apiMsg ->
            UiMessage(
                ts = System.currentTimeMillis() + idx, // approximate ordering
                type = UiMessageType.SAY,
                say = if (apiMsg.role == ApiRole.ASSISTANT) UiSay.TEXT else UiSay.TOOL,
                text = apiMsg.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
                conversationHistoryIndex = idx
            )
        }

        // Write new format files
        AtomicFileWriter.write(File(sessionDir, "api_conversation_history.json"), json.encodeToString(apiMessages))
        AtomicFileWriter.write(File(sessionDir, "ui_messages.json"), json.encodeToString(uiMessages))

        // Load old metadata if available
        val metaFile = File(sessionDir.parent, "${sessionDir.name}.json")
        val item = if (metaFile.exists()) {
            try {
                val oldSession = json.decodeFromString<OldSession>(metaFile.readText())
                HistoryItem(
                    id = oldSession.id,
                    ts = oldSession.lastMessageAt,
                    task = oldSession.title.ifBlank { uiMessages.firstOrNull()?.text?.take(100) ?: "Untitled" },
                    tokensIn = oldSession.inputTokens.toLong(),
                    tokensOut = oldSession.outputTokens.toLong(),
                    modelId = null
                )
            } catch (_: Exception) { null }
        } else null

        if (item != null) items.add(item)
        metaFile.delete()
    }

    private fun addToIndex(baseDir: File, sessionDir: File, items: MutableList<HistoryItem>) {
        // Clean up any leftover old-format sibling metadata file (migration v2 artifact)
        File(sessionDir.parent, "${sessionDir.name}.json").delete()
        // Read existing api_history to build a HistoryItem
        val apiHistory = MessageStateHandler.loadApiHistory(sessionDir)
        if (apiHistory.isEmpty()) return
        items.add(HistoryItem(
            id = sessionDir.name,
            ts = apiHistory.lastOrNull()?.ts ?: System.currentTimeMillis(),
            task = apiHistory.firstOrNull()?.content?.filterIsInstance<ContentBlock.Text>()?.firstOrNull()?.text?.take(100) ?: "Session"
        ))
    }

    @kotlinx.serialization.Serializable
    private data class OldSession(
        val id: String,
        val title: String = "",
        val createdAt: Long = 0,
        val lastMessageAt: Long = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
    )
}
