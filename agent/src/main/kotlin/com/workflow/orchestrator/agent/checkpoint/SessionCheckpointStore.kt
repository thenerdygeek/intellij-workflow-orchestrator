package com.workflow.orchestrator.agent.checkpoint

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.agent.security.CredentialRedactor
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

class SessionCheckpointStore(
    private val sessionDir: File,
    /**
     * Canonical project root used to validate revert paths (E3).
     * Every path restored during revert must live under this root.
     * Null disables the check (legacy callers / tests that don't supply a project root).
     */
    private val projectRoot: File? = null,
) {

    private val log = Logger.getInstance(SessionCheckpointStore::class.java)

    private val checkpointsDir: File = File(sessionDir, "checkpoints").apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun beginUserMessage(messageTs: Long, userText: String) {
        val dir = msgDir(messageTs).apply { mkdirs() }
        // Redact credentials from the user-typed text before persisting to disk.
        // Users sometimes accidentally paste Bearer tokens or .env contents into the chat input;
        // those must not survive to the on-disk checkpoint (audit finding agent-runtime:F-6).
        val meta = CheckpointMeta(
            messageTs = messageTs,
            userText = CredentialRedactor.redact(userText),
            createdAt = System.currentTimeMillis(),
        )
        AtomicFileWriter.write(File(dir, "meta.json"), json.encodeToString(meta))
        File(dir, "files").mkdirs()
    }

    fun clear() {
        if (checkpointsDir.exists()) checkpointsDir.deleteRecursively()
        checkpointsDir.mkdirs()
    }

    fun listMessageCheckpoints(): List<CheckpointMeta> {
        val dirs = checkpointsDir.listFiles { f -> f.isDirectory && f.name.startsWith("msg-") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val metaFile = File(d, "meta.json")
            if (!metaFile.exists()) null
            else try { json.decodeFromString<CheckpointMeta>(metaFile.readText()) } catch (_: Exception) { null }
        }.sortedBy { it.messageTs }
    }

    fun captureIfFirstTouch(messageTs: Long, absolutePath: String) {
        val dir = msgDir(messageTs)
        if (!dir.exists()) return  // beginUserMessage was not called — defensive no-op
        val filesRoot = File(dir, "files")
        val srcFile = File(absolutePath)

        if (!srcFile.exists()) {
            // File does not exist yet — record as "created during this turn"
            updateMeta(messageTs) { meta ->
                if (absolutePath in meta.createdPaths) meta
                else meta.copy(createdPaths = meta.createdPaths + absolutePath)
            }
            return
        }

        // File exists — first-touch capture only
        val snapPath = File(filesRoot, snapshotRelative(absolutePath))
        if (snapPath.exists()) return  // already captured this turn

        snapPath.parentFile.mkdirs()
        srcFile.copyTo(snapPath, overwrite = false)

        updateMeta(messageTs) { meta ->
            if (absolutePath in meta.touchedPaths) meta
            else meta.copy(touchedPaths = meta.touchedPaths + absolutePath)
        }
    }

    private fun updateMeta(messageTs: Long, transform: (CheckpointMeta) -> CheckpointMeta) {
        val metaFile = File(msgDir(messageTs), "meta.json")
        val current = try {
            json.decodeFromString<CheckpointMeta>(metaFile.readText())
        } catch (_: Exception) {
            return
        }
        val updated = transform(current)
        AtomicFileWriter.write(metaFile, json.encodeToString(updated))
    }

    fun aggregateDiff(): AggregateDiff {
        val checkpoints = listMessageCheckpoints()  // sorted ascending
        if (checkpoints.isEmpty()) return AggregateDiff(0, 0, emptyList())

        // Build a map of absolutePath -> earliest checkpoint that touched/created it
        val earliestByPath = mutableMapOf<String, CheckpointMeta>()
        for (cp in checkpoints) {
            for (p in cp.touchedPaths) earliestByPath.putIfAbsent(p, cp)
            for (p in cp.createdPaths) earliestByPath.putIfAbsent(p, cp)
        }

        val files = earliestByPath.map { (path, cp) ->
            val isCreated = path in cp.createdPaths
            val currentFile = File(path)
            val current = if (currentFile.exists()) currentFile.readText() else ""
            val baseline = if (isCreated) {
                ""
            } else {
                File(File(checkpointsDir, "msg-${cp.messageTs}/files"), snapshotRelative(path))
                    .takeIf { it.exists() }?.readText() ?: ""
            }
            val (added, removed) = DiffCalculator.countDiff(baseline, current)
            val status = when {
                isCreated && currentFile.exists() -> FileStatus.CREATED
                !currentFile.exists() -> FileStatus.DELETED
                else -> FileStatus.MODIFIED
            }
            FileChange(path = path, added = added, removed = removed, status = status)
        }.sortedBy { it.path }

        return AggregateDiff(
            totalAdded = files.sumOf { it.added },
            totalRemoved = files.sumOf { it.removed },
            files = files,
        )
    }

    /**
     * Revert files to their state immediately BEFORE [targetMessageTs] was processed.
     *
     * For every checkpoint with ts >= targetMessageTs (i.e. the target message and all later),
     * any file touched there is restored to its earliest snapshot (within that range), and
     * any file created there is deleted.
     *
     * Truncation of conversation history is the caller's responsibility (AgentService).
     *
     * The msg-{ts} dirs for ts >= targetMessageTs are removed at the end.
     *
     * Returns the userText from the target checkpoint so the caller can push it back to the
     * chat input.
     */
    fun revertToMessage(targetMessageTs: Long): RevertResult {
        val all = listMessageCheckpoints()
        val target = all.firstOrNull { it.messageTs == targetMessageTs }
            ?: error("checkpoint for messageTs=$targetMessageTs not found")
        val rangeFromTarget = all.filter { it.messageTs >= targetMessageTs }

        // Build earliest-snapshot map across the to-be-reverted range
        val earliestTouchedByPath = mutableMapOf<String, CheckpointMeta>()
        val createdInRange = mutableSetOf<String>()
        for (cp in rangeFromTarget) {
            for (p in cp.touchedPaths) earliestTouchedByPath.putIfAbsent(p, cp)
            for (p in cp.createdPaths) createdInRange.add(p)
        }

        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // Delete created files — E3: validate path before deleting
        for (path in createdInRange) {
            if (!isSafeRevertPath(path)) {
                skipped.add(path)
                continue
            }
            if (File(path).exists() && File(path).delete()) deleted.add(path)
        }

        // Restore touched files (skip if also in createdInRange — already deleted)
        // E3: validate path before restoring
        for ((path, cp) in earliestTouchedByPath) {
            if (path in createdInRange) continue
            if (!isSafeRevertPath(path)) {
                skipped.add(path)
                continue
            }
            val snapFile = File(File(checkpointsDir, "msg-${cp.messageTs}/files"), snapshotRelative(path))
            if (!snapFile.exists()) continue
            val dst = File(path)
            dst.parentFile?.mkdirs()
            snapFile.copyTo(dst, overwrite = true)
            restored.add(path)
        }

        // Drop invalidated checkpoint dirs
        for (cp in rangeFromTarget) {
            File(checkpointsDir, "msg-${cp.messageTs}").deleteRecursively()
        }

        return RevertResult(
            userText = target.userText,
            restoredFiles = restored.sorted(),
            deletedFiles = deleted.sorted(),
            truncatedAtTs = targetMessageTs,
            skippedPaths = skipped.sorted(),
        )
    }

    /**
     * Restore a single file to its session baseline (the earliest snapshot, or delete if created).
     *
     * Does NOT modify the conversation history. Caller is responsible for pushing the
     * updated aggregate diff to the UI.
     *
     * @return true if the file was restored or deleted, false if the path is unknown to the store.
     */
    /**
     * Result of reverting a single file to its session baseline.
     *
     * [reverted] true → file was restored or deleted successfully.
     * [skipped] true → path was known but failed the E3 safety check (out-of-root / symlink).
     * Both false → path is unknown to the checkpoint store.
     */
    data class SingleFileRevertResult(
        val reverted: Boolean,
        val skipped: Boolean = false,
    )

    /**
     * Restore a single file to its session baseline (the earliest snapshot, or delete if created).
     *
     * E3: Validates the path before writing. Out-of-root or symlink paths are skipped and
     * surfaced via [SingleFileRevertResult.skipped] rather than silently swallowed.
     *
     * Does NOT modify the conversation history. Caller is responsible for pushing the
     * updated aggregate diff to the UI.
     *
     * @return [SingleFileRevertResult] — [reverted] = file was restored or deleted; [skipped] =
     *         known but blocked by E3 safety check; both false = path unknown to this store.
     */
    fun revertFileToBaseline(absolutePath: String): SingleFileRevertResult {
        // E3: validate before touching the filesystem
        if (!isSafeRevertPath(absolutePath)) {
            val all = listMessageCheckpoints()
            val isKnown = all.any { absolutePath in it.createdPaths || absolutePath in it.touchedPaths }
            return SingleFileRevertResult(reverted = false, skipped = isKnown)
        }

        val all = listMessageCheckpoints()
        for (cp in all) {
            if (absolutePath in cp.createdPaths) {
                // Created at this checkpoint — delete the file and remove tracking
                if (File(absolutePath).exists()) File(absolutePath).delete()
                return SingleFileRevertResult(reverted = true)
            }
            if (absolutePath in cp.touchedPaths) {
                val snap = File(File(checkpointsDir, "msg-${cp.messageTs}/files"), snapshotRelative(absolutePath))
                if (snap.exists()) {
                    val dst = File(absolutePath)
                    dst.parentFile?.mkdirs()
                    snap.copyTo(dst, overwrite = true)
                    return SingleFileRevertResult(reverted = true)
                }
            }
        }
        return SingleFileRevertResult(reverted = false)
    }

    /**
     * Legacy Boolean-returning facade for callers that don't need the skipped-path distinction.
     * Returns `true` only when the file was actually restored/deleted (not when skipped).
     */
    fun revertFileToBaselineBool(absolutePath: String): Boolean =
        revertFileToBaseline(absolutePath).reverted

    // ── E3: path validation ────────────────────────────────────────────────────

    /**
     * Validates that [path] is safe to write to during a revert operation.
     *
     * Returns `true` if the path is safe; logs a warning and returns `false` if:
     * - The path is a symbolic link (E1 symmetry: don't write through symlinks during restore).
     * - The canonical path escapes [projectRoot] (when a project root was supplied).
     * - The path contains `..` after canonicalization (belt-and-suspenders; canonicalPath
     *   already resolves traversal, but an explicit check is clearer for auditors).
     *
     * No-op (returns `true`) when [projectRoot] is null — preserves backward compatibility
     * with callers that haven't opted into project-root validation.
     */
    private fun isSafeRevertPath(path: String): Boolean {
        val file = File(path)

        // Reject symlinks at the leaf (File.toPath().toRealPath() catches deeper links too,
        // but we also check the leaf explicitly for clarity).
        if (Files.isSymbolicLink(file.toPath())) {
            log.warn("CheckpointStore: skipping revert of symlink path: $path")
            return false
        }

        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            log.warn("CheckpointStore: cannot canonicalize revert path '$path': ${e.message}")
            return false
        }

        // Belt-and-suspenders: reject any path that still contains ".." after canonicalization.
        if (canonical.contains("..")) {
            log.warn("CheckpointStore: skipping revert of path with '..' after canonicalization: $path")
            return false
        }

        if (projectRoot != null) {
            val rootCanonical = try {
                projectRoot.canonicalPath
            } catch (e: Exception) {
                log.warn("CheckpointStore: cannot canonicalize project root: ${e.message}")
                return false
            }
            val underRoot = canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical
            if (!underRoot) {
                log.warn("CheckpointStore: skipping revert of out-of-root path '$path' (root=$rootCanonical)")
                return false
            }
        }

        return true
    }

    private fun msgDir(messageTs: Long): File = File(checkpointsDir, "msg-$messageTs")

    /**
     * Map an absolute filesystem path to a relative key under `msg-{ts}/files/`.
     *
     * Cross-platform: `/Users/me/Foo.kt` → `Users/me/Foo.kt`,
     *                 `C:\Users\me\Foo.kt` → `C/Users/me/Foo.kt`,
     *                 `/var/lib/foo.txt` → `var/lib/foo.txt`.
     *
     * Java's `File(parent, child)` discards `parent` when `child` is itself absolute.
     * So we MUST strip both POSIX `/` prefixes and Windows drive letters before
     * joining under `filesRoot`. Backslashes are normalized to forward slashes so
     * the path tree under `files/` is consistent across platforms.
     */
    private fun snapshotRelative(absolutePath: String): String =
        absolutePath
            .replace('\\', '/')
            .replace(Regex("^([A-Za-z]):"), "$1") // strip the ":" off "C:" → "C"
            .trimStart('/')
}
