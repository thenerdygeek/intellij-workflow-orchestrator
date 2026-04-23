package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text guardrails that pin the run_command streaming wiring contract so
 * it cannot regress silently.
 *
 * The original regression was introduced when SingleAgentSession.kt was deleted
 * (commit c92b0b51) without migrating the currentToolCallId.set/remove block into
 * AgentLoop, and when clearActiveLoopState nulled the callback without re-arming it.
 * These three tests make that class of regression fail loudly at test time instead
 * of manifesting as a silent "exit code 0 / (No output)" in the chat UI.
 *
 * Pattern: identical to RunInvocationLeakTest — read source as String, assert
 * presence/absence of structural signatures. No JCEF, no process spawning needed.
 */
class RunCommandStreamingWiringTest {

    // ───────────────────────────────────────────────────────────────────────
    // Test 1 — AgentLoop must set the currentToolCallId ThreadLocal
    //          before calling tool.execute() for run_command, and remove it
    //          in a finally block on every exit path.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `AgentLoop sets and removes RunCommandTool currentToolCallId around execute`() {
        val text = readSource("loop", "AgentLoop.kt")

        assertTrue(
            text.contains("RunCommandTool.currentToolCallId.set(toolCallId)"),
            "AgentLoop.kt must set RunCommandTool.currentToolCallId to the LLM-assigned " +
                "toolCallId before calling tool.execute() for run_command. Without this, " +
                "the reader thread inside RunCommandTool falls back to a synthetic 'run-cmd-N' " +
                "id and streaming chunks are keyed under the wrong id — React's Terminal looks " +
                "up toolOutputStreams[toolCall.id] (LLM id) and finds nothing. " +
                "See: RunCommandTool.kt line ~238 (currentToolCallId.get() ?: synthetic fallback)."
        )

        assertTrue(
            text.contains("RunCommandTool.currentToolCallId.remove()"),
            "AgentLoop.kt must remove RunCommandTool.currentToolCallId in a finally block " +
                "after tool.execute() for run_command. Without this, the ThreadLocal leaks " +
                "across loop iterations or to the next tool call on the same thread."
        )

        // The set must be gated on the STREAMING_TOOLS set (not unconditional) and the remove
        // must be in a finally block.  The original check was the literal string
        // `if (toolName == "run_command") RunCommandTool.currentToolCallId.set` but commit
        // a3be4144 deliberately generalised the gate to a `STREAMING_TOOLS` set so that sonar
        // live streaming could reuse the same ThreadLocal wiring without duplicating the
        // set/remove block.  The contract now is: the ThreadLocal is only touched for tools
        // that are members of STREAMING_TOOLS, not for every tool.
        assertTrue(
            text.contains("""if (toolName in STREAMING_TOOLS) RunCommandTool.currentToolCallId.set"""),
            "AgentLoop.kt must gate the currentToolCallId.set on `toolName in STREAMING_TOOLS`. " +
                "Setting the ThreadLocal unconditionally would affect other tools sharing the " +
                "same dispatcher thread.  The STREAMING_TOOLS set (which always includes " +
                "\"run_command\") provides the same isolation guarantee while letting sonar and " +
                "future streaming tools reuse the wiring without duplicating the set/remove block."
        )

