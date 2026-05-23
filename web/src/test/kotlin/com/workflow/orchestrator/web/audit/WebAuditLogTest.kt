package com.workflow.orchestrator.web.audit

import com.workflow.orchestrator.core.model.web.AllowlistDecision
import com.workflow.orchestrator.core.model.web.SanitizerVerdict
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
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
        val lines = dir.resolve("web-audit.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"op\":\"fetch\""))
        assertTrue(lines[0].contains("\"sanitizerVerdict\":\"SAFE\""))
    }

    @Test fun `rotates files older than 7 days`() {
        // touch a stale file
        val stale = dir.resolve("web-audit.log.20260515").toFile()
        stale.writeText("stale\n")
        val mtime = Instant.now().minusSeconds(8 * 86_400).toEpochMilli()
        stale.setLastModified(mtime)
        sut.rotateIfStale()
        assertFalse(stale.exists(), "stale file should be removed")
    }
}
