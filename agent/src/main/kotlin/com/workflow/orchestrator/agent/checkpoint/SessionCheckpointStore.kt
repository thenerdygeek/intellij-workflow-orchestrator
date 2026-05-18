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
        val snapPath = File(filesRoot, absolutePath.trimStart('/'))
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
        metaFile.writeText(json.encodeToString(updated))
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
                File(File(checkpointsDir, "msg-${cp.messageTs}/files"), path.trimStart('/'))
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

        // Delete created files
        for (path in createdInRange) {
            if (File(path).exists() && File(path).delete()) deleted.add(path)
        }

        // Restore touched files (skip if also in createdInRange — already deleted)
        for ((path, cp) in earliestTouchedByPath) {
            if (path in createdInRange) continue
            val snapFile = File(File(checkpointsDir, "msg-${cp.messageTs}/files"), path.trimStart('/'))
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
    fun revertFileToBaseline(absolutePath: String): Boolean {
        val all = listMessageCheckpoints()
        for (cp in all) {
            if (absolutePath in cp.createdPaths) {
                // Created at this checkpoint — delete the file and remove tracking
                if (File(absolutePath).exists()) File(absolutePath).delete()
                return true
            }
            if (absolutePath in cp.touchedPaths) {
                val snap = File(File(checkpointsDir, "msg-${cp.messageTs}/files"), absolutePath.trimStart('/'))
                if (snap.exists()) {
                    val dst = File(absolutePath)
                    dst.parentFile?.mkdirs()
                    snap.copyTo(dst, overwrite = true)
                    return true
                }
            }
        }
        return false
    }

    private fun msgDir(messageTs: Long): File = File(checkpointsDir, "msg-$messageTs")
}
