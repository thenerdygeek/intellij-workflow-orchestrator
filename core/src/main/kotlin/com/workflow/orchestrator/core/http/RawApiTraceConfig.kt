package com.workflow.orchestrator.core.http

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

enum class RawApiTraceMode { OFF, ALWAYS_ON, BURST }

/**
 * Singleton configuration for the raw API trace interceptor.
 *
 * Reads its initial state from [com.workflow.orchestrator.core.settings.PluginSettings]
 * at startup and can be mutated at runtime (e.g., from a settings page or diagnostic action).
 *
 * Thread-safety:
 * - [mode] and scalar properties are `@Volatile` — safe to read from any thread.
 * - [burstRemaining] is an [AtomicInteger] — decrement is race-free.
 */
object RawApiTraceConfig {

    @Volatile var mode: RawApiTraceMode = RawApiTraceMode.OFF

    /** How many days to keep dated subdirectories under the `raw-api/` folder. */
    @Volatile var retentionDays: Int = 3

    /** Maximum bytes captured per file (request or response). Default 10 MB. */
    @Volatile var maxBodyBytes: Long = 10L * 1024 * 1024  // 10 MB

    /**
     * When false (default), prompt bodies are written verbatim — they ARE the diagnostic data.
     * When true, bodies are passed through [com.workflow.orchestrator.agent.security.CredentialRedactor]
     * before writing.
     */
    @Volatile var redactPromptBody: Boolean = false

    // ── Burst mode ──────────────────────────────────────────────────────────

    private val burstRemaining = AtomicInteger(0)

    /**
     * Arm burst mode with [n] traces. The [mode] must also be set to [RawApiTraceMode.BURST]
     * by the caller. Each completed traced request decrements the counter; when it
     * reaches zero [shouldTrace] returns false automatically.
     */
    fun setBurstCount(n: Int) {
        burstRemaining.set(n)
    }

    /**
     * Returns true if a trace should be written for the next request.
     * - [RawApiTraceMode.ALWAYS_ON] → always true
     * - [RawApiTraceMode.BURST]     → true while [burstRemaining] > 0
     * - [RawApiTraceMode.OFF]       → always false
     */
    fun shouldTrace(): Boolean = when (mode) {
        RawApiTraceMode.OFF -> false
        RawApiTraceMode.ALWAYS_ON -> true
        RawApiTraceMode.BURST -> burstRemaining.get() > 0
    }

    /**
     * Decrement the burst counter. Safe to call from any thread. No-op when not in burst mode
     * or counter is already at zero.
     */
    fun decrementBurst() {
        if (mode == RawApiTraceMode.BURST) {
            burstRemaining.updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    // ── Directory helpers ────────────────────────────────────────────────────

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Returns the dated trace directory: `{projectDir}/agent/logs/raw-api/{YYYY-MM-DD}/`.
     * The directory is NOT created here — the interceptor creates it lazily on first write.
     */
    fun traceDir(projectDir: File): File =
        File(projectDir, "agent/logs/raw-api/${LocalDate.now().format(DATE_FMT)}")
}
