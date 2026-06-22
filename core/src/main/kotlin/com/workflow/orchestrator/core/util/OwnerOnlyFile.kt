package com.workflow.orchestrator.core.util

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * A4 (confidentiality hardening): restricts diagnostic dump files/dirs to owner-only POSIX
 * permissions so the full LLM request bodies they contain (entire conversation + file contents)
 * are not world-readable on shared / multi-user hosts.
 *
 * No-op on non-POSIX filesystems (Windows). Best-effort: failures are swallowed because the dump
 * is opt-in diagnostic output (default OFF), never load-bearing.
 *
 * Lives in `:core` rather than reusing `:agent`'s `AtomicFileWriter.applyOwnerOnlyPerms` because
 * that helper is module-internal and `:core` must not depend on `:agent`.
 */
object OwnerOnlyFile {

    private val FILE_PERMS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    private val DIR_PERMS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    /** `rw-------` on [file] (best-effort; no-op on non-POSIX or if the file is absent). */
    fun restrictFile(file: File) = restrict(file, FILE_PERMS)

    /**
     * `rwx------` on [dir] so other users cannot traverse into it — this is the primary lever:
     * an owner-only directory protects every file created inside it regardless of the umask.
     */
    fun restrictDir(dir: File) = restrict(dir, DIR_PERMS)

    private fun restrict(target: File, perms: Set<PosixFilePermission>) {
        runCatching {
            Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
                ?.setPermissions(perms)
        }
    }
}
