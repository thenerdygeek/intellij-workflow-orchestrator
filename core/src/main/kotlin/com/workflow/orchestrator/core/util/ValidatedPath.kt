package com.workflow.orchestrator.core.util

/**
 * Result of [PathLinkResolver] validation. Represents a path that has been
 * canonicalized and verified to sit inside an allowed project root.
 *
 * - [input] is the original text as supplied (for UI matching).
 * - [canonicalPath] is the post-`toRealPath()` absolute path (symlinks resolved).
 * - [line] and [column] are 0-based; 0 when unspecified.
 */
data class ValidatedPath(
    val input: String,
    val canonicalPath: String,
    val line: Int,
    val column: Int,
)
