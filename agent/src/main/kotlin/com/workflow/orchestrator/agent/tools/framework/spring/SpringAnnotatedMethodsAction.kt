package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `spring(action=annotated_methods, annotation, filter?)` — generic
 * scanner for methods annotated with Spring annotations. Returns
 * one line per method with annotation-specific detail extracted
 * from the annotation's attributes.
 *
 * Annotation-specific branches implemented for @Scheduled,
 * @EventListener, @TransactionalEventListener, @Transactional,
 * @Cacheable/@CachePut/@CacheEvict, @Async, @PreAuthorize,
 * @PostAuthorize, @Secured. Any other FQN is accepted as a raw
 * annotation FQN — the method signature is reported without
 * attribute extraction.
 *
 * Replaces the specialized scheduled_tasks and event_listeners
 * actions (which still exist for backward compat until dispatch 3).
 */

// Short-name alias table (lower-case, no leading '@')
private val ANNOTATION_ALIASES: Map<String, String> = mapOf(
    "scheduled" to "org.springframework.scheduling.annotation.Scheduled",
    "eventlistener" to "org.springframework.context.event.EventListener",
    "event_listener" to "org.springframework.context.event.EventListener",
    "transactional" to "org.springframework.transaction.annotation.Transactional",
    "cacheable" to "org.springframework.cache.annotation.Cacheable",
    "cacheput" to "org.springframework.cache.annotation.CachePut",
    "cache_put" to "org.springframework.cache.annotation.CachePut",
    "cacheevict" to "org.springframework.cache.annotation.CacheEvict",
    "cache_evict" to "org.springframework.cache.annotation.CacheEvict",
    "async" to "org.springframework.scheduling.annotation.Async",
    "preauthorize" to "org.springframework.security.access.prepost.PreAuthorize",
    "pre_authorize" to "org.springframework.security.access.prepost.PreAuthorize",
    "postauthorize" to "org.springframework.security.access.prepost.PostAuthorize",
    "post_authorize" to "org.springframework.security.access.prepost.PostAuthorize",
    "secured" to "org.springframework.security.access.annotation.Secured",
    "transactionaleventlistener" to "org.springframework.transaction.event.TransactionalEventListener",
    "transactional_event_listener" to "org.springframework.transaction.event.TransactionalEventListener",
)

