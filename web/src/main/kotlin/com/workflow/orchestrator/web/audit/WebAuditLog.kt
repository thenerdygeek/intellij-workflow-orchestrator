package com.workflow.orchestrator.web.audit

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.util.InstantMoshiAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

class WebAuditLog(private val dir: Path) {

    private val lock = ReentrantLock()
    private val moshi: Moshi = Moshi.Builder()
        .add(InstantMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(WebAuditRecord::class.java)

    fun append(record: WebAuditRecord) = lock.withLock {
        if (!dir.exists()) Files.createDirectories(dir)
        val path = dir.resolve("web-audit.log")
        Files.writeString(
            path,
            adapter.toJson(record) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /** Remove rotated log files older than 7 days. */
    fun rotateIfStale() = lock.withLock {
        if (!dir.exists()) return@withLock
        val cutoff = Instant.now().minusSeconds(7 * 86_400).toEpochMilli()
        Files.newDirectoryStream(dir, "web-audit.log.*").use { stream ->
            for (p in stream) {
                if (p.toFile().lastModified() < cutoff) p.toFile().delete()
            }
        }
    }
}
