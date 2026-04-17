package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.usageView.UsageInfo

/**
 * Per-usage classification data. Pure data, no IntelliJ PSI references —
 * [summarizeForApproval] is testable without an IntelliJ fixture.
 *
 * @property module Name of the IntelliJ module containing the usage, or `null`
 *   when the file is in an external library OR can't be resolved to any module
 *   (e.g. generated file outside content roots). Distinct buckets are tracked
 *   so a null-module usage counts as its own module for the cross-module gate.
 * @property isLibrary `true` when the usage's file is inside an external library
 *   (a jar, `jar://.../foo.jar!/...`). Library usages are ALWAYS blocked — the
 *   LLM CANNOT bypass the block with `confirm_cross_module=true`.
 * @property isTest `true` when the usage's file is in test source content.
 *   Affects only the test/prod breakdown in the confirmation preview.
 * @property fileName Absolute VirtualFile path for error-message display.
 */
data class UsageClassification(
    val module: String?,
    val isLibrary: Boolean,
    val isTest: Boolean,
    val fileName: String,
)

/**
 * Test/production usage counts for a single module in the cross-module preview.
 */
data class TestProdCounts(
    val testCount: Int,
    val prodCount: Int,
) {
    val total: Int get() = testCount + prodCount
}

/**
 * Outcome of [summarizeForApproval]. The tool branches on this to decide
 * whether to proceed, block, or show a preview.
 *
 * Branches:
 * - [NoUsages]: symbol exists but has no references. Tool proceeds (renaming
 *   unused declarations is a legitimate operation and matches pre-T6 behaviour).
 * - [LibraryBlocked]: at least one usage is in an external library (jar). Tool
 *   returns an error UNCONDITIONALLY. `confirm_cross_module=true` does NOT
 *   bypass this — project code would end up referencing names that no longer
 *   exist in the jar's bytecode.
 * - [CrossModulePreview]: usages span >1 module, no library usages. Tool
 *   returns a preview; LLM must re-invoke with `confirm_cross_module=true` to
 *   proceed.
 * - [SingleModuleOK]: all usages in exactly one module, no library usages.
 *   Tool proceeds without confirmation (preserves the common-case UX).
 */
sealed class SummaryResult {
    data object NoUsages : SummaryResult()
    data class LibraryBlocked(val libraryFiles: List<String>) : SummaryResult()
    data class CrossModulePreview(val moduleBreakdown: Map<String, TestProdCounts>) : SummaryResult()
    data class SingleModuleOK(
        val module: String,
        val testCount: Int,
        val prodCount: Int,
    ) : SummaryResult()
}

/**
 * Synthetic bucket name for usages whose module cannot be resolved (e.g.
 * generated files outside any content root). Keeps them visible in the
 * cross-module preview rather than silently dropping them.
 */
private const val UNKNOWN_MODULE_BUCKET = "<unresolved>"

/**
 * Pure classification entry point. Unit-testable WITHOUT IntelliJ services.
 *
 * Ordering is critical for the hard-block contract:
 *   1. ANY library usage → [SummaryResult.LibraryBlocked] (unconditional; no
 *      confirm_cross_module bypass possible).
 *   2. 0 usages → [SummaryResult.NoUsages].
 *   3. >1 module → [SummaryResult.CrossModulePreview].
 *   4. 1 module → [SummaryResult.SingleModuleOK].
 */
fun summarizeForApproval(classifications: List<UsageClassification>): SummaryResult {
    // 1) Library block wins unconditionally — even one library usage blocks.
    val libraryFiles = classifications.filter { it.isLibrary }.map { it.fileName }.distinct()
    if (libraryFiles.isNotEmpty()) {
        return SummaryResult.LibraryBlocked(libraryFiles)
    }

    // 2) No usages → distinct branch (rename an unused declaration).
    if (classifications.isEmpty()) {
        return SummaryResult.NoUsages
    }

    // 3/4) Group by module. A null module becomes its own bucket so the LLM
    // sees unresolved files in the preview rather than silently dropping them.
    val grouped = classifications.groupBy { it.module ?: UNKNOWN_MODULE_BUCKET }
    if (grouped.size == 1) {
        val (module, usages) = grouped.entries.single()
        val testCount = usages.count { it.isTest }
        val prodCount = usages.count { !it.isTest }
        return SummaryResult.SingleModuleOK(module, testCount = testCount, prodCount = prodCount)
    }

    val breakdown: Map<String, TestProdCounts> = grouped.mapValues { (_, usages) ->
        TestProdCounts(
            testCount = usages.count { it.isTest },
            prodCount = usages.count { !it.isTest },
        )
    }
    return SummaryResult.CrossModulePreview(breakdown)
}

/**
 * Impure classification of a single [UsageInfo]. Uses IntelliJ
 * [ModuleUtilCore.findModuleForFile] and [ProjectFileIndex] — must run under a
 * read action (callers already hold one via `ReadAction.nonBlocking`).
 *
 * Returns `null` when the usage has no resolvable virtual file (e.g. a
 * synthetic element, a ranged usage with no file); such usages are dropped
 * from the classification list. They cannot be classified as library OR
 * module, so skipping them is correct: the rename will still update them (if
 * valid) but they won't influence the safety analysis.
 */
fun classifyUsage(usage: UsageInfo, project: Project): UsageClassification? {
    val vf: VirtualFile = usage.virtualFile ?: return null
    val projectFileIndex = ProjectFileIndex.getInstance(project)

    // Library detection — any file inside a jar / external library root.
    // We use BOTH `isInLibrary` (broadest: includes classes + sources) and
    // `isInLibraryClasses` as belt-and-suspenders. `isInLibrary` returns
    // true if in classes OR sources; `isInLibraryClasses` only if in classes.
    // For the hard-block contract we treat EITHER as a library usage.
    val isLibrary = projectFileIndex.isInLibrary(vf) || projectFileIndex.isInLibraryClasses(vf)

    val moduleName: String? = if (isLibrary) {
        null
    } else {
        ModuleUtilCore.findModuleForFile(vf, project)?.name
    }

    val isTest = projectFileIndex.isInTestSourceContent(vf)

    return UsageClassification(
        module = moduleName,
        isLibrary = isLibrary,
        isTest = isTest,
        fileName = vf.path,
    )
}
