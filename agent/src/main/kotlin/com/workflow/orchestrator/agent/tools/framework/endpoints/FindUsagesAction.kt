package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlResolveRequest
import com.intellij.microservices.url.UrlResolverManager
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `endpoints.find_usages` — resolves a URL string to every handler AND every
 * client call site that IntelliJ's microservices framework knows about.
 *
 * This covers string-literal usages that plain PSI find-references misses:
 *   - `webClient.get("/api/users/" + id)` — a Spring WebClient call
 *   - `@FeignClient` interface method with `@GetMapping("/api/users/{id}")`
 *   - `.http` scratch files that request the URL
 *   - OpenAPI spec files that document the URL
 *
 * Caller must verify
 * [com.workflow.orchestrator.agent.ide.MicroservicesDetector.isAvailable]
 * before invoking.
 */
internal suspend fun executeFindUsages(params: JsonObject, project: Project): ToolResult {
    val url = params["url"]?.jsonPrimitive?.content
    if (url.isNullOrBlank()) {
        return ToolResult(
            content = "Error: 'url' parameter required for find_usages",
            summary = "Error: missing url",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true,
        )
    }

    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val methodHint = params["method"]?.jsonPrimitive?.contentOrNull?.uppercase()

    val content = ReadAction.nonBlocking<String> {
        val resolver = UrlResolverManager.getInstance(project)
        val request = buildResolveRequest(url, methodHint)
        val targets = resolver.resolve(request).toList()
        renderUsages(url, targets)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Usages for $url",
        tokenEstimate = TokenEstimator.estimate(content),
    )
}

/**
 * Build a [UrlResolveRequest] from a free-form URL string.
 *
 * Positional 4-arg constructor: (schemeHint, authorityHint, path, method).
 * `schemeHint` must NOT include `"://"`.
 */
private fun buildResolveRequest(url: String, method: String?): UrlResolveRequest {
    val (schemePart, rest) = splitScheme(url)
    val (authorityText, pathText) = splitAuthorityAndPath(rest)
    val path = UrlPath.fromExactString(pathText)
    return UrlResolveRequest(schemePart, authorityText, path, method)
}

private fun splitScheme(url: String): Pair<String?, String> {
    val i = url.indexOf("://")
    if (i < 0) return null to url
    return url.substring(0, i) to url.substring(i + 3)
}

private fun splitAuthorityAndPath(rest: String): Pair<String?, String> {
    if (!rest.startsWith("/")) {
        val slash = rest.indexOf('/')
        return if (slash < 0) rest to "/" else rest.substring(0, slash) to rest.substring(slash)
    }
    return null to rest
}

private fun renderUsages(url: String, targets: List<UrlTargetInfo>): String {
    if (targets.isEmpty()) return "No usages found for '$url'."

    val sb = StringBuilder()
    sb.appendLine("Usages of '$url' (${targets.size}):")
    sb.appendLine()
    for (t in targets.take(100)) {
        val psi = t.resolveToPsiElement()
        val methods = t.methods.joinToString(",").ifBlank { "ANY" }
        val file = psi?.containingFile?.virtualFile?.path
        val line = psi?.let { el ->
            val containing = el.containingFile ?: return@let null
            PsiDocumentManager.getInstance(el.project).getDocument(containing)
                ?.getLineNumber(el.textOffset)?.plus(1)
        }
        sb.appendLine("[$methods] ${t.source}")
        if (file != null) sb.appendLine("  $file${line?.let { ":$it" } ?: ""}")
        sb.appendLine()
    }
    if (targets.size > 100) sb.appendLine("... (${targets.size - 100} more not shown)")
    return sb.toString().trimEnd()
}
