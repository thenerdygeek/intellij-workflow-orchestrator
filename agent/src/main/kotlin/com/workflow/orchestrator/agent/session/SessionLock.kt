package com.workflow.orchestrator.agent.session

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

class SessionLock private constructor(
    private val lockFile: RandomAccessFile,
    private val lock: FileLock
) {
    fun release() {
        try { lock.release() } catch (_: Exception) {}
        try { lockFile.close() } catch (_: Exception) {}
    }

    companion object {
        private const val LOCK_FILE_NAME = ".lock"

        fun tryAcquire(sessionDir: File): SessionLock? {
            sessionDir.mkdirs()
            val file = File(sessionDir, LOCK_FILE_NAME)
            return try {
                val raf = RandomAccessFile(file, "rw")
                val lock = raf.channel.tryLock()
                if (lock != null) {
                    SessionLock(raf, lock)
                } else {
                    raf.close()
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
