package com.workflow.orchestrator.agent.session

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

object AtomicFileWriter {

    /**
     * Test seam — production leaves this null and we call `Files.move(..., ATOMIC_MOVE,
     * REPLACE_EXISTING)` directly. `AtomicFileWriterRetryTest` swaps in a mover that
     * simulates `AccessDeniedException` so the retry path is exercisable on macOS/Linux
     * (where ATOMIC_MOVE doesn't actually fail with open handles, unlike Windows).
     */
    @Volatile
    internal var moverOverride: ((Path, Path) -> Unit)? = null

    private const val MAX_RETRIES = 5

    /**
     * POSIX permission set: owner read+write only (rw-------).
     * Applied to all tmp files. Windows skips this silently (no POSIX view).
     */
    private val OWNER_ONLY_PERMS = PosixFilePermissions.fromString("rw-------")

    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(
            target.parent,
            "${target.name}.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}"
        ).toPath()
        try {
            // E1: Open with CREATE_NEW so a pre-existing symlink at the tmp path is rejected
            // immediately rather than followed. An attacker who races a symlink here would
            // otherwise redirect the write to an arbitrary file reachable by the IDE process.
            // E2: Set POSIX rw------- (owner-only) before writing content so the file is
            // never readable by other users on shared hosts, even between create and chmod.
            Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW).use { out ->
                applyOwnerOnlyPerms(tmp)
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            moveWithRetry(tmp, target.toPath())
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /**
     * Apply POSIX rw------- (owner-only) to [path].
     * No-op on Windows (no PosixFileAttributeView available).
     */
    internal fun applyOwnerOnlyPerms(path: Path) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?.setPermissions(OWNER_ONLY_PERMS)
    }

    private fun moveWithRetry(src: Path, dst: Path) {
        var attempt = 0
        while (true) {
            try {
                doMove(src, dst)
                return
            } catch (e: AccessDeniedException) {
                // Windows-only contention: AV scanner / VFS refresher / search indexer
                // briefly holds a handle on the destination. ATOMIC_MOVE doesn't wait —
                // we retry with short backoff. Other IOExceptions (disk full, perms)
                // fail fast.
                attempt++
                if (attempt >= MAX_RETRIES) throw e
                Thread.sleep(20L * (1L shl (attempt - 1)))  // 20, 40, 80, 160 ms
            }
        }
    }

    private fun doMove(src: Path, dst: Path) {
        val override = moverOverride
        if (override != null) {
            override(src, dst)
        } else {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
