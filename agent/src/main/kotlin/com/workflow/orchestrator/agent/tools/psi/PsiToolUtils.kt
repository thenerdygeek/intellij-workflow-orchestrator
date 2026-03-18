package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.tools.ToolResult

object PsiToolUtils {

    fun isDumb(project: Project): Boolean = DumbService.isDumb(project)

    fun dumbModeError(): ToolResult = ToolResult(
        content = "Error: IDE is still indexing. Try again in a few seconds.",
        summary = "Error: indexing in progress",
        tokenEstimate = 10,
        isError = true
    )

    /** Find a PsiClass by fully qualified name or simple name. */
    fun findClass(project: Project, className: String): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        // Try fully qualified first
        facade.findClass(className, scope)?.let { return it }

        // Fall back to short name search
        val shortName = className.substringAfterLast('.')
        val classes = facade.findClasses(shortName, scope)
        // Prefer exact simple name match, then first result
        return classes.firstOrNull { it.name == shortName }
    }

    /** Format a PsiMethod signature as a concise string. */
    fun formatMethodSignature(method: PsiMethod): String {
        val modifiers = method.modifierList.text.trim()
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { p ->
            "${p.type.presentableText} ${p.name}"
        }
        val annotations = method.annotations
            .filter { it.qualifiedName?.startsWith("org.springframework") == true || it.qualifiedName?.startsWith("jakarta") == true }
            .joinToString(" ") { "@${it.qualifiedName?.substringAfterLast('.') ?: ""}" }
        val prefix = if (annotations.isNotBlank()) "$annotations " else ""
        return "$prefix$modifiers $returnType ${method.name}($params)"
    }

    /** Format a PsiClass as a skeleton (signatures only, no bodies). */
    fun formatClassSkeleton(psiClass: PsiClass): String {
        val sb = StringBuilder()
        // Package
        (psiClass.containingFile as? PsiJavaFile)?.packageName?.let {
            if (it.isNotBlank()) sb.appendLine("package $it;")
        }
        // Class declaration
        val superTypes = psiClass.superTypes.map { it.presentableText }
        val extendsClause = if (superTypes.isNotEmpty()) " extends/implements ${superTypes.joinToString(", ")}" else ""
        sb.appendLine("${psiClass.modifierList?.text ?: ""} class ${psiClass.name}$extendsClause {")

        // Fields
        psiClass.fields.forEach { field ->
            sb.appendLine("    ${field.modifierList?.text ?: ""} ${field.type.presentableText} ${field.name};")
        }

        // Methods (signature only)
        psiClass.methods.forEach { method ->
            sb.appendLine("    ${formatMethodSignature(method)};")
        }

        sb.appendLine("}")
        return sb.toString()
    }
}
