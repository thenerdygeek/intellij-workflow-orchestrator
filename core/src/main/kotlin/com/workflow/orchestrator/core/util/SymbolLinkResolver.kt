package com.workflow.orchestrator.core.util

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope

class SymbolLinkResolver(private val project: Project) {

    suspend fun resolveAll(hrefs: List<String>): List<ValidatedPath> =
        hrefs.mapNotNull { resolve(it) }

    suspend fun resolve(href: String): ValidatedPath? {
        val fqn = href.removePrefix("symbol:")
        if (fqn.isBlank()) return null

        val (classFqn, memberName) = if ('#' in fqn)
            fqn.substringBefore('#') to fqn.substringAfter('#')
        else fqn to null

        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val element: PsiElement = readAction {
            val cls = facade.findClass(classFqn, scope) ?: return@readAction null
            if (memberName == null) return@readAction cls
            cls.findMethodsByName(memberName, true).firstOrNull()
                ?: cls.findFieldByName(memberName, true)
        } ?: return null

        return readAction {
            val vFile = element.containingFile?.virtualFile ?: return@readAction null
            val doc = PsiDocumentManager.getInstance(project)
                .getDocument(element.containingFile) ?: return@readAction null
            val line = doc.getLineNumber(element.textOffset)
            val col = element.textOffset - doc.getLineStartOffset(line)
            ValidatedPath(
                input = href,
                canonicalPath = vFile.path,
                line = line,
                column = col,
            )
        }
    }
}
