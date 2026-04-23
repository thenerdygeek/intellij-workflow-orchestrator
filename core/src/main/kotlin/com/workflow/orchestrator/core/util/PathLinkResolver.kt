package com.workflow.orchestrator.core.util

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * Validates file-path candidates before the UI allows opening them.
 *
 * Used by:
 * - The agent webview auto-link scanner (batch validate after stream finalize)
 * - The click-time `navigateToFile` handler (re-validate, defense-in-depth)
 * - The markdown `[text](path)` link handler (same re-validate path)
 *
 * Any rejection returns null (single) or drops the entry (batch). See the
 * design spec for the full pipeline.
 */
class PathLinkResolver(private val project: Project) {

    private val rootsCache = AtomicReference<List<Path>>(emptyList())

    fun validate(candidates: List<String>): List<ValidatedPath> =
        candidates.mapNotNull { resolveForOpen(it) }

    fun resolveForOpen(input: String): ValidatedPath? {
        if (input.length > MAX_PATH_LENGTH) return null
        if (input.any { it.isISOControl() || it == ' ' }) return null
        if (SCHEME_PREFIX.containsMatchIn(input)) return null
        if (input.startsWith("\\\\")) return null
        if (DRIVE_RELATIVE.containsMatchIn(input)) return null

        val (pathPart, line, column) = parseLineCol(input) ?: return null

        val segments = pathPart.split('/', '\\').filter { it.isNotEmpty() }
        for (seg in segments) {
            if (seg.endsWith('.') || seg.endsWith(' ')) return null
            if (SHORT_NAME.containsMatchIn(seg)) return null
            if (seg.any { it in RESERVED_CHARS }) return null
            val bare = seg.substringBefore('.').uppercase()
            if (bare in RESERVED_DOS_NAMES) return null
        }

        val candidatePath = try {
            if (Paths.get(pathPart).isAbsolute) Paths.get(pathPart)
            else Paths.get(project.basePath ?: return null, pathPart)
        } catch (_: Exception) {
            return null
        }

        val canonical = try {
            candidatePath.toRealPath()
        } catch (_: Exception) {
            return null
        }

        if (!isUnderAllowedRoot(canonical)) return null
        if (!Files.isRegularFile(canonical)) return null

        return ValidatedPath(
            input = input,
            canonicalPath = canonical.toString(),
            line = line,
            column = column,
        )
    }

    private fun parseLineCol(input: String): Triple<String, Int, Int>? {
        val match = LINE_COL_SUFFIX.find(input)
        if (match == null) return Triple(input, 0, 0)
        val prefix = input.substring(0, match.range.first)
        if (!prefix.contains('/') && !prefix.contains('\\') && !EXT_BEFORE_COLON.containsMatchIn(prefix)) {
            return Triple(input, 0, 0)
        }
        val lineRaw = match.groupValues[1].toIntOrNull() ?: return null
        val colRaw = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val line = (lineRaw.coerceAtMost(MAX_LINE).coerceAtLeast(1)) - 1
        val col = if (colRaw == 0) 0 else (colRaw.coerceAtMost(MAX_LINE).coerceAtLeast(1)) - 1
        return Triple(prefix, line, col)
    }

    private fun allowedRoots(): List<Path> {
        val cached = rootsCache.get()
        if (cached.isNotEmpty()) return cached
        val roots = mutableListOf<Path>()
        project.basePath?.let { bp ->
            try { roots.add(Paths.get(bp).toRealPath()) } catch (_: Exception) {}
        }
        val mm = ModuleManager.getInstance(project)
        for (module in mm.modules) {
            for (vf in ModuleRootManager.getInstance(module).contentRoots) {
                try { roots.add(Paths.get(vf.path).toRealPath()) } catch (_: Exception) {}
            }
        }
        val distinct = roots.distinct()
        rootsCache.set(distinct)
        return distinct
    }

    private fun isUnderAllowedRoot(canonical: Path): Boolean {
        val roots = allowedRoots()
        if (SystemInfo.isWindows) {
            val c = canonical.toString().lowercase()
            return roots.any { root ->
                val r = root.toString().lowercase()
                c == r || c.startsWith(r + File.separator)
            }
        }
        return roots.any { canonical.startsWith(it) }
    }

    /** Called when module roots change — resets cached allowed roots. */
    fun invalidateRoots() {
        rootsCache.set(emptyList())
    }

    private companion object {
        const val MAX_PATH_LENGTH = 4096
        const val MAX_LINE = 10_000_000
        val SCHEME_PREFIX = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:(?![\\/])""")
        val DRIVE_RELATIVE = Regex("""^[A-Za-z]:(?![\\/])""")
        val SHORT_NAME = Regex("""~\d""")
        val LINE_COL_SUFFIX = Regex(""":(\d{1,7})(?::(\d{1,7}))?$""")
        val EXT_BEFORE_COLON = Regex("""\.[A-Za-z][A-Za-z0-9]{0,9}$""")
        val RESERVED_CHARS = charArrayOf('<', '>', '"', '|', '?', '*')
        val RESERVED_DOS_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "CONIN\$", "CONOUT\$",
        )
    }
}
