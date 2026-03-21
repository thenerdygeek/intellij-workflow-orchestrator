package com.workflow.orchestrator.agent.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class SpringEventListenersTool : AgentTool {
    override val name = "spring_event_listeners"
    override val description = "List @EventListener methods and ApplicationListener implementations."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectEventListeners(project)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult(
                "Spring plugin not available.",
                "No Spring",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            return ToolResult(
                "Error: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return ToolResult(
            content = content,
            summary = "Event listeners listed",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectEventListeners(project: Project): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val listeners = mutableListOf<EventListenerInfo>()

        // 1. Find @EventListener annotated methods
        val eventListenerClass = facade.findClass(
            "org.springframework.context.event.EventListener",
            allScope
        )

        if (eventListenerClass != null) {
            val annotatedMethods = AnnotatedElementsSearch.searchPsiMethods(
                eventListenerClass, scope
            ).findAll()

            for (method in annotatedMethods) {
                val className = method.containingClass?.name ?: "(anonymous)"
                val methodName = method.name

                // Extract event type from method parameter
                val eventType = method.parameterList.parameters.firstOrNull()
                    ?.type?.presentableText ?: "?"

                // Check for @Async
                val isAsync = method.getAnnotation(
                    "org.springframework.scheduling.annotation.Async"
                ) != null

                // Check for condition in @EventListener
                val annotation = method.getAnnotation(
                    "org.springframework.context.event.EventListener"
                )
                val condition = annotation?.findAttributeValue("condition")?.text
                    ?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() && it != "\"\"" }

                val extras = mutableListOf<String>()
                extras.add("@EventListener")
                if (isAsync) extras.add("@Async")
                if (condition != null) extras.add("condition=\"$condition\"")

                listeners.add(
                    EventListenerInfo(
                        description = "$className.$methodName($eventType)",
                        annotations = extras.joinToString(" ")
                    )
                )
            }
        }

        // 2. Find ApplicationListener implementations
        val appListenerClass = facade.findClass(
            "org.springframework.context.ApplicationListener",
            allScope
        )

        if (appListenerClass != null) {
            val implementations = ClassInheritorsSearch.search(appListenerClass, scope, true).findAll()

            for (impl in implementations) {
                if (impl.isInterface) continue // Skip sub-interfaces
                val className = impl.name ?: "(anonymous)"
                val eventType = extractListenerEventType(impl, appListenerClass)

                listeners.add(
                    EventListenerInfo(
                        description = "$className implements ApplicationListener<$eventType>",
                        annotations = ""
                    )
                )
            }
        }

        // 3. Find @TransactionalEventListener methods
        val txEventListenerClass = facade.findClass(
            "org.springframework.transaction.event.TransactionalEventListener",
            allScope
        )

        if (txEventListenerClass != null) {
            val txMethods = AnnotatedElementsSearch.searchPsiMethods(
                txEventListenerClass, scope
            ).findAll()

            for (method in txMethods) {
                val className = method.containingClass?.name ?: "(anonymous)"
                val methodName = method.name
                val eventType = method.parameterList.parameters.firstOrNull()
                    ?.type?.presentableText ?: "?"

                val annotation = method.getAnnotation(
                    "org.springframework.transaction.event.TransactionalEventListener"
                )
                val phase = annotation?.findAttributeValue("phase")?.text
                    ?.substringAfterLast(".")
                    ?.takeIf { it.isNotBlank() }

                val extras = mutableListOf("@TransactionalEventListener")
                if (phase != null) extras.add("phase=$phase")

                listeners.add(
                    EventListenerInfo(
                        description = "$className.$methodName($eventType)",
                        annotations = extras.joinToString(" ")
                    )
                )
            }
        }

        if (listeners.isEmpty()) {
            return "No Spring event listeners found in project."
        }

        val sb = StringBuilder("Event Listeners (${listeners.size}):\n")
        for (listener in listeners.sortedBy { it.description }) {
            val suffix = if (listener.annotations.isNotBlank()) " — ${listener.annotations}" else ""
            sb.appendLine("  ${listener.description}$suffix")
        }
        return sb.toString().trimEnd()
    }

    private fun extractListenerEventType(impl: PsiClass, appListenerClass: PsiClass): String {
        val targetFqn = appListenerClass.qualifiedName ?: return "?"
        for (superType in impl.implementsListTypes) {
            val resolved = superType.resolve() ?: continue
            if (resolved.qualifiedName == targetFqn) {
                return superType.parameters.firstOrNull()?.presentableText ?: "?"
            }
        }
        // Check extends list too (for abstract classes)
        for (superType in impl.extendsListTypes) {
            val resolved = superType.resolve() ?: continue
            if (resolved.qualifiedName == targetFqn) {
                return superType.parameters.firstOrNull()?.presentableText ?: "?"
            }
        }
        return "?"
    }

    private data class EventListenerInfo(
        val description: String,
        val annotations: String
    )
}
