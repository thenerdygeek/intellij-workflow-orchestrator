package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

private val repositoryFqns = listOf(
    "org.springframework.data.jpa.repository.JpaRepository",
    "org.springframework.data.repository.CrudRepository",
    "org.springframework.data.repository.PagingAndSortingRepository",
    "org.springframework.data.repository.reactive.ReactiveCrudRepository",
    "org.springframework.data.mongodb.repository.MongoRepository"
)

internal suspend fun executeRepositories(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectRepositories(project)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Spring repositories listed",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectRepositories(project: Project): String {
    val scope = GlobalSearchScope.projectScope(project)
    val allScope = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val repositories = mutableListOf<RepositoryInfo>()
    val processedFqns = mutableSetOf<String>()

    for (repoFqn in repositoryFqns) {
        val baseClass = facade.findClass(repoFqn, allScope) ?: continue
        val inheritors = ClassInheritorsSearch.search(baseClass, scope, true).findAll()

        for (inheritor in inheritors) {
            val fqn = inheritor.qualifiedName ?: continue
            if (!processedFqns.add(fqn)) continue
            if (!inheritor.isInterface) continue

            val repoSimpleName = repoFqn.substringAfterLast('.')
            val typeArgs = extractTypeArguments(inheritor, repoFqn)
            val entityType = typeArgs.getOrNull(0) ?: "?"
            val idType = typeArgs.getOrNull(1) ?: "?"

            val customMethods = inheritor.methods.filter { !isDefaultRepositoryMethod(it) }
                .take(20)
                .map { method ->
                    val queryAnnotation = method.getAnnotation("org.springframework.data.jpa.repository.Query")
                    val queryValue = queryAnnotation?.findAttributeValue("value")?.text
                        ?.removeSurrounding("\"")
                    val returnType = method.returnType?.presentableText ?: "void"
                    val methodParams = method.parameterList.parameters.joinToString(", ") { p ->
                        "${p.name}: ${p.type.presentableText}"
                    }
                    val isModifying = method.getAnnotation("org.springframework.data.jpa.repository.Modifying") != null
                    val isTransactional = method.getAnnotation("org.springframework.transaction.annotation.Transactional") != null
                    RepoMethodInfo(
                        name = method.name,
                        params = methodParams,
                        returnType = returnType,
                        query = queryValue,
                        isModifying = isModifying,
                        isTransactional = isTransactional
                    )
                }

            repositories.add(
                RepositoryInfo(
                    name = inheritor.name ?: "(anonymous)",
                    extendsType = repoSimpleName,
                    entityType = entityType,
                    idType = idType,
                    customMethods = customMethods
                )
            )
        }
    }

    if (repositories.isEmpty()) {
        return "No Spring Data repository interfaces found in project."
    }

    val sb = StringBuilder("Spring Data Repositories (${repositories.size}):\n")
    for (repo in repositories.sortedBy { it.name }) {
        sb.appendLine("  ${repo.name} extends ${repo.extendsType}<${repo.entityType}, ${repo.idType}>")
        for (method in repo.customMethods) {
            val queryTag = if (method.query != null) "@Query(\"${method.query}\") " else ""
            val modifyingTag = if (method.isModifying) "@Modifying " else ""
            val transactionalTag = if (method.isTransactional) "@Transactional " else ""
            sb.appendLine("    $modifyingTag$transactionalTag$queryTag${method.name}(${method.params}): ${method.returnType}")
        }
    }
    return sb.toString().trimEnd()
}

private fun extractTypeArguments(psiClass: PsiClass, targetSuperFqn: String): List<String> {
    for (superType in psiClass.extendsListTypes + psiClass.implementsListTypes) {
        val resolved = superType.resolve() ?: continue
        if (resolved.qualifiedName == targetSuperFqn) {
            return superType.parameters.map { it.presentableText }
        }
    }
    for (superType in psiClass.superTypes) {
        val resolved = superType.resolve() ?: continue
        if (resolved.qualifiedName == targetSuperFqn) {
            return superType.parameters.map { it.presentableText }
        }
    }
    return emptyList()
}

private fun isDefaultRepositoryMethod(method: PsiMethod): Boolean {
    val defaultMethods = setOf(
        "save", "saveAll", "findById", "existsById", "findAll", "findAllById",
        "count", "deleteById", "delete", "deleteAllById", "deleteAll",
        "flush", "saveAndFlush", "saveAllAndFlush", "getById", "getReferenceById",
        "deleteAllInBatch", "deleteInBatch", "getOne"
    )
    return method.name in defaultMethods
}

private data class RepositoryInfo(
    val name: String,
    val extendsType: String,
    val entityType: String,
    val idType: String,
    val customMethods: List<RepoMethodInfo>
)

private data class RepoMethodInfo(
    val name: String,
    val params: String,
    val returnType: String,
    val query: String?,
    val isModifying: Boolean = false,
    val isTransactional: Boolean = false
)
