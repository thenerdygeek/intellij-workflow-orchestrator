package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

private val securityPatterns = listOf(
    "authorizeHttpRequests", "authorizeRequests",
    "oauth2Login", "oauth2ResourceServer",
    "httpBasic", "formLogin",
    "csrf", "cors",
    "sessionManagement", "headers",
    "exceptionHandling", "rememberMe"
)

internal suspend fun executeSecurityConfig(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val content: String = try {
        ReadAction.nonBlocking<String> {
            collectSecurityConfig(project)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Spring Security config analyzed",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun collectSecurityConfig(project: Project): String {
    val scope = GlobalSearchScope.projectScope(project)
    val allScope = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val sb = StringBuilder("Spring Security:\n")
    var foundAnything = false

    val enableWebSecurityClass = facade.findClass(
        "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
        allScope
    )
    val securityConfigClasses = if (enableWebSecurityClass != null) {
        AnnotatedElementsSearch.searchPsiClasses(enableWebSecurityClass, scope).findAll()
    } else {
        emptyList()
    }

    if (securityConfigClasses.isNotEmpty()) {
        foundAnything = true
        for (cls in securityConfigClasses) {
            sb.appendLine("  Config class: ${cls.name} (@EnableWebSecurity)")

            val filterChainMethods = cls.methods.filter { method ->
                method.returnType?.canonicalText?.contains("SecurityFilterChain") == true
            }

            if (filterChainMethods.isNotEmpty()) {
                val detectedPatterns = mutableSetOf<String>()
                for (method in filterChainMethods) {
                    val bodyText = method.body?.text ?: continue
                    for (pattern in securityPatterns) {
                        if (bodyText.contains(pattern)) {
                            detectedPatterns.add(pattern)
                        }
                    }
                }
                if (detectedPatterns.isNotEmpty()) {
                    sb.appendLine("  Security chain methods: ${detectedPatterns.sorted().joinToString(", ")}")
                }
            }
        }
    }

    val preAuthorizeMethods = mutableListOf<MethodSecurityInfo>()
    val preAuthorizeClass = facade.findClass(
        "org.springframework.security.access.prepost.PreAuthorize",
        allScope
    )
    if (preAuthorizeClass != null) {
        val methods = AnnotatedElementsSearch.searchPsiMethods(preAuthorizeClass, scope).findAll()
        for (method in methods) {
            val annotation = method.getAnnotation(
                "org.springframework.security.access.prepost.PreAuthorize"
            ) ?: continue
            val value = annotation.findAttributeValue("value")?.text
                ?.removeSurrounding("\"") ?: ""
            preAuthorizeMethods.add(
                MethodSecurityInfo(
                    className = method.containingClass?.name ?: "(anonymous)",
                    methodName = method.name,
                    annotationType = "@PreAuthorize",
                    expression = value
                )
            )
        }
    }

    val securedClass = facade.findClass(
        "org.springframework.security.access.annotation.Secured",
        allScope
    )
    if (securedClass != null) {
        val methods = AnnotatedElementsSearch.searchPsiMethods(securedClass, scope).findAll()
        for (method in methods) {
            val annotation = method.getAnnotation(
                "org.springframework.security.access.annotation.Secured"
            ) ?: continue
            val value = annotation.findAttributeValue("value")?.text
                ?.removeSurrounding("\"")
                ?.removeSurrounding("{", "}") ?: ""
            preAuthorizeMethods.add(
                MethodSecurityInfo(
                    className = method.containingClass?.name ?: "(anonymous)",
                    methodName = method.name,
                    annotationType = "@Secured",
                    expression = value
                )
            )
        }
    }

    val rolesAllowedClass = facade.findClass("jakarta.annotation.security.RolesAllowed", allScope)
        ?: facade.findClass("javax.annotation.security.RolesAllowed", allScope)
    if (rolesAllowedClass != null) {
        val methods = AnnotatedElementsSearch.searchPsiMethods(rolesAllowedClass, scope).findAll()
        for (method in methods) {
            val annotation = method.getAnnotation("jakarta.annotation.security.RolesAllowed")
                ?: method.getAnnotation("javax.annotation.security.RolesAllowed") ?: continue
            val value = annotation.findAttributeValue("value")?.text
                ?.removeSurrounding("\"")?.removeSurrounding("{", "}") ?: ""
            preAuthorizeMethods.add(MethodSecurityInfo(
                className = method.containingClass?.name ?: "(anonymous)",
                methodName = method.name,
                annotationType = "@RolesAllowed",
                expression = value
            ))
        }
    }

    if (preAuthorizeMethods.isNotEmpty()) {
        foundAnything = true
        sb.appendLine("  Method security:")
        for (info in preAuthorizeMethods.take(50).sortedBy { "${it.className}.${it.methodName}" }) {
            sb.appendLine("    ${info.className}.${info.methodName}() — ${info.annotationType}(${info.expression})")
        }
        if (preAuthorizeMethods.size > 50) {
            sb.appendLine("    ... (${preAuthorizeMethods.size - 50} more not shown)")
        }
    }

    if (!foundAnything) {
        return "No Spring Security configuration found in project."
    }

    return sb.toString().trimEnd()
}

private data class MethodSecurityInfo(
    val className: String,
    val methodName: String,
    val annotationType: String,
    val expression: String
)
