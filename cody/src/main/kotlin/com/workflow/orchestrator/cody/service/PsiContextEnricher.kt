package com.workflow.orchestrator.cody.service

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFinderHelper

class PsiContextEnricher(private val project: Project) {

    data class PsiContext(
        val fileType: String,
        val packageName: String?,
        val className: String?,
        val classAnnotations: List<String>,
        val methodAnnotations: Map<String, List<String>>,
        val testFilePath: String?,
        val imports: List<String>,
        val mavenModule: String?,
        val relatedFiles: List<String>,
        val isTestFile: Boolean
    )

    suspend fun enrich(filePath: String): PsiContext {
        return readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return@readAction emptyContext(filePath)
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
                ?: return@readAction emptyContext(filePath)

            val fileIndex = ProjectFileIndex.getInstance(project)
            val isTest = fileIndex.isInTestSourceContent(vFile)

            val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)

            PsiContext(
                fileType = psiFile.fileType.name,
                packageName = (psiFile as? PsiJavaFile)?.packageName,
                className = psiClass?.qualifiedName,
                classAnnotations = psiClass?.let { extractAnnotations(it) } ?: emptyList(),
                methodAnnotations = psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap(),
                testFilePath = if (!isTest) psiClass?.let { findTestFile(it) } else null,
                imports = extractImports(psiFile),
                mavenModule = detectMavenModule(vFile),
                relatedFiles = psiClass?.let { findRelatedFiles(it) } ?: emptyList(),
                isTestFile = isTest
            )
        }
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

    private fun findTestFile(psiClass: PsiClass): String? {
        val tests = TestFinderHelper.findTestsForClass(psiClass)
        return tests.firstOrNull()?.containingFile?.virtualFile?.path
    }

    private fun extractImports(psiFile: PsiFile): List<String> {
        return (psiFile as? PsiJavaFile)?.importList?.allImportStatements
            ?.mapNotNull { it.text?.removePrefix("import ")?.removeSuffix(";")?.trim() }
            ?: emptyList()
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

    private fun findRelatedFiles(psiClass: PsiClass): List<String> {
        return try {
            val refs = ReferencesSearch.search(psiClass).findAll().take(10)
            refs.mapNotNull { ref ->
                ref.element.containingFile?.virtualFile?.path
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun emptyContext(filePath: String) = PsiContext(
        fileType = filePath.substringAfterLast('.', "unknown"),
        packageName = null,
        className = null,
        classAnnotations = emptyList(),
        methodAnnotations = emptyMap(),
        testFilePath = null,
        imports = emptyList(),
        mavenModule = null,
        relatedFiles = emptyList(),
        isTestFile = false
    )
}
