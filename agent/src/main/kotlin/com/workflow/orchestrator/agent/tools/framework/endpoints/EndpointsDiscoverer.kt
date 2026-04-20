package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.EndpointsUrlTargetProvider
import com.intellij.microservices.endpoints.ModuleEndpointsFilter
import com.intellij.microservices.endpoints.presentation.EndpointMethodPresentation
import com.intellij.microservices.url.Authority
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

/**
 * Normalized endpoint record produced by [EndpointsDiscoverer].
 *
 * Populated from either the rich [EndpointsUrlTargetProvider] path
 * (preferred; provides structured URL + HTTP methods) or from the
 * presentation fallback (base [EndpointsProvider]; e.g. OpenAPI definitions
 * that only expose [com.intellij.navigation.ItemPresentation]).
 */
internal data class DiscoveredEndpoint(
    val framework: String,
    val httpMethods: List<String>,
    val url: String,
    val source: String,
    val handlerClass: String?,
    val handlerMethod: String?,
    val filePath: String?,
    val lineNumber: Int?,
    val endpointType: String,
    /** Held for follow-up resolution (find_usages, export_openapi). Nullable to allow fallback rows. */
    val urlTargetInfo: UrlTargetInfo?,
)

/**
 * Iterates every registered [EndpointsProvider] and returns one
 * [DiscoveredEndpoint] per URL target. Caller must invoke under a read
 * action in smart mode.
 *
 * Caller must also verify
 * [com.workflow.orchestrator.agent.ide.MicroservicesDetector.isAvailable]
 * before constructing this class — the file itself references microservices
 * classes directly and will fail to load on IntelliJ Community.
 */
internal object EndpointsDiscoverer {

    fun discover(project: Project): List<DiscoveredEndpoint> {
        if (!EndpointsProvider.hasAnyProviders()) return emptyList()

        val providers = EndpointsProvider.getAvailableProviders(project).toList()
        if (providers.isEmpty()) return emptyList()

        val modules = ModuleManager.getInstance(project).modules
        val results = mutableListOf<DiscoveredEndpoint>()

        for (provider in providers) {
            val framework = provider.presentation.title
            val endpointTypeTag = provider.endpointType.queryTag

            for (module in modules) {
                val filter: EndpointsFilter = ModuleEndpointsFilter(
                    module = module,
                    fromLibraries = false,
                    fromTests = false,
                )

                @Suppress("UNCHECKED_CAST")
                val typed = provider as EndpointsProvider<Any, Any>
                val groups = typed.getEndpointGroups(project, filter)

                for (group in groups) {
                    for (endpoint in typed.getEndpoints(group)) {
                        if (!typed.isValidEndpoint(group, endpoint)) continue
                        collectEndpoint(typed, group, endpoint, framework, endpointTypeTag, results)
                    }
                }
            }
        }
        return results
    }

    private fun collectEndpoint(
        typed: EndpointsProvider<Any, Any>,
        group: Any,
        endpoint: Any,
        framework: String,
        endpointTypeTag: String,
        out: MutableList<DiscoveredEndpoint>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val urlTargetProvider = typed as? EndpointsUrlTargetProvider<Any, Any>
        val urlTargets = urlTargetProvider?.getUrlTargetInfo(group, endpoint)?.toList().orEmpty()

        if (urlTargets.isNotEmpty()) {
            urlTargets.forEach { target ->
                out += target.toDiscovered(framework, endpointTypeTag)
            }
            return
        }

        // Fallback: providers that only supply ItemPresentation (e.g. OpenAPI definitions).
        val presentation = typed.getEndpointPresentation(group, endpoint)
        val methodNames = (presentation as? EndpointMethodPresentation)?.endpointMethods.orEmpty()
        val psi = typed.getNavigationElement(group, endpoint)
        out += DiscoveredEndpoint(
            framework = framework,
            httpMethods = methodNames,
            url = presentation.presentableText.orEmpty(),
            source = presentation.locationString.orEmpty(),
            handlerClass = psi?.handlerClassName(),
            handlerMethod = psi?.handlerMethodName(),
            filePath = psi?.containingFile?.virtualFile?.path,
            lineNumber = psi?.lineNumber(),
            endpointType = endpointTypeTag,
            urlTargetInfo = null,
        )
    }

    private fun UrlTargetInfo.toDiscovered(framework: String, typeTag: String): DiscoveredEndpoint {
        val psi = resolveToPsiElement()
        val pathStr = path.getPresentation(HttpUrlRenderer)
        val authority = authorities.firstExact()
        val scheme = schemes.firstOrNull()
        val url = when {
            scheme != null && authority != null -> "$scheme$authority$pathStr"
            else -> pathStr
        }
        return DiscoveredEndpoint(
            framework = framework,
            httpMethods = methods.sorted(),
            url = url,
            source = source,
            handlerClass = psi?.handlerClassName(),
            handlerMethod = psi?.handlerMethodName(),
            filePath = psi?.containingFile?.virtualFile?.path,
            lineNumber = psi?.lineNumber(),
            endpointType = typeTag,
            urlTargetInfo = this,
        )
    }

    private object HttpUrlRenderer : UrlPath.PathSegmentRenderer {
        override fun visitVariable(variable: UrlPath.PathSegment.Variable): String =
            "{${variable.variableName ?: "var"}}"
    }

    private fun List<Authority>.firstExact(): String? =
        firstNotNullOfOrNull { (it as? Authority.Exact)?.text }

    private fun PsiElement.handlerClassName(): String? {
        var e: PsiElement? = this
        while (e != null) {
            if (e is PsiClass) return e.qualifiedName
            e = e.parent
        }
        return null
    }

    private fun PsiElement.handlerMethodName(): String? =
        (this as? PsiMethod)?.name
            ?: generateSequence(this) { it.parent }
                .filterIsInstance<PsiMethod>()
                .firstOrNull()?.name

    private fun PsiElement.lineNumber(): Int? {
        val file = containingFile ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        return doc.getLineNumber(textOffset) + 1
    }
}
