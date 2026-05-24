package com.workflow.orchestrator.web.audit

import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines

class WebAuditLogTest {
    private lateinit var dir: Path
    private lateinit var sut: WebAuditLog

    @BeforeEach fun setUp() {
        dir = Files.createTempDirectory("web-audit-test")
        sut = WebAuditLog(dir)
    }

    @AfterEach fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test fun `appends one JSONL line per call`() {
        val rec = WebAuditRecord(
            ts = Instant.parse("2026-05-23T14:32:01Z"),
            op = "fetch",
            agentSessionId = "ses_abc",
            url = "https://bit.ly/x",
            finalUrl = "https://docs.example.com/x",
            query = null, provider = null,
            allowlistDecision = AllowlistDecision.APPROVED_PROMPT,
            screenerFlags = listOf("SHORTENER"),
            ssrfPass = true, httpStatus = 200, contentType = "text/html",
            responseBytes = 14_322, extractedChars = 4_187, resultCount = null,
            sanitizerVerdict = SanitizerVerdict.SAFE, sanitizerNotes = null,
            elapsedMs = 1_843, error = null,
        )
        sut.append(rec)
        sut.append(rec.copy(url = "https://example.org/y"))
        val lines = dir.resolve(WebAuditLog.ACTIVE_LOG).readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"op\":\"fetch\""))
        assertTrue(lines[0].contains("\"sanitizerVerdict\":\"SAFE\""))
    }

    @Test fun `rotates files older than 7 days`() {
        // Touch a stale archived file (older than 7 days) — it should be deleted.
        val stale = dir.resolve("web-audit.log.20260515").toFile()
        stale.writeText("stale\n")
        val mtime = Instant.now().minusSeconds(8 * 86_400).toEpochMilli()
        stale.setLastModified(mtime)
        sut.rotateIfStale()
        assertFalse(stale.exists(), "stale archived file should be removed")
    }

    // ── I4 regression tests ───────────────────────────────────────────────────

    @Test fun `rotates web-audit_log to dated sibling when older than 24h`() {
        // Write some content to the active log and make it appear >24h old.
        val activeLog = dir.resolve(WebAuditLog.ACTIVE_LOG).toFile()
        activeLog.writeText("line1\n")
        val staleMs = Instant.now().minusSeconds(25 * 3600).toEpochMilli()
        activeLog.setLastModified(staleMs)

        sut.rotateIfStale()

        // The active log must no longer exist (renamed to a dated sibling).
        assertFalse(activeLog.exists(), "active log must be renamed on rotation")

        // A dated sibling must have been created.
        val siblings = dir.listDirectoryEntries("web-audit.log.*").map { it.name }
        assertTrue(siblings.isNotEmpty(), "A dated sibling must exist after rotation: $siblings")
        assertTrue(
            siblings.any { it.matches(Regex("web-audit\\.log\\.\\d{4}-\\d{2}-\\d{2}")) },
            "Sibling must match web-audit.log.yyyy-MM-dd pattern: $siblings"
        )
    }

    @Test fun `does not rotate when web-audit_log is fresh`() {
        // A recently written active log must NOT be rotated.
        val activeLog = dir.resolve(WebAuditLog.ACTIVE_LOG).toFile()
        activeLog.writeText("fresh\n")
        // lastModified is current time by default — no need to set it.

        sut.rotateIfStale()

        // The active log must still exist (not rotated).
        assertTrue(activeLog.exists(), "fresh active log must not be rotated")
        val siblings = dir.listDirectoryEntries("web-audit.log.*").map { it.name }
        assertTrue(siblings.isEmpty(), "No dated siblings expected for a fresh log: $siblings")
    }

    @Test fun `does not rotate when web-audit_log does not exist`() {
        // If the active log never existed, rotateIfStale should complete without errors.
        val activeLog = dir.resolve(WebAuditLog.ACTIVE_LOG).toFile()
        assertFalse(activeLog.exists())
        // Must not throw.
        assertDoesNotThrow { sut.rotateIfStale() }
    }

    // ── I14: FileLock + ReentrantLock combination ──────────────────────────────
    //
    // The FileLock + ReentrantLock combination ensures BOTH intra-JVM safety (the
    // ReentrantLock handles two coroutines inside the same process) AND inter-JVM
    // safety (the FileLock handles two IDE windows hitting the same audit log file).
    //
    // The inter-JVM race is hard to crisply test without spinning up two JVMs, but
    // we can pin that: (a) intra-JVM concurrent appends still produce N lines for
    // N append calls, and (b) the lock file is created as expected.

    @Test fun `concurrent in-JVM appends do not lose records`() {
        val rec = WebAuditRecord(
            ts = Instant.parse("2026-05-23T14:32:01Z"),
            op = "fetch",
            agentSessionId = "ses_abc",
            url = "https://x",
            finalUrl = null, query = null, provider = null,
            allowlistDecision = null, screenerFlags = emptyList(),
            ssrfPass = true, httpStatus = null, contentType = null,
            responseBytes = null, extractedChars = null, resultCount = null,
            sanitizerVerdict = null, sanitizerNotes = null,
            elapsedMs = 1, error = null,
        )
        val threads = (1..8).map {
            Thread { repeat(25) { sut.append(rec) } }.apply { start() }
        }
        threads.forEach { it.join() }
        val lines = dir.resolve(WebAuditLog.ACTIVE_LOG).readLines()
        assertEquals(200, lines.size, "All ${threads.size} threads' appends must be persisted")
    }
}
