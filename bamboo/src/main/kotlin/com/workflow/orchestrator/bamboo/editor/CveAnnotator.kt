package com.workflow.orchestrator.bamboo.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.workflow.orchestrator.bamboo.service.CveRemediationService
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveSeverity
import com.workflow.orchestrator.bamboo.service.CveRemediationService.CveVulnerability
import com.workflow.orchestrator.core.settings.PluginSettings

class CveAnnotator : ExternalAnnotator<CveAnnotator.CollectionInfo, CveAnnotator.AnnotationResult>() {

    data class CollectionInfo(
        val file: PsiFile,
        val vulnerabilities: List<CveVulnerability>
    )

    data class AnnotationResult(
        val annotations: List<CveAnnotationEntry>
    )

    data class CveAnnotationEntry(
        val element: XmlTag,
        val cve: CveVulnerability
    )

    // Cache: skip re-scanning if the file hasn't been modified since last run.
    // Keyed by file path so multiple pom.xml files don't share cached results
    // (ExternalAnnotator instances may be shared across files).
    private data class CacheEntry(val stamp: Long, val vulnCount: Int, val result: CollectionInfo)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CollectionInfo? {
        if (file !is XmlFile || file.name != "pom.xml") return null
        val project = file.project
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckCveEnabled) return null

        val vulns = CveRemediationService.getInstance(project).vulnerabilities.value
        if (vulns.isEmpty()) return null

        // Short-circuit: if the file hasn't changed and vuln list is same size, reuse cached results.
        // Keyed by file path to handle multiple pom.xml files correctly.
        val filePath = file.virtualFile?.path ?: ""
        val currentStamp = file.modificationStamp
        val cached = cache[filePath]
        if (cached != null && cached.stamp == currentStamp && cached.vulnCount == vulns.size) {
            return cached.result
        }

        // Skip scan if the caret is not within a dependency-related block.
        // Check if the file actually contains dependency tags before doing full scan.
        val caretOffset = editor.caretModel.offset
        val elementAtCaret = file.findElementAt(caretOffset)
        val isInDependencyContext = elementAtCaret?.let { element ->
            var parent = element.parent
            while (parent != null) {
                if (parent is XmlTag && (parent.name == "dependencies" || parent.name == "dependencyManagement")) {
                    return@let true
                }
                parent = parent.parent
            }
            false
        } ?: false

        // If we're editing outside dependency blocks and have cached results, reuse them
        if (!isInDependencyContext && cached != null && cached.vulnCount == vulns.size) {
            return cached.result
        }

        val result = CollectionInfo(file, vulns)
        cache[filePath] = CacheEntry(currentStamp, vulns.size, result)
        return result
    }

    override fun doAnnotate(collectionInfo: CollectionInfo): AnnotationResult {
        val xmlFile = collectionInfo.file as XmlFile
        val entries = mutableListOf<CveAnnotationEntry>()
        val rootTag = xmlFile.rootTag ?: return AnnotationResult(emptyList())

        for (vuln in collectionInfo.vulnerabilities) {
            val depTag = findDependencyTag(rootTag, vuln.groupId, vuln.artifactId)
            if (depTag != null) {
                entries.add(CveAnnotationEntry(depTag, vuln))
            }
        }

        return AnnotationResult(entries)
    }

    override fun apply(file: PsiFile, result: AnnotationResult, holder: AnnotationHolder) {
        for (entry in result.annotations) {
            val severity = when (entry.cve.severity) {
                CveSeverity.CRITICAL, CveSeverity.HIGH -> HighlightSeverity.WARNING
                CveSeverity.MEDIUM -> HighlightSeverity.WEAK_WARNING
                CveSeverity.LOW -> HighlightSeverity.INFORMATION
            }

            val fixText = entry.cve.fixedVersion?.let { " — Fix: bump to $it" } ?: ""
            holder.newAnnotation(
                severity,
                "${entry.cve.cveId} (${entry.cve.severity})$fixText"
            )
                .range(entry.element.textRange)
                .create()
        }
    }

    private fun findDependencyTag(rootTag: XmlTag, groupId: String, artifactId: String): XmlTag? {
        val dependenciesSections = mutableListOf<XmlTag>()

        rootTag.findFirstSubTag("dependencies")?.let { dependenciesSections.add(it) }
        rootTag.findFirstSubTag("dependencyManagement")
            ?.findFirstSubTag("dependencies")
            ?.let { dependenciesSections.add(it) }

        for (depsTag in dependenciesSections) {
            for (dep in depsTag.findSubTags("dependency")) {
                if (dep.findFirstSubTag("groupId")?.value?.text == groupId &&
                    dep.findFirstSubTag("artifactId")?.value?.text == artifactId) {
                    return dep
                }
            }
        }

        return null
    }
}
