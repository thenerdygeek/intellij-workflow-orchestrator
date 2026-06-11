package com.workflow.orchestrator.core.psi

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

class PsiContextEnricher(private val project: Project) {

    data class PsiContext(
        val className: String?,
        val classAnnotations: List<String>,
        val methodAnnotations: Map<String, List<String>>,
        val mavenModule: String?,
        val isTestFile: Boolean
    )

    /**
     * P2-22 + B19 (2026-06-10 perf audit): ONE read action computing a plain-data
     * snapshot. The previous shape ran 5 sequential read actions, resolved the file
     * twice, and carried a [PsiClass] across read-action boundaries — a
     * PsiInvalidElementAccessException risk while the user types. No PSI element
     * escapes the lambda: [PsiContext] is pure data (strings/lists/maps/boolean).
     */
    suspend fun enrich(filePath: String): PsiContext = readAction {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return@readAction emptyContext()
        val psiFile = PsiManager.getInstance(project).findFile(vFile)
            ?: return@readAction emptyContext()
        val isTest = ProjectFileIndex.getInstance(project).isInTestSourceContent(vFile)
        val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
        PsiContext(
            className = psiClass?.qualifiedName,
            classAnnotations = psiClass?.let { extractAnnotations(it) } ?: emptyList(),
            methodAnnotations = psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap(),
            mavenModule = detectMavenModule(vFile),
            isTestFile = isTest
        )
    }

    private fun extractAnnotations(psiClass: PsiClass): List<String> {
        return psiClass.annotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
    }

    private fun extractMethodAnnotations(psiClass: PsiClass): Map<String, List<String>> {
        return psiClass.methods.associate { method ->
            method.name to method.annotations.mapNotNull {
                it.qualifiedName?.substringAfterLast('.')
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun detectMavenModule(vFile: VirtualFile): String? {
        return try {
            val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            if (!mavenManager.isMavenizedProject) return null
            mavenManager.projects.find { mavenProject ->
                VfsUtilCore.isAncestor(mavenProject.directoryFile, vFile, false)
            }?.mavenId?.artifactId
        } catch (_: Exception) {
            null
        }
    }

    private fun emptyContext() = PsiContext(
        className = null,
        classAnnotations = emptyList(),
        methodAnnotations = emptyMap(),
        mavenModule = null,
        isTestFile = false
    )
}
