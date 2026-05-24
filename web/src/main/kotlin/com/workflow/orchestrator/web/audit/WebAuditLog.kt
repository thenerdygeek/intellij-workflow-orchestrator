package com.workflow.orchestrator.web.audit

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.util.InstantMoshiAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

class WebAuditLog(private val dir: Path) {

    companion object {
        /** Active log file name. */
        const val ACTIVE_LOG = "web-audit.log"
        /** Maximum age of the active log before rotation (24 hours in seconds). */
        private const val ROTATE_AGE_SECS = 86_400L
        /** Maximum size of the active log before rotation (50 MB). */
        private const val ROTATE_SIZE_BYTES = 50L * 1024 * 1024
        /** Rotated siblings older than this are deleted (7 days in milliseconds). */
        private const val PURGE_CUTOFF_MS = 7 * 86_400 * 1000L
    }

    private val lock = ReentrantLock()
    private val moshi: Moshi = Moshi.Builder()
        .add(InstantMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(WebAuditRecord::class.java)

    fun append(record: WebAuditRecord) = lock.withLock {
        if (!dir.exists()) Files.createDirectories(dir)
        val path = dir.resolve(ACTIVE_LOG)
        Files.writeString(
            path,
            adapter.toJson(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * Rotates the active log file when it is older than 24 h or larger than 50 MB, then
     * purges archived siblings older than 7 days.
     *
     * Rotation renames `web-audit.log` → `web-audit.log.<yyyy-MM-dd>` using the file's
     * lastModified date (so the archive name reflects when the content was written, not today).
     *
     * Runs under the same [ReentrantLock] as [append] so writers cannot race with rotation.
     */
    fun rotateIfStale() = lock.withLock {
        if (!dir.exists()) return@withLock

        val activeLog = dir.resolve(ACTIVE_LOG).toFile()

        // Step 1: rotate the active log if it is stale.
        if (activeLog.exists()) {
            val lastModifiedMs = activeLog.lastModified()
            val ageSeconds = (System.currentTimeMillis() - lastModifiedMs) / 1000L
            val sizeBytes = activeLog.length()

            if (ageSeconds >= ROTATE_AGE_SECS || sizeBytes >= ROTATE_SIZE_BYTES) {
                // Name the archive after the file's last-modified date.
                val archiveDate = Instant.ofEpochMilli(lastModifiedMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()          // ISO-8601: yyyy-MM-dd
                val archive = dir.resolve("$ACTIVE_LOG.$archiveDate")
                Files.move(activeLog.toPath(), archive, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // Step 2: delete archived siblings older than 7 days.
        val cutoffMs = System.currentTimeMillis() - PURGE_CUTOFF_MS
        Files.newDirectoryStream(dir, "$ACTIVE_LOG.*").use { stream ->
            for (p in stream) {
                if (p.toFile().lastModified() < cutoffMs) p.toFile().delete()
            }
        }
    }
}
