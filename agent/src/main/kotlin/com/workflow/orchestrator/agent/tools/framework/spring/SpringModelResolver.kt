package com.workflow.orchestrator.agent.tools.framework.spring

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import java.lang.reflect.Method

/**
 * Reflective facade over the IntelliJ Ultimate Spring plugin's bean-model APIs.
 *
 * The `:agent` JAR ships against both Community and Ultimate, so every Spring
 * call goes through reflection. Callers should already have gated on
 * [com.workflow.orchestrator.agent.ide.IdeContext.hasSpringPlugin]; when the
 * plugin is unavailable, every method here returns an empty value without
 * raising, so callers see a clean "not found" rather than a reflection trace.
 *
 * Signatures pinned by [SpringToolTest]. See
 * `docs/research/2026-04-21-intellij-spring-plugin-api-signatures.md` for the
 * full surface.
 */
internal object SpringModelResolver {

    private const val SPRING_MANAGER_FQN = "com.intellij.spring.SpringManager"
    private const val COMMON_SPRING_MODEL_FQN = "com.intellij.spring.CommonSpringModel"
    private const val SPRING_MODEL_SEARCHERS_FQN = "com.intellij.spring.model.utils.SpringModelSearchers"

    @Volatile private var cached: Api? = null
    @Volatile private var initialized: Boolean = false

    /** All SpringModels across every module. Empty if Spring plugin absent. */
    fun getAllModels(project: Project): List<Any> {
        val api = api() ?: return emptyList()
        val springManager = try {
            api.getInstance.invoke(null, project)
        } catch (_: Exception) { return emptyList() } ?: return emptyList()

        val out = mutableListOf<Any>()
        for (module in ModuleManager.getInstance(project).modules) {
            val models = try {
                api.getAllModels.invoke(springManager, module) as? Collection<*>
            } catch (_: Exception) { null } ?: continue
            for (m in models) if (m != null) out.add(m)
        }
        return out
    }

    /**
     * Resolves a bean by any name form: canonical bean name, alias, @Bean
     * method name, explicit `@Bean("name")` / `@Component("name")` value,
     * simple or qualified class name. Returns the first [SpringBeanPointer]
     * (as `Any`) whose model recognizes the name.
     */
    fun findBean(project: Project, beanName: String): Any? {
        val api = api() ?: return null
        for (model in getAllModels(project)) {
            val result = try {
                api.findBean.invoke(null, model, beanName)
            } catch (_: Exception) { null }
            if (result != null) return result
        }
        return null
    }

    /**
     * Every [CommonSpringBean] in every model. Used for consumer-scan
     * passes that need the full bean universe (stereotype, @Bean method,
     * XML — Spring's model knows them all; our PSI scan doesn't).
     */
    fun allBeans(project: Project): List<Any> {
        val api = api() ?: return emptyList()
        val out = mutableListOf<Any>()
        for (model in getAllModels(project)) {
            val beans = try {
                api.getAllCommonBeans.invoke(model) as? Collection<*>
            } catch (_: Exception) { null } ?: continue
            for (b in beans) if (b != null) out.add(b)
        }
        return out
    }

    // ─── SpringBeanPointer accessors ───

    fun pointerName(pointer: Any): String? = invoke(pointer, "getName") as? String

    fun pointerAliases(pointer: Any): List<String> {
        val raw = invoke(pointer, "getAliases") as? Array<*> ?: return emptyList()
        return raw.mapNotNull { it as? String }
    }

    fun pointerBeanClass(pointer: Any): PsiClass? = invoke(pointer, "getBeanClass") as? PsiClass

    @Suppress("UNCHECKED_CAST")
    fun pointerEffectiveTypes(pointer: Any): Collection<PsiType> =
        (invoke(pointer, "getEffectiveBeanTypes") as? Collection<PsiType>) ?: emptyList()

    fun pointerSpringBean(pointer: Any): Any? = invoke(pointer, "getSpringBean")

    // ─── CommonSpringBean accessors ───

    fun beanName(bean: Any): String? = invoke(bean, "getBeanName") as? String

    fun beanScope(bean: Any): String? = invoke(bean, "getSpringScope")?.toString()

    fun beanProfile(bean: Any): String? = invoke(bean, "getProfile")?.toString()

    fun beanIsPrimary(bean: Any): Boolean = invoke(bean, "isPrimary") as? Boolean == true

    /**
     * The PSI element defining the bean — `PsiClass` for stereotype/XML
     * beans, `PsiMethod` for `@Bean` factory methods. Walks via
     * `getIdentifyingPsiElement` when present (JAM beans), falling back
     * to `PsiElementPointer.getPsiElement` where applicable.
     */
    fun beanDefiningElement(bean: Any): PsiElement? {
        invoke(bean, "getIdentifyingPsiElement")?.let { if (it is PsiElement) return it }
        invoke(bean, "getPsiElement")?.let { if (it is PsiElement) return it }
        return null
    }

    // ─── internals ───

    private fun api(): Api? {
        if (initialized) return cached
        synchronized(this) {
            if (initialized) return cached
            cached = try {
                val springManager = Class.forName(SPRING_MANAGER_FQN)
                val searchers = Class.forName(SPRING_MODEL_SEARCHERS_FQN)
                val commonSpringModel = Class.forName(COMMON_SPRING_MODEL_FQN)
                Api(
                    getInstance = springManager.getMethod("getInstance", Project::class.java),
                    getAllModels = springManager.getMethod("getAllModels", Module::class.java),
                    findBean = searchers.getMethod("findBean", commonSpringModel, String::class.java),
                    getAllCommonBeans = commonSpringModel.getMethod("getAllCommonBeans"),
                )
            } catch (_: Throwable) {
                null
            }
            initialized = true
            return cached
        }
    }

    private fun invoke(target: Any, methodName: String): Any? = try {
        target.javaClass.getMethod(methodName).invoke(target)
    } catch (_: Exception) { null }

    private class Api(
        val getInstance: Method,
        val getAllModels: Method,
        val findBean: Method,
        val getAllCommonBeans: Method,
    )
}
