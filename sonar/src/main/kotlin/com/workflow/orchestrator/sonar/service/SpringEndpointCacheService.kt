package com.workflow.orchestrator.sonar.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.workflow.orchestrator.sonar.model.LineCoverageStatus
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-computes and caches which line numbers in each file belong to Spring REST
 * endpoint methods (annotated with @RequestMapping, @GetMapping, etc.).
 *
 * This cache is populated asynchronously when coverage data arrives so that
 * [CoverageLineMarkerProvider] never needs to perform PSI tree walks on the EDT.
 * Until the cache is populated for a file, the line marker provider simply shows
 * the regular uncovered icon (no Spring distinction).
 *
 * The cache is cleared on branch change / build completion (when line coverage
 * is also cleared) and re-populated on demand.
 */
@Service(Service.Level.PROJECT)
class SpringEndpointCacheService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(SpringEndpointCacheService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Maps relative file path -> set of 1-based line numbers that are inside
     * a Spring endpoint method.
     */
    private val endpointLineCache = ConcurrentHashMap<String, Set<Int>>()

    /**
     * Check whether a given line in a file is inside a Spring endpoint method.
     * Returns false if the cache has not been populated yet for this file.
     */
    fun isEndpointLine(relativePath: String, lineNumber: Int): Boolean {
        return endpointLineCache[relativePath]?.contains(lineNumber) == true
    }

    /**
     * Asynchronously scan a file for Spring endpoint annotations and populate
     * the cache with the line numbers that fall within annotated methods.
     *
     * @param relativePath the project-relative file path
     * @param uncoveredLines the set of 1-based line numbers that are uncovered
     *        (we only need to check these lines for endpoint membership)
     */
    fun computeEndpointLinesAsync(relativePath: String, uncoveredLines: Set<Int>) {
        if (uncoveredLines.isEmpty()) return
        if (endpointLineCache.containsKey(relativePath)) return

        scope.launch {
            try {
                val endpointLines = ReadAction.compute<Set<Int>, Throwable> {
                    computeEndpointLines(relativePath, uncoveredLines)
                }
                endpointLineCache[relativePath] = endpointLines
                if (endpointLines.isNotEmpty()) {
                    log.info("[Sonar:SpringCache] Cached ${endpointLines.size} endpoint lines for '$relativePath'")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.warn("[Sonar:SpringCache] Failed to compute endpoint lines for '$relativePath': ${e.message}")
                // Store empty set so we don't retry repeatedly
                endpointLineCache[relativePath] = emptySet()
            }
        }
    }

    /**
     * Compute endpoint lines synchronously. Must be called inside a read action.
     */
    private fun computeEndpointLines(relativePath: String, uncoveredLines: Set<Int>): Set<Int> {
        if (project.isDisposed) return emptySet()

        val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: return emptySet()
        val virtualFile = VfsUtilCore.findRelativeFile(relativePath, baseDir)
            ?: return emptySet()
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return emptySet()
        val document = psiFile.viewProvider.document
            ?: return emptySet()

        // Find all methods annotated with Spring request mapping annotations
        val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
        val endpointMethods = methods.filter { method ->
            method.annotations.any { it.qualifiedName in REQUEST_MAPPING_ANNOTATIONS }
        }

        if (endpointMethods.isEmpty()) return emptySet()

        // Collect all line numbers that fall within endpoint methods
        val endpointLines = mutableSetOf<Int>()
        for (method in endpointMethods) {
            val startLine = document.getLineNumber(method.textRange.startOffset) + 1
            val endLine = document.getLineNumber(method.textRange.endOffset) + 1
            for (line in startLine..endLine) {
                if (line in uncoveredLines) {
                    endpointLines.add(line)
                }
            }
        }

        return endpointLines
    }

    /**
     * Clear the endpoint cache. Called when line coverage cache is cleared
     * (branch change, build completion) so stale data is not used.
     */
    fun clearCache() {
        val size = endpointLineCache.size
        endpointLineCache.clear()
        if (size > 0) {
            log.info("[Sonar:SpringCache] Cleared endpoint cache ($size entries)")
        }
    }

    /**
     * Remove a single file's cache entry (e.g., when it is re-fetched).
     */
    fun invalidateFile(relativePath: String) {
        endpointLineCache.remove(relativePath)
    }

    override fun dispose() {
        scope.cancel()
        endpointLineCache.clear()
    }

    companion object {
        private val REQUEST_MAPPING_ANNOTATIONS = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
        )

        fun getInstance(project: Project): SpringEndpointCacheService {
            return project.getService(SpringEndpointCacheService::class.java)
        }
    }
}
