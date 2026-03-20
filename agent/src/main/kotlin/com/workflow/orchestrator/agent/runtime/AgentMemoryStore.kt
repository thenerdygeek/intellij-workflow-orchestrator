package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Cross-session memory store that persists markdown files in the project directory.
 * Memory directory: {projectBasePath}/.workflow/agent/memory/
 *
 * Files:
 * - MEMORY.md — index file listing all memories with links and descriptions
 * - {topic}.md — individual memory files with "# {topic}" header + content
 */
class AgentMemoryStore(private val projectBasePath: File) {

    companion object {
        private val LOG = Logger.getInstance(AgentMemoryStore::class.java)
        private const val MEMORY_DIR = ".workflow/agent/memory"
        private const val INDEX_FILE = "MEMORY.md"
    }

    private val memoryDir: File
        get() = File(projectBasePath, MEMORY_DIR)

    /**
     * Save a memory under the given topic. Creates or overwrites the topic file
     * and rebuilds the index.
     */
    fun saveMemory(topic: String, content: String) {
        try {
            ensureDir()
            val filename = sanitizeTopic(topic)
            val file = File(memoryDir, "$filename.md")
            if (!file.canonicalPath.startsWith(memoryDir.canonicalPath)) {
                LOG.warn("AgentMemoryStore: path traversal blocked for topic '$topic'")
                return
            }
            file.writeText("# $topic\n\n$content\n")
            LOG.info("AgentMemoryStore: saved memory '$topic' -> ${file.name}")
            rebuildIndex()
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to save memory '$topic'", e)
        }
    }

    /**
     * Delete a memory by topic name. Removes the file and rebuilds the index.
     */
    fun deleteMemory(topic: String) {
        try {
            val filename = sanitizeTopic(topic)
            val file = File(memoryDir, "$filename.md")
            if (!file.canonicalPath.startsWith(memoryDir.canonicalPath)) {
                LOG.warn("AgentMemoryStore: path traversal blocked for topic '$topic'")
                return
            }
            if (file.exists()) {
                file.delete()
                LOG.info("AgentMemoryStore: deleted memory '$topic'")
                rebuildIndex()
            }
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to delete memory '$topic'", e)
        }
    }

    /**
     * List all memory topic names (filename without .md extension, excluding MEMORY.md).
     */
    fun listMemories(): List<String> {
        return try {
            if (!memoryDir.exists()) return emptyList()
            memoryDir.listFiles()
                ?.filter { it.name.endsWith(".md") && it.name != INDEX_FILE }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to list memories", e)
            emptyList()
        }
    }

    /**
     * Load memories for system prompt injection.
     *
     * @param maxLines Maximum number of lines to include
     * @return Formatted string with index + inline topic contents, or null if no memories exist
     */
    fun loadMemories(maxLines: Int = 200): String? {
        return try {
            val topics = listMemories()
            if (topics.isEmpty()) return null

            val indexFile = File(memoryDir, INDEX_FILE)
            val result = StringBuilder()
            var linesUsed = 0

            // Read index first
            if (indexFile.exists()) {
                val indexLines = indexFile.readLines()
                for (line in indexLines) {
                    if (linesUsed >= maxLines) break
                    result.appendLine(line)
                    linesUsed++
                }
            }

            // Append inline topic contents (most recent first)
            for (topic in topics) {
                if (linesUsed >= maxLines) break
                val file = File(memoryDir, "$topic.md")
                if (!file.exists()) continue

                result.appendLine()
                linesUsed++

                val lines = file.readLines()
                for (line in lines) {
                    if (linesUsed >= maxLines) break
                    result.appendLine(line)
                    linesUsed++
                }
            }

            result.toString().trimEnd()
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to load memories", e)
            null
        }
    }

    /**
     * Rebuild the MEMORY.md index file from all topic files.
     * Lists all .md files except MEMORY.md, sorted by last modified (most recent first).
     */
    private fun rebuildIndex() {
        try {
            ensureDir()
            val files = memoryDir.listFiles()
                ?.filter { it.name.endsWith(".md") && it.name != INDEX_FILE }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val sb = StringBuilder("# Agent Memory\n")
            for (file in files) {
                val topic = file.nameWithoutExtension
                val firstContentLine = file.readLines()
                    .drop(1) // skip "# topic" header
                    .firstOrNull { it.isNotBlank() }
                    ?: ""
                sb.appendLine("- [$topic]($topic.md) — $firstContentLine")
            }

            File(memoryDir, INDEX_FILE).writeText(sb.toString())
        } catch (e: Exception) {
            LOG.warn("AgentMemoryStore: failed to rebuild index", e)
        }
    }

    /**
     * Sanitize a topic name to a safe filename: lowercase, replace non-alphanumeric with hyphens,
     * collapse consecutive hyphens, trim leading/trailing hyphens.
     */
    internal fun sanitizeTopic(topic: String): String {
        return topic.lowercase()
            .replace(Regex("[^a-z0-9]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun ensureDir() {
        if (!memoryDir.exists()) {
            memoryDir.mkdirs()
        }
    }
}
