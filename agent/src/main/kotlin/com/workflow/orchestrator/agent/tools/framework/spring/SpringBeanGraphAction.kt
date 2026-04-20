package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.integration.ToolValidation
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `spring(action=bean_graph, bean_name=...)` — resolves any bean form
 * through the Ultimate Spring plugin's bean model ([SpringModelResolver])
 * and emits a two-sided dependency graph: what this bean injects
 * (dependencies) and which other beans inject it (consumers).
 *
 * Accepts every name form the Spring model recognizes:
 * - canonical bean name and aliases (`@Bean("myBean")`, `@Component("x")`)
 * - simple or fully-qualified class name (stereotype beans, XML-backed)
 * - `@Bean` method name (factory beans)
 *
 * Dependency extraction:
 * - Class-backed bean (stereotype / XML): primary constructor parameters,
 *   `@Autowired` fields, `@Autowired` setter methods.
 * - Method-backed bean (`@Bean` factory): the method's parameters — Spring
 *   autowires them implicitly from the context.
 *
 * Consumer detection walks every bean in every model (not only stereotype
 * classes, unlike the previous PSI-only implementation) and inspects each
 * bean's defining PSI element for injection sites that match the target's
 * effective types. This catches injection into `@Bean` methods and XML
 * beans, which the old scan missed.
 */
internal suspend fun executeBeanGraph(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    val beanName = params["bean_name"]?.jsonPrimitive?.contentOrNull
        ?: return ToolValidation.missingParam("bean_name")

    val content = ReadAction.nonBlocking<String> {
        buildGraph(project, beanName)
    }.inSmartMode(project).executeSynchronously()

    return ToolResult(
        content = content,
        summary = "Bean dependency graph for '$beanName'",
        tokenEstimate = TokenEstimator.estimate(content)
    )
}

private fun buildGraph(project: Project, beanName: String): String {
    val pointer = SpringModelResolver.findBean(project, beanName)
        ?: return "No bean '$beanName' found. Searched: bean names, aliases, class names (simple or fully qualified), and @Bean method names across all Spring models in this project."

    val bean = SpringModelResolver.pointerSpringBean(pointer)
    val definingElement = bean?.let { SpringModelResolver.beanDefiningElement(it) }

    val sb = StringBuilder()
    appendHeader(sb, pointer, bean, definingElement, fallbackName = beanName)
    sb.appendLine()

    sb.appendLine("Dependencies (what this bean injects):")
    val deps = extractDependencies(definingElement)
    if (deps.isEmpty()) {
        sb.appendLine("  (no dependencies)")
    } else {
        deps.forEach { sb.appendLine("  ${it.render()}") }
    }

    sb.appendLine()
    sb.appendLine("Consumers (what injects this bean):")
    val consumers = findConsumers(project, pointer)
    if (consumers.isEmpty()) {
        sb.appendLine("  (no consumers found)")
    } else {
        consumers.forEach { sb.appendLine("  ${it.render()}") }
    }

    return sb.toString()
}

private fun appendHeader(
    sb: StringBuilder,
    pointer: Any,
    bean: Any?,
    definingElement: PsiElement?,
    fallbackName: String,
) {
    val name = SpringModelResolver.pointerName(pointer) ?: bean?.let { SpringModelResolver.beanName(it) } ?: fallbackName
    val aliases = SpringModelResolver.pointerAliases(pointer)
    val beanClass = SpringModelResolver.pointerBeanClass(pointer)

    sb.appendLine("Bean: $name")
    if (aliases.isNotEmpty()) sb.appendLine("Aliases: ${aliases.joinToString(", ")}")
    beanClass?.qualifiedName?.let { sb.appendLine("Type: $it") }

    when (definingElement) {
        is PsiMethod -> {
            val containing = definingElement.containingClass?.qualifiedName
            sb.appendLine("Definition: @Bean method `${definingElement.name}` in $containing")
        }
        is PsiClass -> {
            val stereotype = detectStereotype(definingElement)
            sb.appendLine("Definition: $stereotype ${definingElement.qualifiedName ?: definingElement.name}")
        }
        else -> if (definingElement != null) {
            sb.appendLine("Definition: ${definingElement::class.simpleName}")
        }
    }

    if (bean != null) {
        val scope = SpringModelResolver.beanScope(bean)
        val profile = SpringModelResolver.beanProfile(bean)
        val primary = SpringModelResolver.beanIsPrimary(bean)
        val meta = buildList {
            if (!scope.isNullOrBlank()) add("scope=$scope")
            if (!profile.isNullOrBlank() && profile != "DefaultSpringProfile") add("profile=$profile")
            if (primary) add("@Primary")
        }
        if (meta.isNotEmpty()) sb.appendLine("Metadata: ${meta.joinToString(", ")}")
    }
}

private fun extractDependencies(defining: PsiElement?): List<Dependency> {
    return when (defining) {
        is PsiClass -> extractFromClass(defining)
        is PsiMethod -> extractFromBeanMethod(defining)
        else -> emptyList()
    }
}

