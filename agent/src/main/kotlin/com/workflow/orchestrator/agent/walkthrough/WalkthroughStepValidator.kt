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

/** Per-step existence + line-bound validation inside a readAction (spec §3). */
suspend fun defaultStepValidator(project: Project, steps: List<WalkthroughStep>): StepValidation =
    validateSteps(project, steps, ::resolveStepFile)

/**
 * Validation body with an injectable resolver seam. Platform-fixture tests must NOT touch
 * LocalFileSystem/disk (a real-disk refresh spawns UnindexedFilesScanner work that outlives
 * the light fixture and hangs the NEXT BasePlatformTestCase setUp on "Indexing timeout"),
 * so they inject a LightVirtualFile-backed resolver instead.
 */
internal suspend fun validateSteps(
    project: Project,
    steps: List<WalkthroughStep>,
    resolve: (Project, String) -> VirtualFile?,
): StepValidation =
    readAction {
        val valid = mutableListOf<WalkthroughStep>()
        val errors = mutableListOf<String>()
        steps.forEachIndexed { i, s ->
            val n = i + 1
            val vfile = resolve(project, s.file)
            if (vfile == null || vfile.isDirectory) {
                errors += "step $n: file not found: ${s.file}"
                return@forEachIndexed
            }
            val doc = FileDocumentManager.getInstance().getDocument(vfile)
            if (doc == null) {
                errors += "step $n: not a text file: ${s.file}"
                return@forEachIndexed
            }
            when {
                s.startLine > doc.lineCount ->
                    errors += "step $n: start_line ${s.startLine} exceeds file length ${doc.lineCount} (${s.file})"
                s.endLine > doc.lineCount ->
                    errors += "step $n: end_line ${s.endLine} exceeds file length ${doc.lineCount} (${s.file})"
                else -> valid += s
            }
        }
        StepValidation(valid, errors)
    }
