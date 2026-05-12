package com.workflow.orchestrator.agent.session

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    fun write(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parent, "${target.name}.tmp.${System.currentTimeMillis()}.${(Math.random() * 100000).toInt()}")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            moveWithRetry(tmp.toPath(), target.toPath())
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
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
