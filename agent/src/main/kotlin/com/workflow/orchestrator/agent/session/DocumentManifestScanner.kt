package com.workflow.orchestrator.agent.session

import java.nio.file.Files
import java.nio.file.Path

/** A readable document the agent has been handed this session, with its exact path. */
data class SessionDocument(
    val displayName: String,
    val absolutePath: String,
)

/**
 * Stateless enumeration of readable documents in a session directory — user attachments
 * and tool-downloaded artifacts — so their EXACT paths can be surfaced to the LLM and
 * survive context compaction. Derived from the filesystem (ground truth), not a registry.
 */
object DocumentManifestScanner {

    private val SHA_PREFIX = Regex("^[0-9a-fA-F]{8}-")
    private val IMAGE_DIR = Regex("^document-[0-9a-fA-F]{6}$")
    private val EXCLUDED_DOWNLOAD_DIRS = setOf("document-cache", "tool-output")
    private const val MAX_DOCS = 60

    fun scan(sessionDir: Path): List<SessionDocument> {
        val out = LinkedHashMap<String, SessionDocument>() // keyed by absolutePath, dedup
        scanAttachments(sessionDir.resolve("attachments").resolve("files"), out)
        scanDownloads(sessionDir.resolve("downloads"), out)
        return out.values.sortedBy { it.displayName.lowercase() }.take(MAX_DOCS)
    }

    private fun scanAttachments(filesDir: Path, out: MutableMap<String, SessionDocument>) {
        if (!Files.isDirectory(filesDir)) return
        Files.newDirectoryStream(filesDir).use { stream ->
            for (p in stream) {
                if (!Files.isRegularFile(p)) continue
                val name = p.fileName.toString()
                if (name.contains(".tmp.") || name.endsWith(".tmp")) continue
                val display = name.replaceFirst(SHA_PREFIX, "")
                val abs = p.toAbsolutePath().toString()
                out[abs] = SessionDocument(display, abs)
            }
        }
    }

    private fun scanDownloads(downloadsDir: Path, out: MutableMap<String, SessionDocument>) {
        if (!Files.isDirectory(downloadsDir)) return
        Files.newDirectoryStream(downloadsDir).use { stream ->
            for (sub in stream) {
                if (!Files.isDirectory(sub)) {
                    // a loose file directly under downloads/ — include it
                    if (Files.isRegularFile(sub)) {
                        val abs = sub.toAbsolutePath().toString()
                        out[abs] = SessionDocument(sub.fileName.toString(), abs)
                    }
                    continue
                }
                val dirName = sub.fileName.toString()
                if (dirName in EXCLUDED_DOWNLOAD_DIRS || IMAGE_DIR.matches(dirName)) continue
                // include regular files within this source dir (one level; recurse shallowly)
                Files.walk(sub, 3).use { walk ->
                    walk.filter { Files.isRegularFile(it) }.forEach { f ->
                        val name = f.fileName.toString()
                        if (name.contains(".tmp.") || name.endsWith(".tmp")) return@forEach
                        val abs = f.toAbsolutePath().toString()
                        out[abs] = SessionDocument(name, abs)
                    }
                }
            }
        }
    }
}
