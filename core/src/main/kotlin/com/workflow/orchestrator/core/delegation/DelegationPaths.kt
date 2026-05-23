package com.workflow.orchestrator.core.delegation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Deterministic Unix Domain Socket paths for cross-IDE agent delegation.
 *
 * Path scheme: `~/.workflow-orchestrator/ipc/<sha256(absolute(projectRoot)).hex.take(16)>.sock`
 *
 * The hash is the SHA-256 of the project's absolute, normalized path, truncated to a 16-char
 * hex prefix (64 bits — collision-safe at the scale of one user's project list).
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §5.2.
 */
object DelegationPaths {
    private const val IPC_DIR = ".workflow-orchestrator/ipc"
    private const val HASH_LEN_CHARS = 16

    /**
     * Computes the deterministic UDS socket path for a project root.
     *
     * Path is canonicalized to absolute + normalized form before hashing, so opening
     * the same project from different CWDs always resolves to the same socket.
     */
    fun socketFor(projectRoot: Path): Path {
        val canonical = projectRoot.toAbsolutePath().normalize().toString()
        val hash = sha256Hex(canonical).take(HASH_LEN_CHARS)
        val ipcDir = Path.of(System.getProperty("user.home"), IPC_DIR)
        return ipcDir.resolve("$hash.sock")
    }

    /** Returns the per-user IPC directory (`~/.workflow-orchestrator/ipc/`). */
    fun ipcDir(): Path = Path.of(System.getProperty("user.home"), IPC_DIR)

    /**
     * Ensures the IPC directory exists with restrictive permissions (0700 on POSIX).
     * Call before binding a socket.
     */
    fun ensureIpcDir() {
        val dir = Path.of(System.getProperty("user.home"), IPC_DIR)
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            } catch (_: UnsupportedOperationException) {
                // Windows / non-POSIX FS — skip
            }
        }
    }

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