        // Also assert that run_command is listed as a member of STREAMING_TOOLS so the original
        // intent — this ThreadLocal is always set for run_command — is still verifiable from
        // source text alone.
        assertTrue(
            text.contains(""""run_command""""),
            "AgentLoop.kt must list \"run_command\" in the STREAMING_TOOLS set so the " +
                "ThreadLocal wiring is still active for the primary streaming tool."
        )

        val finallyRemovePattern = Regex(
            """finally\s*\{[^}]*RunCommandTool\.currentToolCallId\.remove""",
            RegexOption.DOT_MATCHES_ALL
        )
        assertTrue(
            finallyRemovePattern.containsMatchIn(text),
            "AgentLoop.kt must remove RunCommandTool.currentToolCallId inside a finally block " +
                "so the cleanup runs on all exit paths: normal completion, timeout, exception, " +
                "and coroutine cancellation."
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Test 2 — AgentController.clearActiveLoopState must NOT null the
    //          streamCallback. The callback is wired once in init; nulling
    //          it breaks every run_command after the first cancel/newChat.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `AgentController clearActiveLoopState does not null RunCommandTool streamCallback`() {
        val text = readSource("ui", "AgentController.kt")

        // Locate the clearActiveLoopState function body.
        // We check that the streamCallback null assignment doesn't appear anywhere in
        // the file, since there's no other legitimate site for it — the callback is
        // permanently wired in wireCallbacks() (called from init) and the batcher
        // it targets lives for the controller's lifetime.
        assertFalse(
            text.contains("RunCommandTool.streamCallback = null"),
            "AgentController.kt must not null RunCommandTool.streamCallback anywhere. " +
                "The callback is set once in wireCallbacks() (called from init) and the " +
                "PerToolStreamBatcher it points to lives for the controller's lifetime. " +
                "Nulling it in clearActiveLoopState (called by cancel/newChat/dispose) " +
                "breaks streaming for every subsequent run_command in the same session. " +
                "toolStreamBatcher.flush() already safely drains buffered output on cancel."
        )

        // The callback must still be set somewhere (wireCallbacks in init).
        assertTrue(
            text.contains("RunCommandTool.streamCallback = {"),
            "AgentController.kt must still wire RunCommandTool.streamCallback to the " +
                "toolStreamBatcher in wireCallbacks() (called from init). If this line is " +
                "gone, streaming is completely dead for all run_command calls."
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Test 3 — RunCommandTool reader threads must not silently swallow
    //          exceptions. Silent swallows hide I/O failures that make
    //          outputLines appear empty, causing the "(No output)" sentinel
    //          to appear even for commands that should have produced output.
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `RunCommandTool reader threads log exceptions instead of swallowing them`() {
        val text = readSource("tools/builtin", "RunCommandTool.kt")

        // Exactly one `catch (_: Exception)` is permitted: the `parseEnvParam` helper
        // that silently ignores malformed JSON from the LLM (emptyMap() fallback is correct
        // there). Any additional occurrences mean a reader thread was reverted to the
        // silent-swallow form, which hides I/O failures and leaves outputLines empty.
        val silentCatchCount = Regex("""catch\s*\(_:\s*Exception\)""").findAll(text).count()
        assertTrue(
            silentCatchCount <= 1,
            "RunCommandTool.kt has $silentCatchCount silent `catch (_: Exception)` blocks " +
                "but at most 1 is permitted (the parseEnvParam JSON fallback). Reader-thread " +
                "catches must use `catch (e: Exception)` + LOG.warn so I/O failures are " +
                "visible in the agent JSONL log instead of leaving outputLines silently empty."
        )

        assertTrue(
            text.contains("LOG.warn"),
            "RunCommandTool.kt reader-thread catch blocks must call LOG.warn so that any " +
                "abnormal termination (closed stdout, encoding error, etc.) is visible in " +
                "~/.workflow-orchestrator/{proj}/logs/agent-YYYY-MM-DD.jsonl."
        )
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Reads a Kotlin source file from the agent module's main source set.
     * Resolves relative to `user.dir` — works under both Gradle (user.dir = agent/)
     * and IntelliJ test runner (user.dir = repo root).
     */
    private fun readSource(subPackage: String, name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val rel = "src/main/kotlin/com/workflow/orchestrator/agent/$subPackage/$name"
        val moduleRooted = File(root, rel)
        val repoRooted = File(root, "agent/$rel")
        val path = when {
            moduleRooted.isFile -> moduleRooted
            repoRooted.isFile -> repoRooted
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRooted.absolutePath}\n" +
                    "  2. ${repoRooted.absolutePath}\n" +
                    "user.dir=$userDir — module layout may have changed."
            )
        }
        return path.readText()
    }
}
