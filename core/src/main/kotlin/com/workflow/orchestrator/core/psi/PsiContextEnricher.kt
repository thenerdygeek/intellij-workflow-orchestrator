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

    suspend fun enrich(filePath: String): PsiContext {
        // Read 1: Resolve file and basic structural info (fast)
        val basicInfo = readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return@readAction null
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@readAction null
            val fileIndex = ProjectFileIndex.getInstance(project)
            val isTest = fileIndex.isInTestSourceContent(vFile)
            val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
            Triple(psiFile, psiClass, isTest)
        } ?: return emptyContext()

        val (_, psiClass, isTest) = basicInfo

        // Read 2: Extract lightweight metadata (fast)
        val className = readAction { psiClass?.qualifiedName }
        val classAnnotations = readAction { psiClass?.let { extractAnnotations(it) } ?: emptyList() }
        val methodAnnotations = readAction { psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap() }

        // Read 3: Maven module detection
        val mavenModule = readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (vFile != null) detectMavenModule(vFile) else null
        }

        return PsiContext(
            className = className,
            classAnnotations = classAnnotations,
            methodAnnotations = methodAnnotations,
            mavenModule = mavenModule,
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