internal suspend fun executeAnnotatedMethods(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val annotationInput = params["annotation"]?.jsonPrimitive?.contentOrNull
        ?: return ToolResult(
            "Error: 'annotation' parameter required. Pass a short name (e.g. '@Scheduled', 'Transactional') or a full FQN.",
            "Error: missing annotation",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true,
        )
    val filter = params["filter"]?.jsonPrimitive?.contentOrNull

    val fqn = resolveAnnotationFqn(annotationInput)

    val content: String = try {
        ReadAction.nonBlocking<String> {
            scanAnnotatedMethods(project, fqn, filter)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: NoClassDefFoundError) {
        return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    } catch (e: Exception) {
        return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }

    return ToolResult(
        content = content,
        summary = "Annotated methods listed for $fqn",
        tokenEstimate = TokenEstimator.estimate(content),
    )
}

internal fun resolveAnnotationFqn(input: String): String {
    val key = input.removePrefix("@").trim().lowercase()
    ANNOTATION_ALIASES[key]?.let { return it }
    // Treat as raw FQN if it looks like one (contains a dot); otherwise return as-is
    return input.trim().removePrefix("@")
}

private fun scanAnnotatedMethods(project: Project, annotationFqn: String, filter: String?): String {
    val scope = GlobalSearchScope.projectScope(project)
    val allScope = GlobalSearchScope.allScope(project)
    val facade = JavaPsiFacade.getInstance(project)

    val annotationClass = facade.findClass(annotationFqn, allScope)
        ?: return "Annotation '$annotationFqn' not found on classpath. Check the FQN or ensure the relevant Spring dependency is on the project."

    val methods = AnnotatedElementsSearch.searchPsiMethods(annotationClass, scope).findAll()

    val filtered = if (filter.isNullOrBlank()) methods else methods.filter { m ->
        val cls = m.containingClass?.name.orEmpty()
        val mn = m.name
        cls.contains(filter, ignoreCase = true) || mn.contains(filter, ignoreCase = true)
    }

    if (filtered.isEmpty()) {
        val suffix = if (filter != null) " matching '$filter'" else ""
        return "No methods annotated with $annotationFqn$suffix found in project scope."
    }

    val sb = StringBuilder("Methods annotated with $annotationFqn (${filtered.size}):\n")
    for (method in filtered.sortedBy { "${it.containingClass?.qualifiedName ?: it.containingClass?.name}.${it.name}" }) {
        sb.appendLine("  ${formatMethod(method, annotationFqn)}")
    }
    return sb.toString().trimEnd()
}

private fun formatMethod(method: PsiMethod, annotationFqn: String): String {
    val className = method.containingClass?.name ?: "(anonymous)"
    val methodName = method.name
    val params = method.parameterList.parameters.joinToString(", ") { it.type.presentableText }
    val signature = "$className.$methodName($params)"

    val annotation = method.getAnnotation(annotationFqn) ?: return signature
    val detail = extractDetail(method, annotation, annotationFqn)
    return if (detail.isNotBlank()) "$signature — $detail" else signature
}

private fun extractDetail(method: PsiMethod, annotation: PsiAnnotation, annotationFqn: String): String {
    return when (annotationFqn) {
        "org.springframework.scheduling.annotation.Scheduled" -> extractScheduledDetail(annotation)
        "org.springframework.context.event.EventListener" -> extractEventListenerDetail(method, annotation)
        "org.springframework.transaction.event.TransactionalEventListener" -> extractTxEventListenerDetail(method, annotation)
        "org.springframework.transaction.annotation.Transactional" -> extractTransactionalDetail(annotation)
        "org.springframework.cache.annotation.Cacheable",
        "org.springframework.cache.annotation.CachePut",
        "org.springframework.cache.annotation.CacheEvict" -> extractCacheDetail(annotation)
        "org.springframework.scheduling.annotation.Async" -> extractAsyncDetail(annotation)
        "org.springframework.security.access.prepost.PreAuthorize",
        "org.springframework.security.access.prepost.PostAuthorize" -> extractAuthorizeDetail(annotation)
        "org.springframework.security.access.annotation.Secured" -> extractSecuredDetail(annotation)
        else -> ""
    }
}

private fun extractScheduledDetail(annotation: PsiAnnotation): String {
    val parts = mutableListOf<String>()
    annStr(annotation, "cron")?.let { parts.add("cron: \"$it\"") }
    annLong(annotation, "fixedRate")?.let { parts.add("fixedRate: ${it}ms") }
    annStr(annotation, "fixedRateString")?.let { parts.add("fixedRate: \"$it\"") }
    annLong(annotation, "fixedDelay")?.let { parts.add("fixedDelay: ${it}ms") }
    annStr(annotation, "fixedDelayString")?.let { parts.add("fixedDelay: \"$it\"") }
    annLong(annotation, "initialDelay")?.let { parts.add("initialDelay: ${it}ms") }
    annStr(annotation, "initialDelayString")?.let { parts.add("initialDelay: \"$it\"") }
    return if (parts.isEmpty()) "(no schedule params)" else parts.joinToString(", ")
}

private fun extractEventListenerDetail(method: PsiMethod, annotation: PsiAnnotation): String {
    val parts = mutableListOf<String>()
    val eventType = method.parameterList.parameters.firstOrNull()?.type?.presentableText
    if (eventType != null) parts.add("event=$eventType")
    annStr(annotation, "condition")?.let { parts.add("condition=\"$it\"") }
    val isAsync = method.getAnnotation("org.springframework.scheduling.annotation.Async") != null
    if (isAsync) parts.add("@Async")
    return parts.joinToString(", ")
}

private fun extractTxEventListenerDetail(method: PsiMethod, annotation: PsiAnnotation): String {
    val parts = mutableListOf<String>()
    val eventType = method.parameterList.parameters.firstOrNull()?.type?.presentableText
    if (eventType != null) parts.add("event=$eventType")
    annotation.findAttributeValue("phase")?.text?.substringAfterLast(".")?.takeIf { it.isNotBlank() }
        ?.let { parts.add("phase=$it") }
    return parts.joinToString(", ")
}

private fun extractTransactionalDetail(annotation: PsiAnnotation): String {
    val parts = mutableListOf<String>()
    annotation.findAttributeValue("propagation")?.text?.substringAfterLast(".")?.takeIf { it.isNotBlank() }
        ?.let { parts.add("propagation=$it") }
    annotation.findAttributeValue("isolation")?.text?.substringAfterLast(".")?.takeIf { it.isNotBlank() }
        ?.let { parts.add("isolation=$it") }
    annStr(annotation, "readOnly")?.takeIf { it == "true" }?.let { parts.add("readOnly") }
    annLong(annotation, "timeout")?.let { parts.add("timeout=${it}s") }
    return parts.joinToString(", ")
}

private fun extractCacheDetail(annotation: PsiAnnotation): String {
    val parts = mutableListOf<String>()
    (annStr(annotation, "value") ?: annStr(annotation, "cacheNames"))?.let { parts.add("cache=\"$it\"") }
    annStr(annotation, "key")?.let { parts.add("key=\"$it\"") }
    annStr(annotation, "condition")?.let { parts.add("condition=\"$it\"") }
    return parts.joinToString(", ")
}

private fun extractAsyncDetail(annotation: PsiAnnotation): String {
    return annStr(annotation, "value")?.let { "executor=\"$it\"" } ?: ""
}

private fun extractAuthorizeDetail(annotation: PsiAnnotation): String {
    return annStr(annotation, "value")?.let { "\"$it\"" } ?: ""
}

private fun extractSecuredDetail(annotation: PsiAnnotation): String {
    val value = annotation.findAttributeValue("value")?.text ?: return ""
    return value
}

private fun annStr(annotation: PsiAnnotation, name: String): String? {
    val value = annotation.findAttributeValue(name) ?: return null
    val text = value.text?.removeSurrounding("\"") ?: return null
    return if (text.isNotBlank() && text != "\"\"") text else null
}

private fun annLong(annotation: PsiAnnotation, name: String): Long? {
    val value = annotation.findAttributeValue(name) ?: return null
    val text = value.text ?: return null
    val parsed = text.removeSuffix("L").removeSuffix("l").toLongOrNull()
    return if (parsed != null && parsed >= 0) parsed else null
}