private fun extractFromClass(cls: PsiClass): List<Dependency> {
    val deps = mutableListOf<Dependency>()
    cls.constructors.firstOrNull()?.parameterList?.parameters?.forEach { p ->
        deps += dependencyFromParam(p, "constructor")
    }
    cls.fields.forEach { field ->
        if (field.getAnnotation(FQN_AUTOWIRED) != null) deps += dependencyFromField(field)
    }
    cls.methods.forEach { m ->
        if (m.getAnnotation(FQN_AUTOWIRED) != null && m.name.startsWith("set") && m.parameterList.parametersCount > 0) {
            m.parameterList.parameters.forEach { p ->
                deps += dependencyFromParam(p, "setter")
            }
        }
    }
    return deps
}

private fun extractFromBeanMethod(method: PsiMethod): List<Dependency> =
    method.parameterList.parameters.map { dependencyFromParam(it, "parameter") }

private fun dependencyFromParam(param: PsiParameter, kind: String): Dependency {
    val qualifier = param.getAnnotation(FQN_QUALIFIER)
        ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
    return Dependency(param.name, param.type.presentableText, kind, qualifier)
}

private fun dependencyFromField(field: PsiField): Dependency {
    val qualifier = field.getAnnotation(FQN_QUALIFIER)
        ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
    return Dependency(field.name, field.type.presentableText, "field", qualifier)
}

private fun findConsumers(project: Project, targetPointer: Any): List<Consumer> {
    val targetTypes = SpringModelResolver.pointerEffectiveTypes(targetPointer)
    val targetClasses = targetTypes.mapNotNullTo(mutableSetOf()) { it.canonicalTextOrNull() }
    val targetClass = SpringModelResolver.pointerBeanClass(targetPointer)
    val targetName = SpringModelResolver.pointerName(targetPointer)

    val consumers = mutableListOf<Consumer>()
    val seen = mutableSetOf<String>()

    for (bean in SpringModelResolver.allBeans(project)) {
        val defining = SpringModelResolver.beanDefiningElement(bean) ?: continue
        val candidateLabel = labelFor(bean, defining)
        if (!seen.add(candidateLabel)) continue

        val injectionEdges = when (defining) {
            is PsiClass -> injectionPointsOfClass(defining)
            is PsiMethod -> injectionPointsOfMethod(defining)
            else -> emptyList()
        }

        for (edge in injectionEdges) {
            val typeMatch = edge.typeFqn in targetClasses ||
                targetClass?.let { isAssignable(it, edge.typeFqn, project) } == true
            val qualifierMatch = edge.qualifier != null && edge.qualifier == targetName
            if (typeMatch || qualifierMatch) {
                consumers += Consumer(candidateLabel, edge.kind, edge.name)
                break
            }
        }
    }
    return consumers.take(30)
}

private data class InjectionEdge(
    val kind: String,
    val name: String,
    val typeFqn: String,
    val qualifier: String?,
)

private fun injectionPointsOfClass(cls: PsiClass): List<InjectionEdge> {
    val out = mutableListOf<InjectionEdge>()
    cls.constructors.firstOrNull()?.parameterList?.parameters?.forEach { p ->
        out += InjectionEdge("constructor", p.name, p.type.canonicalText, qualifierFor(p))
    }
    cls.fields.filter { it.getAnnotation(FQN_AUTOWIRED) != null }.forEach { f ->
        out += InjectionEdge("field", f.name, f.type.canonicalText, qualifierFor(f))
    }
    cls.methods.filter {
        it.getAnnotation(FQN_AUTOWIRED) != null && it.name.startsWith("set") && it.parameterList.parametersCount > 0
    }.forEach { m ->
        m.parameterList.parameters.forEach { p ->
            out += InjectionEdge("setter", p.name, p.type.canonicalText, qualifierFor(p))
        }
    }
    return out
}

private fun injectionPointsOfMethod(method: PsiMethod): List<InjectionEdge> =
    method.parameterList.parameters.map { p ->
        InjectionEdge("method-param", p.name, p.type.canonicalText, qualifierFor(p))
    }

private fun qualifierFor(element: PsiElement): String? {
    val owner = element as? com.intellij.psi.PsiModifierListOwner ?: return null
    return owner.getAnnotation(FQN_QUALIFIER)
        ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
}

private fun labelFor(bean: Any, defining: PsiElement): String = when (defining) {
    is PsiMethod -> {
        val owner = defining.containingClass?.qualifiedName ?: "?"
        val name = SpringModelResolver.beanName(bean) ?: defining.name
        "$owner#${defining.name} (@Bean $name)"
    }
    is PsiClass -> defining.qualifiedName ?: defining.name ?: "unknown"
    else -> SpringModelResolver.beanName(bean) ?: "unknown"
}

private fun isAssignable(target: PsiClass, typeFqn: String, project: Project): Boolean {
    val typeClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
        .findClass(typeFqn, com.intellij.psi.search.GlobalSearchScope.allScope(project))
        ?: return false
    return target.isInheritor(typeClass, true) || typeClass.isInheritor(target, true)
}

private fun PsiType.canonicalTextOrNull(): String? = try { canonicalText } catch (_: Exception) { null }

private const val FQN_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired"
private const val FQN_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier"

private data class Dependency(
    val name: String,
    val typeName: String,
    val kind: String,
    val qualifier: String?,
) {
    fun render(): String {
        val q = if (qualifier != null) " @Qualifier(\"$qualifier\")" else ""
        return "$kind: $typeName $name$q"
    }
}

private data class Consumer(
    val owner: String,
    val kind: String,
    val fieldOrParamName: String,
) {
    fun render(): String = "$owner ($kind: $fieldOrParamName)"
}
