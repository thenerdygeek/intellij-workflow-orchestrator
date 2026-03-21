package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Builds rich context from @ mentions.
 * Reads file content via IntelliJ Document API (sees unsaved changes),
 * generates directory trees, and assembles a context string for the LLM.
 *
 * Token budget: Each file is capped at 500 lines / 20K chars.
 * Total mention context is capped at 50K chars (~12.5K tokens).
 */
class MentionContextBuilder(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(MentionContextBuilder::class.java)
        private const val MAX_FILE_LINES = 500
        private const val MAX_FILE_CHARS = 20_000
        private const val MAX_TOTAL_CHARS = 50_000
        private const val MAX_TREE_DEPTH = 4
        private const val MAX_TREE_ENTRIES = 100
    }

    data class Mention(
        val type: String,   // "file", "symbol", "tool", "skill", "folder"
        val name: String,   // Display name
        val value: String   // Path or identifier
    )

    /**
     * Build a context string from a list of mentions.
     * Returns XML-tagged context suitable for injection as a system message.
     */
    fun buildContext(mentions: List<Mention>): String? {
        if (mentions.isEmpty()) return null
        val basePath = project.basePath ?: return null

        val sb = StringBuilder()
        var totalChars = 0

        for (mention in mentions) {
            if (totalChars >= MAX_TOTAL_CHARS) {
                sb.appendLine("\n[Mention context truncated — ${MAX_TOTAL_CHARS / 1000}K char budget reached]")
                break
            }

            val section = when (mention.type) {
                "file" -> buildFileContext(mention, basePath)
                "folder" -> buildFolderContext(mention, basePath)
                "symbol" -> buildSymbolContext(mention)
                "tool" -> buildToolContext(mention)
                "skill" -> buildSkillContext(mention)
                else -> null
            } ?: continue

            val remaining = MAX_TOTAL_CHARS - totalChars
            val truncated = if (section.length > remaining) {
                section.take(remaining) + "\n[truncated]"
            } else section

            sb.append(truncated)
            totalChars += truncated.length
        }

        return if (sb.isNotBlank()) sb.toString() else null
    }

    private fun buildFileContext(mention: Mention, basePath: String): String {
        val fullPath = if (mention.value.startsWith("/")) mention.value
                       else "$basePath/${mention.value}"
        val file = File(fullPath)
        if (!file.isFile) return "<mentioned_file path=\"${mention.value}\">\nFile not found.\n</mentioned_file>\n\n"

        // Try IntelliJ Document API first (sees unsaved changes), fall back to java.io
        val content = try {
            val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
            if (vf != null) {
                val doc = ReadAction.compute<Document?, Exception> {
                    FileDocumentManager.getInstance().getDocument(vf)
                }
                doc?.text ?: vf.contentsToByteArray().toString(Charsets.UTF_8)
            } else {
                file.readText()
            }
        } catch (e: Exception) {
            // IntelliJ APIs unavailable (e.g. in unit tests) — fall back to plain file I/O
            try {
                file.readText()
            } catch (io: Exception) {
                return "<mentioned_file path=\"${mention.value}\">\nError reading file: ${io.message}\n</mentioned_file>\n\n"
            }
        }

        // Truncate large files
        val lines = content.lines()
        val truncatedContent = if (lines.size > MAX_FILE_LINES) {
            lines.take(MAX_FILE_LINES).joinToString("\n") + "\n\n[File truncated at $MAX_FILE_LINES lines — ${lines.size} total]"
        } else if (content.length > MAX_FILE_CHARS) {
            content.take(MAX_FILE_CHARS) + "\n\n[File truncated at ${MAX_FILE_CHARS / 1000}K chars]"
        } else content

        return "<mentioned_file path=\"${mention.value}\" lines=\"${lines.size}\">\n$truncatedContent\n</mentioned_file>\n\n"
    }

    private fun buildFolderContext(mention: Mention, basePath: String): String {
        val fullPath = if (mention.value.startsWith("/")) mention.value
                       else "$basePath/${mention.value}"
        val dir = File(fullPath)
        if (!dir.isDirectory) return "<mentioned_folder path=\"${mention.value}\">\nDirectory not found.\n</mentioned_folder>\n\n"

        val tree = buildTree(dir, "", 0)
        return "<mentioned_folder path=\"${mention.value}\">\n$tree</mentioned_folder>\n\n"
    }

    private fun buildTree(dir: File, prefix: String, depth: Int): String {
        if (depth > MAX_TREE_DEPTH) return "${prefix}...\n"
        val sb = StringBuilder()
        var count = 0
        val entries = dir.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name != "node_modules" && it.name != "build" && it.name != "out" && it.name != "__pycache__" }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?: return ""

        for (entry in entries) {
            if (count >= MAX_TREE_ENTRIES) {
                sb.appendLine("${prefix}... (${entries.size - count} more entries)")
                break
            }
            if (entry.isDirectory) {
                sb.appendLine("${prefix}${entry.name}/")
                sb.append(buildTree(entry, "$prefix  ", depth + 1))
            } else {
                sb.appendLine("${prefix}${entry.name}")
            }
            count++
        }
        return sb.toString()
    }

    private fun buildSymbolContext(mention: Mention): String {
        return "<mentioned_symbol name=\"${mention.name}\" qualified=\"${mention.value}\">\nSymbol: ${mention.value}\nThe user is referencing this symbol. Use find_definition or find_references to explore it.\n</mentioned_symbol>\n\n"
    }

    private fun buildToolContext(mention: Mention): String {
        return "<mentioned_tool name=\"${mention.value}\">\nThe user wants you to use the ${mention.value} tool.\n</mentioned_tool>\n\n"
    }

    private fun buildSkillContext(mention: Mention): String {
        return "<mentioned_skill name=\"${mention.value}\">\nThe user wants you to activate the /${mention.value} skill.\n</mentioned_skill>\n\n"
    }
}
