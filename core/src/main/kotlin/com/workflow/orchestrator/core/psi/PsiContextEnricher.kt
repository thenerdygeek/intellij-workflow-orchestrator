package com.workflow.orchestrator.core.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
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
        } ?: return emptyContext(filePath)

        val (psiFile, psiClass, isTest) = basicInfo

        // Read 2: Extract lightweight metadata (fast)
        val fileType = readAction { psiFile.fileType.name }
        val packageName = readAction { (psiFile as? PsiJavaFile)?.packageName }
        val className = readAction { psiClass?.qualifiedName }
        val classAnnotations = readAction { psiClass?.let { extractAnnotations(it) } ?: emptyList() }
        val methodAnnotations = readAction { psiClass?.let { extractMethodAnnotations(it) } ?: emptyMap() }
        val imports = readAction { extractImports(psiFile) }

        // Read 3: Find test file (potentially slow due to index lookups)
        val testFilePath = if (!isTest && psiClass != null) {
            readAction { findTestFile(psiClass) }
        } else null

        // Read 4: Maven module detection
        val mavenModule = readAction {
            val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            if (vFile != null) detectMavenModule(vFile) else null
        }

        // Read 5: Related files via ReferencesSearch (slowest — use non-blocking
        // read action that auto-cancels when a write action comes in and retries,
        // preventing long read-lock holds that block editor writes)
        val relatedFiles = if (psiClass != null) {
            try {
                ReadAction.nonBlocking<List<String>> { findRelatedFiles(psiClass) }
                    .inSmartMode(project)
                    .executeSynchronously()
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        return PsiContext(
            fileType = fileType,
            packageName = packageName,
            className = className,
            classAnnotations = classAnnotations,
            methodAnnotations = methodAnnotations,
            testFilePath = testFilePath,
            imports = imports,
            mavenModule = mavenModule,
            relatedFiles = relatedFiles,
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
            ProgressManager.checkCanceled()
            val refs = ReferencesSearch.search(psiClass).findAll().take(10)
            refs.mapNotNull { ref ->
                ProgressManager.checkCanceled()
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
