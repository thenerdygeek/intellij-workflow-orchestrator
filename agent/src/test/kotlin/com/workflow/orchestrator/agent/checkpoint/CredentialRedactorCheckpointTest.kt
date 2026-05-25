package com.workflow.orchestrator.agent.checkpoint

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Verifies that credentials present in the user-typed text are redacted before the
 * [SessionCheckpointStore] persists [CheckpointMeta] to disk.
 *
 * Users sometimes accidentally paste Bearer tokens or .env contents into the chat input;
 * those must NOT survive to the on-disk checkpoint (audit finding agent-runtime:F-6).
 */
class CredentialRedactorCheckpointTest {

    /**
     * A user message containing a Bearer token is persisted with the token replaced
     * by [REDACTED] in the on-disk meta.json.
     */
    @Test
    fun `Bearer token in userText is redacted before persisting meta json`(@TempDir tmp: java.nio.file.Path) {
        val rawToken = "Bearer abc123xyz456abc123xyz456"
        val userText = "Please use Authorization: $rawToken to call the API"

        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 1000L, userText = userText)

        // Verify the in-memory model no longer holds the raw token
        val meta = store.listMessageCheckpoints().first()
        assertFalse(
            meta.userText.contains("abc123xyz456"),
            "Raw Bearer token must NOT appear in in-memory CheckpointMeta.userText; got: ${meta.userText}"
        )
        assertTrue(
            meta.userText.contains("[REDACTED]"),
            "Redacted marker must be present in in-memory CheckpointMeta.userText; got: ${meta.userText}"
        )

        // Verify the on-disk meta.json bytes do not contain the raw token
        val metaJson = File(tmp.toFile(), "checkpoints/msg-1000/meta.json").readText()
        assertFalse(
            metaJson.contains("abc123xyz456"),
            "Raw Bearer token must NOT appear in on-disk meta.json; got: $metaJson"
        )
        assertTrue(
            metaJson.contains("[REDACTED]"),
            "Redacted marker must be present in on-disk meta.json; got: $metaJson"
        )
    }

    /**
     * A GitHub personal access token (ghp_ prefix) pasted into the chat is redacted
     * before it reaches disk.
     */
    @Test
    fun `GitHub token in userText is redacted before persisting meta json`(@TempDir tmp: java.nio.file.Path) {
        val ghToken = "ghp_" + "A".repeat(36)
        val userText = "Use token $ghToken for the GitHub API call"

        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 2000L, userText = userText)

        val metaJson = File(tmp.toFile(), "checkpoints/msg-2000/meta.json").readText()
        assertFalse(
            metaJson.contains(ghToken),
            "GitHub token must NOT appear raw in on-disk meta.json; got: $metaJson"
        )
    }

    /**
     * A Sourcegraph access token (sgp_ prefix + 40 hex) pasted into the chat is redacted
     * before it reaches disk (agent-runtime:F-11). Sourcegraph tokens live in PasswordSafe
     * and could be accidentally copied into the input.
     */
    @Test
    fun `Sourcegraph token in userText is redacted before persisting meta json`(@TempDir tmp: java.nio.file.Path) {
        val sgToken = "sgp_" + "a".repeat(40)
        // Standalone token (NOT in an `Authorization: token` header) so this exercises the
        // raw `sgp_` redaction pattern specifically (agent-runtime:F-11), not the auth-header one.
        val userText = "My Sourcegraph token is $sgToken — please configure it"

        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 2500L, userText = userText)

        val metaJson = File(tmp.toFile(), "checkpoints/msg-2500/meta.json").readText()
        assertFalse(
            metaJson.contains(sgToken),
            "Sourcegraph token must NOT appear raw in on-disk meta.json; got: $metaJson"
        )
    }

    /**
     * Non-sensitive text in the user message is NOT altered by redaction.
     */
    @Test
    fun `non-sensitive userText survives redaction unchanged`(@TempDir tmp: java.nio.file.Path) {
        val userText = "Fix the NullPointerException in UserService.kt at line 42"

        val store = SessionCheckpointStore(sessionDir = tmp.toFile())
        store.beginUserMessage(messageTs = 3000L, userText = userText)

        val meta = store.listMessageCheckpoints().first()
        assertTrue(
            meta.userText == userText,
            "Non-sensitive userText must survive redaction unchanged; got: ${meta.userText}"
        )
    }
}
