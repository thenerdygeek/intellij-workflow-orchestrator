package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Resolve a step's file like other file tools: absolute as-is, else project-relative.
 * NON-refreshing lookup (`findFileByPath`, the ReadFileTool.kt:154 precedent) — this is
 * called inside a readAction, where a synchronous VFS refresh is prohibited by the platform.
 */
internal fun resolveStepFile(project: Project, path: String): VirtualFile? {
    val full = if (File(path).isAbsolute) path else "${project.basePath}/$path"
    return LocalFileSystem.getInstance().findFileByPath(full)
}

/**
 * What the platform can tell us about a step's file. Kept as a small value type so the
 * per-step bounds + error-message logic ([validateStepsWith]) stays PURE and unit-testable
 * without a platform fixture (which would collide on the headless "Indexing timeout").
 */
sealed interface StepFileProbe {
    data object NotFound : StepFileProbe
    data object NotText : StepFileProbe
    data class Text(val lineCount: Int) : StepFileProbe
}

/**
 * PURE per-step existence + line-bound validation (no platform deps). Invalid steps are rejected
 * individually with a precise message (the LLM reads these to re-`append` corrected steps); valid
 * steps in the same call are kept. The error strings are the contract — pinned by unit tests.
 */
internal fun validateStepsWith(
    steps: List<WalkthroughStep>,
    probe: (WalkthroughStep) -> StepFileProbe,
): StepValidation {
    val valid = mutableListOf<WalkthroughStep>()
    val errors = mutableListOf<String>()
    steps.forEachIndexed { i, s ->
        val n = i + 1
        when (val p = probe(s)) {
            StepFileProbe.NotFound -> errors += "step $n: file not found: ${s.file}"
            StepFileProbe.NotText -> errors += "step $n: not a text file: ${s.file}"
            is StepFileProbe.Text -> when {
                s.startLine > p.lineCount ->
                    errors += "step $n: start_line ${s.startLine} exceeds file length ${p.lineCount} (${s.file})"
                s.endLine > p.lineCount ->
                    errors += "step $n: end_line ${s.endLine} exceeds file length ${p.lineCount} (${s.file})"
                else -> valid += s
            }
        }
    }
    return StepValidation(valid, errors)
}

/** Real platform probe: resolve the file + read its line count, all inside a `readAction` (spec §3). */
suspend fun defaultStepValidator(project: Project, steps: List<WalkthroughStep>): StepValidation =
    readAction {
        validateStepsWith(steps) { s ->
            val vfile = resolveStepFile(project, s.file)
            if (vfile == null || vfile.isDirectory) {
                StepFileProbe.NotFound
            } else {
                val doc = FileDocumentManager.getInstance().getDocument(vfile)
                if (doc == null) StepFileProbe.NotText else StepFileProbe.Text(doc.lineCount)
            }
        }
    }
