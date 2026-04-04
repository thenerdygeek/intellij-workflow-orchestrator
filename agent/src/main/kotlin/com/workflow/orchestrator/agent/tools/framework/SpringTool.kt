package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.psi.PsiToolUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Spring meta-tool replacing 15 individual Spring/Spring Boot/JPA tools.
 *
 * Saves token budget per API call by collapsing all Spring-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: context, endpoints, bean_graph, config, version_info, profiles, repositories,
 *          security_config, scheduled_tasks, event_listeners, boot_endpoints, boot_autoconfig,
 *          boot_config_properties, boot_actuator, jpa_entities
 */
class SpringTool : AgentTool {

    override val name = "spring"

    override val description = """
Spring framework intelligence — beans, endpoints, configuration, JPA, security, actuator.

Actions and their parameters:
- context(filter?) → Spring bean context
- endpoints(filter?, include_params?) → REST endpoint mappings
- bean_graph(bean_name) → Bean dependency graph
- config(property) → Configuration property value
- version_info(module) → Framework version info
- profiles() → Active Spring profiles
- repositories(filter?) → Spring Data repositories
- security_config() → Security configuration
- scheduled_tasks() → @Scheduled methods
- event_listeners() → @EventListener methods
- boot_endpoints(class_name?) → Boot endpoint mappings
- boot_autoconfig(filter?, project_only?) → Auto-configuration classes (project_only default true)
- boot_config_properties(class_name?, prefix?) → @ConfigurationProperties bindings
- boot_actuator() → Actuator endpoints
- jpa_entities(entity?) → JPA entity analysis
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "context", "endpoints", "bean_graph", "config", "version_info",
                    "profiles", "repositories", "security_config", "scheduled_tasks",
                    "event_listeners", "boot_endpoints", "boot_autoconfig",
                    "boot_config_properties", "boot_actuator", "jpa_entities"
                )
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for context, endpoints, boot_autoconfig"
            ),
            "bean_name" to ParameterProperty(
                type = "string",
                description = "Bean class name (simple or fully qualified) — for bean_graph"
            ),
            "property" to ParameterProperty(
                type = "string",
                description = "Specific property name to look up (e.g., 'spring.datasource.url') — for config"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to inspect — for version_info"
            ),
            "include_params" to ParameterProperty(
                type = "boolean",
                description = "If true, show handler method parameters with annotations (default: false) — for endpoints"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Filter by controller or config properties class name — for boot_endpoints, boot_config_properties"
            ),
            "prefix" to ParameterProperty(
                type = "string",
                description = "Filter by configuration properties prefix — for boot_config_properties"
            ),
            "project_only" to ParameterProperty(
                type = "boolean",
                description = "If true (default), only scan project-scope classes; if false, includes library classes — for boot_autoconfig"
            ),
            "entity" to ParameterProperty(
                type = "string",
                description = "Specific entity class name — for jpa_entities"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "context" -> executeContext(params, project)
            "endpoints" -> executeEndpoints(params, project)
            "bean_graph" -> executeBeanGraph(params, project)
            "config" -> executeConfig(params, project)
            "version_info" -> executeVersionInfo(params, project)
            "profiles" -> executeProfiles(params, project)
            "repositories" -> executeRepositories(params, project)
            "security_config" -> executeSecurityConfig(params, project)
            "scheduled_tasks" -> executeScheduledTasks(params, project)
            "event_listeners" -> executeEventListeners(params, project)
            "boot_endpoints" -> executeBootEndpoints(params, project)
            "boot_autoconfig" -> executeBootAutoConfig(params, project)
            "boot_config_properties" -> executeBootConfigProperties(params, project)
            "boot_actuator" -> executeBootActuator(params, project)
            "jpa_entities" -> executeJpaEntities(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: context (from SpringContextTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeContext(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectBeans(project, filter)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: ClassNotFoundException) {
            SPRING_PLUGIN_MISSING_MSG
        }

        return ToolResult(
            content = content,
            summary = "Spring beans listed (${content.lines().size} entries)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectBeans(project: Project, filter: String?): String {
        return try {
            val springManagerClass = Class.forName("com.intellij.spring.SpringManager")
            val getInstance = springManagerClass.getMethod("getInstance", Project::class.java)
            val springManager = getInstance.invoke(null, project)
            val getAllModels = springManagerClass.getMethod("getAllModels", Project::class.java)
            val models = getAllModels.invoke(springManager, project) as Collection<Any>
            if (models.isEmpty()) return "No Spring model found. Is this a Spring project with the Spring plugin enabled?"

            val allBeans = mutableListOf<Any>()
            for (model in models) {
                val beans = model.javaClass.getMethod("getAllCommonBeans").invoke(model) as Collection<Any>
                allBeans.addAll(beans)
            }

            val filtered = if (filter != null) {
                allBeans.filter { bean ->
                    val name = getBeanStringProperty(bean, "getBeanName") ?: ""
                    val type = getBeanClassQualifiedName(bean) ?: ""
                    name.contains(filter, ignoreCase = true) || type.contains(filter, ignoreCase = true)
                }
            } else {
                allBeans
            }

            if (filtered.isEmpty()) {
                return "No beans found${if (filter != null) " matching '$filter'" else ""}."
            }

            filtered.take(50).joinToString("\n") { bean ->
                val name = getBeanStringProperty(bean, "getBeanName") ?: "(unnamed)"
                val type = getBeanClassQualifiedName(bean) ?: "(unknown type)"
                val scope = getBeanStringProperty(bean, "getBeanScope") ?: "singleton"
                val beanClass = getBeanPsiClass(bean)
                val stereotype = detectStereotype(beanClass)
                "$stereotype $name: $type (scope: $scope)"
            }.let { result ->
                if (filtered.size > 50) {
                    "$result\n... (${filtered.size - 50} more beans not shown)"
                } else {
                    result
                }
            }
        } catch (e: NoClassDefFoundError) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: ClassNotFoundException) {
            SPRING_PLUGIN_MISSING_MSG
        } catch (e: Exception) {
            "Error accessing Spring model: ${e.message}. Make sure the Spring plugin is available."
        }
    }

    private fun getBeanStringProperty(bean: Any, methodName: String): String? {
        return try { bean.javaClass.getMethod(methodName).invoke(bean) as? String } catch (_: Exception) { null }
    }

    private fun getBeanPsiClass(bean: Any): PsiClass? {
        return try { bean.javaClass.getMethod("getBeanClass").invoke(bean) as? PsiClass } catch (_: Exception) { null }
    }

    private fun getBeanClassQualifiedName(bean: Any): String? = getBeanPsiClass(bean)?.qualifiedName

    private fun detectStereotype(psiClass: PsiClass?): String {
        if (psiClass == null) return "@Bean"
        return when {
            psiClass.getAnnotation("org.springframework.stereotype.Service") != null -> "@Service"
            psiClass.getAnnotation("org.springframework.stereotype.Repository") != null -> "@Repository"
            psiClass.getAnnotation("org.springframework.web.bind.annotation.RestController") != null -> "@RestController"
            psiClass.getAnnotation("org.springframework.stereotype.Controller") != null -> "@Controller"
            psiClass.getAnnotation("org.springframework.stereotype.Component") != null -> "@Component"
            psiClass.getAnnotation("org.springframework.context.annotation.Configuration") != null -> "@Configuration"
            else -> "@Bean"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: endpoints (from SpringEndpointsTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val endpointMappingAnnotations = mapOf(
        "org.springframework.web.bind.annotation.RequestMapping" to null,
        "org.springframework.web.bind.annotation.GetMapping" to "GET",
        "org.springframework.web.bind.annotation.PostMapping" to "POST",
        "org.springframework.web.bind.annotation.PutMapping" to "PUT",
        "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
        "org.springframework.web.bind.annotation.PatchMapping" to "PATCH"
    )

    private val controllerAnnotations = listOf(
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller"
    )

    private suspend fun executeEndpoints(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val includeParams = params["include_params"]?.jsonPrimitive?.content?.toBoolean() ?: false

        val content = ReadAction.nonBlocking<String> {
            collectEndpoints(project, filter, includeParams)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Spring endpoints listed (${content.lines().size} entries)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectEndpoints(project: Project, filter: String?, includeParams: Boolean = false): String {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val controllerClasses = mutableSetOf<PsiClass>()
        for (annotationFqn in controllerAnnotations) {
            val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
            if (annotationClass != null) {
                controllerClasses.addAll(
                    AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()
                )
            }
        }

        if (controllerClasses.isEmpty()) {
            return "No @RestController or @Controller classes found in project."
        }

        val endpoints = mutableListOf<EndpointInfo>()
        for (cls in controllerClasses) {
            val classPrefix = extractMappingPath(
                cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            )
            for (method in cls.methods) {
                for ((annotationFqn, httpMethod) in endpointMappingAnnotations) {
                    val annotation = method.getAnnotation(annotationFqn) ?: continue
                    val methodPath = extractMappingPath(annotation)
                    val path = classPrefix + methodPath
                    val resolvedMethod = httpMethod
                        ?: extractRequestMethod(annotation)
                        ?: "GET"
                    endpoints.add(
                        EndpointInfo(
                            httpMethod = resolvedMethod,
                            path = path.ifBlank { "/" },
                            className = cls.name ?: "(anonymous)",
                            methodName = method.name,
                            methodSignature = formatEndpointMethodParams(method, includeParams)
                        )
                    )
                }
            }
        }

        val filtered = if (filter != null) {
            endpoints.filter { ep ->
                ep.path.contains(filter, ignoreCase = true) ||
                    ep.httpMethod.contains(filter, ignoreCase = true) ||
                    ep.className.contains(filter, ignoreCase = true)
            }
        } else {
            endpoints
        }

        if (filtered.isEmpty()) {
            return "No endpoints found${if (filter != null) " matching '$filter'" else ""}."
        }

        val sorted = filtered.sortedWith(compareBy({ it.path }, { it.httpMethod }))
        return sorted.take(100).joinToString("\n") { ep ->
            "${ep.httpMethod.padEnd(7)} ${ep.path} -> ${ep.className}.${ep.methodName}(${ep.methodSignature})"
        }.let { result ->
            if (sorted.size > 100) {
                "$result\n... (${sorted.size - 100} more endpoints not shown)"
            } else {
                result
            }
        }
    }

    private fun extractMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text
            ?.removeSurrounding("\"")
            ?.removeSurrounding("{", "}")
            ?: ""
    }

    private fun extractRequestMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        val text = method.text ?: return null
        return text.replace("RequestMethod.", "").removeSurrounding("{", "}")
    }

    private fun formatEndpointMethodParams(method: PsiMethod, includeParams: Boolean = false): String {
        return method.parameterList.parameters.joinToString(", ") { p ->
            if (includeParams) {
                val annotations = listOf("PathVariable", "RequestParam", "RequestBody", "RequestHeader")
                    .mapNotNull { ann ->
                        p.getAnnotation("org.springframework.web.bind.annotation.$ann")?.let { "@$ann" }
                    }
                val prefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
                "$prefix${p.type.presentableText} ${p.name}"
            } else {
                p.type.presentableText
            }
        }
    }

    private data class EndpointInfo(
        val httpMethod: String,
        val path: String,
        val className: String,
        val methodName: String,
        val methodSignature: String
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: bean_graph (from SpringBeanGraphTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val springBeanAnnotations = listOf(
        "org.springframework.stereotype.Service",
        "org.springframework.stereotype.Repository",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.stereotype.Controller",
        "org.springframework.stereotype.Component",
        "org.springframework.context.annotation.Configuration"
    )

    private suspend fun executeBeanGraph(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val beanName = params["bean_name"]?.jsonPrimitive?.contentOrNull
            ?: return missingParam("bean_name")

        val content = ReadAction.nonBlocking<String> {
            buildDependencyGraph(project, beanName)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Bean dependency graph for '$beanName'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun buildDependencyGraph(project: Project, beanName: String): String {
        val psiClass = PsiToolUtils.findClass(project, beanName)
            ?: return "No class '$beanName' found in project."

        val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: beanName
        val sb = StringBuilder()
        sb.appendLine("Dependency graph for: $qualifiedName")

        val stereotype = detectStereotype(psiClass)
        sb.appendLine("Stereotype: $stereotype")
        sb.appendLine()

        sb.appendLine("Dependencies (what this bean injects):")
        val dependencies = mutableListOf<DependencyInfo>()

        val primaryConstructor = psiClass.constructors.firstOrNull()
        if (primaryConstructor != null) {
            primaryConstructor.parameterList.parameters.forEach { param ->
                dependencies.add(extractDependencyFromParam(param))
            }
        }

        psiClass.fields.forEach { field ->
            if (field.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null) {
                dependencies.add(extractDependencyFromField(field))
            }
        }

        psiClass.methods.forEach { method ->
            if (method.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null &&
                method.name.startsWith("set") && method.parameterList.parametersCount > 0
            ) {
                method.parameterList.parameters.forEach { param ->
                    dependencies.add(extractDependencyFromParam(param, "setter"))
                }
            }
        }

        if (dependencies.isEmpty()) {
            sb.appendLine("  (no dependencies)")
        } else {
            dependencies.forEach { dep ->
                val qualifier = if (dep.qualifier != null) " @Qualifier(\"${dep.qualifier}\")" else ""
                sb.appendLine("  ${dep.injectionType}: ${dep.typeName} ${dep.name}$qualifier")
            }
        }

        sb.appendLine()

        sb.appendLine("Consumers (what injects this bean):")
        val consumers = findConsumers(project, psiClass)
        if (consumers.isEmpty()) {
            sb.appendLine("  (no consumers found)")
        } else {
            consumers.forEach { consumer ->
                sb.appendLine("  ${consumer.className} (${consumer.injectionType}: ${consumer.fieldOrParamName})")
            }
        }

        return sb.toString()
    }

    private fun extractDependencyFromParam(param: PsiParameter, type: String = "constructor"): DependencyInfo {
        val qualifier = param.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return DependencyInfo(
            name = param.name,
            typeName = param.type.presentableText,
            injectionType = type,
            qualifier = qualifier
        )
    }

    private fun extractDependencyFromField(field: PsiField): DependencyInfo {
        val qualifier = field.getAnnotation("org.springframework.beans.factory.annotation.Qualifier")
            ?.findAttributeValue("value")?.text?.removeSurrounding("\"")
        return DependencyInfo(
            name = field.name,
            typeName = field.type.presentableText,
            injectionType = "field",
            qualifier = qualifier
        )
    }

    private fun findConsumers(project: Project, targetClass: PsiClass): List<ConsumerInfo> {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val consumers = mutableListOf<ConsumerInfo>()
        val targetTypeName = targetClass.qualifiedName ?: return consumers

        val allBeanClasses = mutableSetOf<PsiClass>()
        for (annotationFqn in springBeanAnnotations) {
            val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
            if (annotationClass != null) {
                allBeanClasses.addAll(
                    AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()
                )
            }
        }

        for (cls in allBeanClasses) {
            if (cls == targetClass) continue

            cls.constructors.firstOrNull()?.parameterList?.parameters?.forEach { param ->
                if (param.type.canonicalText == targetTypeName ||
                    isAssignableFrom(targetClass, param.type.canonicalText, project)
                ) {
                    consumers.add(
                        ConsumerInfo(
                            className = cls.qualifiedName ?: cls.name ?: "unknown",
                            injectionType = "constructor",
                            fieldOrParamName = param.name
                        )
                    )
                }
            }

            cls.fields.filter {
                it.getAnnotation("org.springframework.beans.factory.annotation.Autowired") != null
            }.forEach { field ->
                if (field.type.canonicalText == targetTypeName ||
                    isAssignableFrom(targetClass, field.type.canonicalText, project)
                ) {
                    consumers.add(
                        ConsumerInfo(
                            className = cls.qualifiedName ?: cls.name ?: "unknown",
                            injectionType = "field",
                            fieldOrParamName = field.name
                        )
                    )
                }
            }
        }

        return consumers.distinctBy { it.className }.take(30)
    }

    private fun isAssignableFrom(targetClass: PsiClass, typeFqn: String, project: Project): Boolean {
        val typeClass = JavaPsiFacade.getInstance(project)
            .findClass(typeFqn, GlobalSearchScope.allScope(project))
            ?: return false
        return targetClass.isInheritor(typeClass, true) || typeClass.isInheritor(targetClass, true)
    }

    private data class DependencyInfo(
        val name: String,
        val typeName: String,
        val injectionType: String,
        val qualifier: String?
    )

    private data class ConsumerInfo(
        val className: String,
        val injectionType: String,
        val fieldOrParamName: String
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: config (from SpringConfigTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeConfig(params: JsonObject, project: Project): ToolResult {
        val propertyName = params["property"]?.jsonPrimitive?.content
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val configFiles = findConfigFiles(File(basePath))
                if (configFiles.isEmpty()) {
                    return@withContext ToolResult(
                        "No Spring configuration files found (application.properties, application.yml, application.yaml).",
                        "No config files", 10
                    )
                }

                val allProperties = mutableMapOf<String, MutableList<ConfigPropertyEntry>>()

                for (configFile in configFiles) {
                    val relativePath = configFile.absolutePath.removePrefix("$basePath/")
                    val extension = configFile.extension.lowercase()

                    when (extension) {
                        "properties" -> parsePropertiesFile(configFile, relativePath, allProperties)
                        "yml", "yaml" -> parseYamlFile(configFile, relativePath, allProperties)
                    }
                }

                if (allProperties.isEmpty()) {
                    return@withContext ToolResult("Configuration files found but contain no properties.", "Empty config", 5)
                }

                val content = if (propertyName != null) {
                    formatPropertyLookup(propertyName, allProperties)
                } else {
                    formatAllProperties(allProperties)
                }

                ToolResult(
                    content = content,
                    summary = if (propertyName != null) "Lookup: $propertyName" else "${allProperties.size} properties from ${configFiles.size} file(s)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error reading Spring config: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun findConfigFiles(baseDir: File): List<File> {
        val searchDirs = listOf(
            "src/main/resources",
            "src/main/resources/config",
            "src/test/resources"
        )
        val fileNames = listOf(
            "application.properties",
            "application.yml",
            "application.yaml",
            "application-dev.properties",
            "application-dev.yml",
            "application-test.properties",
            "application-test.yml",
            "application-prod.properties",
            "application-prod.yml",
            "bootstrap.properties",
            "bootstrap.yml"
        )

        val found = mutableListOf<File>()

        for (dir in searchDirs) {
            val resourceDir = File(baseDir, dir)
            if (!resourceDir.isDirectory) continue
            for (fileName in fileNames) {
                val file = File(resourceDir, fileName)
                if (file.isFile) found.add(file)
            }
        }

        baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { subDir ->
            for (dir in searchDirs) {
                val resourceDir = File(subDir, dir)
                if (!resourceDir.isDirectory) continue
                for (fileName in fileNames) {
                    val file = File(resourceDir, fileName)
                    if (file.isFile) found.add(file)
                }
            }
        }

        return found
    }

    private fun parsePropertiesFile(file: File, relativePath: String, target: MutableMap<String, MutableList<ConfigPropertyEntry>>) {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        for ((key, value) in props) {
            val k = key.toString()
            target.getOrPut(k) { mutableListOf() }.add(ConfigPropertyEntry(value.toString(), relativePath))
        }
    }

    private fun parseYamlFile(file: File, relativePath: String, target: MutableMap<String, MutableList<ConfigPropertyEntry>>) {
        val lines = file.readLines()
        val keyStack = mutableListOf<Pair<Int, String>>()

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue

            val indent = line.length - line.trimStart().length

            while (keyStack.isNotEmpty() && keyStack.last().first >= indent) {
                keyStack.removeAt(keyStack.size - 1)
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex < 0) continue

            val key = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()

            keyStack.add(indent to key)

            if (value.isNotEmpty() && !value.startsWith("#")) {
                val fullKey = keyStack.joinToString(".") { it.second }
                val cleanValue = value.removeSurrounding("\"").removeSurrounding("'")
                target.getOrPut(fullKey) { mutableListOf() }.add(ConfigPropertyEntry(cleanValue, relativePath))
            }
        }
    }

    private fun formatPropertyLookup(propertyName: String, allProperties: Map<String, List<ConfigPropertyEntry>>): String {
        val exact = allProperties[propertyName]
        if (exact != null) {
            return buildString {
                appendLine("Property: $propertyName")
                exact.forEach { entry ->
                    appendLine("  Value: ${entry.value}")
                    appendLine("  File:  ${entry.source}")
                }
            }
        }

        val matches = allProperties.filter { (key, _) -> key.contains(propertyName, ignoreCase = true) }
        if (matches.isEmpty()) {
            return "Property '$propertyName' not found in any configuration file."
        }

        return buildString {
            appendLine("Property '$propertyName' not found exactly. Similar properties:")
            matches.entries.take(20).forEach { (key, entries) ->
                entries.forEach { entry ->
                    appendLine("  $key = ${entry.value}  (${entry.source})")
                }
            }
            if (matches.size > 20) appendLine("  ... and ${matches.size - 20} more")
        }
    }

    private fun formatAllProperties(allProperties: Map<String, List<ConfigPropertyEntry>>): String {
        return buildString {
            appendLine("Spring configuration properties (${allProperties.size} total):")
            appendLine()

            val byFile = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for ((key, entries) in allProperties) {
                for (entry in entries) {
                    byFile.getOrPut(entry.source) { mutableListOf() }.add(key to entry.value)
                }
            }

            for ((file, props) in byFile) {
                appendLine("[$file]")
                props.sortedBy { it.first }.take(100).forEach { (key, value) ->
                    val displayValue = if (value.length > 80) value.take(77) + "..." else value
                    appendLine("  $key = $displayValue")
                }
                if (props.size > 100) appendLine("  ... and ${props.size - 100} more")
                appendLine()
            }
        }
    }

    private data class ConfigPropertyEntry(val value: String, val source: String)

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: version_info (from SpringVersionTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeVersionInfo(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content

            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val projectName = MavenUtils.getDisplayName(targetProject)
            val projectVersion = MavenUtils.getMavenId(targetProject, "getVersion") ?: "unknown"

            val dependencies = MavenUtils.getDependencies(targetProject)
            val properties = MavenUtils.getProperties(targetProject)

            val versions = mutableMapOf<String, String>()

            findVersion(dependencies, "org.springframework.boot", "spring-boot-starter", "spring-boot")?.let {
                versions["Spring Boot"] = it
            }
            if ("Spring Boot" !in versions) {
                getParentVersion(targetProject, "org.springframework.boot")?.let {
                    versions["Spring Boot"] = it
                }
            }

            findVersion(dependencies, "org.springframework", "spring-core", "spring-context", "spring-web")?.let {
                versions["Spring Framework"] = it
            }

            val javaVersion = properties["java.version"]
                ?: properties["maven.compiler.source"]
                ?: properties["maven.compiler.target"]
                ?: properties["maven.compiler.release"]
            if (javaVersion != null) {
                versions["Java"] = javaVersion
            }

            findVersion(dependencies, "org.jetbrains.kotlin", "kotlin-stdlib", "kotlin-stdlib-jdk8", "kotlin-reflect")?.let {
                versions["Kotlin"] = it
            } ?: properties["kotlin.version"]?.let { versions["Kotlin"] = it }

            findVersion(dependencies, "org.junit.jupiter", "junit-jupiter", "junit-jupiter-api")?.let {
                versions["JUnit"] = it
            }

            findVersion(dependencies, "org.hibernate.orm", "hibernate-core")?.let {
                versions["Hibernate"] = it
            } ?: findVersion(dependencies, "org.hibernate", "hibernate-core")?.let {
                versions["Hibernate"] = it
            }

            findVersion(dependencies, "com.fasterxml.jackson.core", "jackson-databind")?.let {
                versions["Jackson"] = it
            }

            findVersion(dependencies, "org.projectlombok", "lombok")?.let {
                versions["Lombok"] = it
            }

            findVersion(dependencies, "org.apache.commons", "commons-lang3")?.let {
                versions["Commons Lang"] = it
            }

            findVersion(dependencies, "ch.qos.logback", "logback-classic")?.let {
                versions["Logback"] = it
            }

            if (versions.isEmpty()) {
                return ToolResult(
                    "Project: $projectName ($projectVersion)\nNo recognized framework versions detected from Maven dependencies.",
                    "No frameworks", 10
                )
            }

            val content = buildString {
                appendLine("Project: $projectName ($projectVersion)")
                for ((name, version) in versions) {
                    appendLine("$name: $version")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = versions.entries.joinToString(", ") { "${it.key} ${it.value}" },
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error detecting versions: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun findVersion(dependencies: List<MavenUtils.MavenDependencyInfo>, groupId: String, vararg artifactIds: String): String? {
        for (artifactId in artifactIds) {
            val dep = dependencies.find { it.groupId == groupId && it.artifactId == artifactId && it.version.isNotBlank() }
            if (dep != null) return dep.version
        }
        return dependencies.find { it.groupId == groupId && it.version.isNotBlank() }?.version
    }

    private fun getParentVersion(mavenProject: Any, parentGroupId: String): String? {
        return try {
            val parentId = mavenProject.javaClass.getMethod("getParentId").invoke(mavenProject) ?: return null
            val groupId = parentId.javaClass.getMethod("getGroupId").invoke(parentId) as? String
            if (groupId == parentGroupId) {
                parentId.javaClass.getMethod("getVersion").invoke(parentId) as? String
            } else null
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: profiles (from SpringProfilesTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeProfiles(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectProfiles(project)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return ToolResult(
            content = content,
            summary = "Spring profiles listed",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectProfiles(project: Project): String {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val profileSources = mutableMapOf<String, MutableList<String>>()

        val profileAnnotationClass = facade.findClass(
            "org.springframework.context.annotation.Profile",
            GlobalSearchScope.allScope(project)
        )

        if (profileAnnotationClass != null) {
            val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
                profileAnnotationClass, scope
            ).findAll()

            for (cls in annotatedClasses) {
                val annotation = cls.getAnnotation("org.springframework.context.annotation.Profile")
                    ?: continue
                val profiles = extractProfileNames(annotation)
                val className = cls.name ?: "(anonymous)"
                for (profile in profiles) {
                    profileSources.getOrPut(profile) { mutableListOf() }
                        .add("@Profile on class: $className")
                }
            }

            val annotatedMethods = AnnotatedElementsSearch.searchPsiMethods(
                profileAnnotationClass, scope
            ).findAll()

            for (method in annotatedMethods) {
                val annotation = method.getAnnotation("org.springframework.context.annotation.Profile")
                    ?: continue
                val profiles = extractProfileNames(annotation)
                val className = method.containingClass?.name ?: "(anonymous)"
                val methodName = method.name
                for (profile in profiles) {
                    profileSources.getOrPut(profile) { mutableListOf() }
                        .add("@Profile on method: $className.$methodName()")
                }
            }
        }

        val profileConfigPattern = Regex("^application-(.+)\\.(properties|ya?ml)$")
        val allPropertyFiles = FilenameIndex.getAllFilesByExt(project, "properties", scope) +
            FilenameIndex.getAllFilesByExt(project, "yml", scope) +
            FilenameIndex.getAllFilesByExt(project, "yaml", scope)

        for (vf in allPropertyFiles) {
            val match = profileConfigPattern.matchEntire(vf.name) ?: continue
            val profile = match.groupValues[1]
            profileSources.getOrPut(profile) { mutableListOf() }
                .add("config: ${vf.name}")
        }

        var activeProfiles: String? = null
        val appPropsFiles = FilenameIndex.getVirtualFilesByName("application.properties", scope)
        for (vf in appPropsFiles) {
            val text = VfsUtil.loadText(vf)
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("spring.profiles.active=")) {
                    activeProfiles = trimmed.substringAfter("spring.profiles.active=").trim()
                }
            }
        }

        val appYmlFiles = FilenameIndex.getVirtualFilesByName("application.yml", scope) +
            FilenameIndex.getVirtualFilesByName("application.yaml", scope)
        for (vf in appYmlFiles) {
            val text = VfsUtil.loadText(vf)
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("active:") && text.contains("profiles:")) {
                    activeProfiles = trimmed.substringAfter("active:").trim()
                }
            }
        }

        if (profileSources.isEmpty() && activeProfiles == null) {
            return "No Spring profiles found in project."
        }

        val sb = StringBuilder("Spring Profiles:\n")
        for ((profile, sources) in profileSources.toSortedMap()) {
            val sourceSummary = sources.joinToString("; ")
            sb.appendLine("  $profile — $sourceSummary")
        }
        if (activeProfiles != null) {
            sb.appendLine("Active (application config): $activeProfiles")
        }
        return sb.toString().trimEnd()
    }

    private fun extractProfileNames(annotation: PsiAnnotation): List<String> {
        val value = annotation.findAttributeValue("value") ?: return emptyList()
        return when (value) {
            is PsiArrayInitializerMemberValue -> {
                value.initializers.mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
            }
            is PsiLiteralExpression -> {
                listOfNotNull(value.value as? String)
            }
            else -> {
                val text = value.text ?: return emptyList()
                text.removeSurrounding("{", "}")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: repositories (from SpringRepositoriesTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val repositoryFqns = listOf(
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.data.repository.CrudRepository",
        "org.springframework.data.repository.PagingAndSortingRepository",
        "org.springframework.data.repository.reactive.ReactiveCrudRepository",
        "org.springframework.data.mongodb.repository.MongoRepository"
    )

    private suspend fun executeRepositories(params: JsonObject, project: Project): ToolResult {
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: security_config (from SpringSecurityTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val securityPatterns = listOf(
        "authorizeHttpRequests", "authorizeRequests",
        "oauth2Login", "oauth2ResourceServer",
        "httpBasic", "formLogin",
        "csrf", "cors",
        "sessionManagement", "headers",
        "exceptionHandling", "rememberMe"
    )

    private suspend fun executeSecurityConfig(params: JsonObject, project: Project): ToolResult {
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: scheduled_tasks (from SpringScheduledTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeScheduledTasks(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectScheduledTasks(project)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return ToolResult(
            content = content,
            summary = "Scheduled tasks listed",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectScheduledTasks(project: Project): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val sb = StringBuilder()

        val enableSchedulingClass = facade.findClass(
            "org.springframework.scheduling.annotation.EnableScheduling",
            allScope
        )
        if (enableSchedulingClass != null) {
            val enabledClasses = AnnotatedElementsSearch.searchPsiClasses(
                enableSchedulingClass, scope
            ).findAll()
            if (enabledClasses.isNotEmpty()) {
                val classNames = enabledClasses.mapNotNull { it.name }.joinToString(", ")
                sb.appendLine("@EnableScheduling: enabled ($classNames)")
            } else {
                sb.appendLine("@EnableScheduling: not found in project classes")
            }
        } else {
            sb.appendLine("@EnableScheduling: annotation not on classpath")
        }

        val scheduledClass = facade.findClass(
            "org.springframework.scheduling.annotation.Scheduled",
            allScope
        )

        if (scheduledClass == null) {
            sb.appendLine("@Scheduled annotation not found on classpath.")
            return sb.toString().trimEnd()
        }

        val scheduledMethods = AnnotatedElementsSearch.searchPsiMethods(
            scheduledClass, scope
        ).findAll()

        if (scheduledMethods.isEmpty()) {
            sb.appendLine("Scheduled Tasks: none found")
            return sb.toString().trimEnd()
        }

        sb.appendLine("Scheduled Tasks (${scheduledMethods.size}):")

        for (method in scheduledMethods.sortedBy { "${it.containingClass?.name}.${it.name}" }) {
            val annotation = method.getAnnotation(
                "org.springframework.scheduling.annotation.Scheduled"
            ) ?: continue

            val className = method.containingClass?.name ?: "(anonymous)"
            val methodName = method.name

            val schedule = mutableListOf<String>()

            val cron = extractScheduledAnnotationStringValue(annotation, "cron")
            if (cron != null) schedule.add("cron: \"$cron\"")

            val fixedRate = extractScheduledAnnotationLongValue(annotation, "fixedRate")
            if (fixedRate != null) schedule.add("fixedRate: ${fixedRate}ms")

            val fixedRateString = extractScheduledAnnotationStringValue(annotation, "fixedRateString")
            if (fixedRateString != null) schedule.add("fixedRate: \"$fixedRateString\"")

            val fixedDelay = extractScheduledAnnotationLongValue(annotation, "fixedDelay")
            if (fixedDelay != null) schedule.add("fixedDelay: ${fixedDelay}ms")

            val fixedDelayString = extractScheduledAnnotationStringValue(annotation, "fixedDelayString")
            if (fixedDelayString != null) schedule.add("fixedDelay: \"$fixedDelayString\"")

            val initialDelay = extractScheduledAnnotationLongValue(annotation, "initialDelay")
            if (initialDelay != null) schedule.add("initialDelay: ${initialDelay}ms")

            val initialDelayString = extractScheduledAnnotationStringValue(annotation, "initialDelayString")
            if (initialDelayString != null) schedule.add("initialDelay: \"$initialDelayString\"")

            val scheduleStr = if (schedule.isNotEmpty()) schedule.joinToString(", ") else "(no schedule params)"
            sb.appendLine("  $className.$methodName() — $scheduleStr")
        }

        return sb.toString().trimEnd()
    }

    private fun extractScheduledAnnotationStringValue(
        annotation: PsiAnnotation,
        attributeName: String
    ): String? {
        val value = annotation.findAttributeValue(attributeName) ?: return null
        val text = value.text?.removeSurrounding("\"") ?: return null
        return if (text.isNotBlank() && text != "\"\"" && text != "") text else null
    }

    private fun extractScheduledAnnotationLongValue(
        annotation: PsiAnnotation,
        attributeName: String
    ): Long? {
        val value = annotation.findAttributeValue(attributeName) ?: return null
        val text = value.text ?: return null
        val parsed = text.removeSuffix("L").removeSuffix("l").toLongOrNull()
        return if (parsed != null && parsed >= 0) parsed else null
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: event_listeners (from SpringEventListenersTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeEventListeners(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectEventListeners(project)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
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

                val eventType = method.parameterList.parameters.firstOrNull()
                    ?.type?.presentableText ?: "?"

                val isAsync = method.getAnnotation(
                    "org.springframework.scheduling.annotation.Async"
                ) != null

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

        val appListenerClass = facade.findClass(
            "org.springframework.context.ApplicationListener",
            allScope
        )

        if (appListenerClass != null) {
            val implementations = ClassInheritorsSearch.search(appListenerClass, scope, true).findAll()

            for (impl in implementations) {
                if (impl.isInterface) continue
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: boot_endpoints (from SpringBootEndpointsTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val bootParamAnnotations = listOf(
        "PathVariable" to "org.springframework.web.bind.annotation.PathVariable",
        "RequestParam" to "org.springframework.web.bind.annotation.RequestParam",
        "RequestBody" to "org.springframework.web.bind.annotation.RequestBody",
        "RequestHeader" to "org.springframework.web.bind.annotation.RequestHeader",
        "Valid" to "javax.validation.Valid",
        "Valid" to "jakarta.validation.Valid",
        "Validated" to "org.springframework.validation.annotation.Validated"
    )

    private suspend fun executeBootEndpoints(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val classNameFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content = ReadAction.nonBlocking<String> {
            collectBootEndpoints(project, filter, classNameFilter)
        }.inSmartMode(project).executeSynchronously()

        return ToolResult(
            content = content,
            summary = "Spring Boot endpoints listed (${content.lines().size} lines)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectBootEndpoints(project: Project, filter: String?, classNameFilter: String?): String {
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val contextPath = resolveContextPath(project)

        val controllerClasses = mutableSetOf<PsiClass>()
        for (annotationFqn in controllerAnnotations) {
            val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
            if (annotationClass != null) {
                controllerClasses.addAll(
                    AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()
                )
            }
        }

        if (controllerClasses.isEmpty()) {
            return "No @RestController or @Controller classes found in project."
        }

        val filteredClasses = if (classNameFilter != null) {
            controllerClasses.filter { cls ->
                cls.name?.contains(classNameFilter, ignoreCase = true) == true ||
                    cls.qualifiedName?.contains(classNameFilter, ignoreCase = true) == true
            }
        } else {
            controllerClasses.toList()
        }

        if (filteredClasses.isEmpty()) {
            return "No controllers found matching class name '$classNameFilter'."
        }

        val endpoints = mutableListOf<RichEndpointInfo>()
        for (cls in filteredClasses) {
            val classPrefix = extractBootMappingPath(
                cls.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
            )
            for (method in cls.methods) {
                for ((annotationFqn, httpMethod) in endpointMappingAnnotations) {
                    val annotation = method.getAnnotation(annotationFqn) ?: continue
                    val methodPath = extractBootMappingPath(annotation)
                    val fullPath = buildPath(contextPath, classPrefix, methodPath)
                    val resolvedMethod = httpMethod
                        ?: extractBootRequestMethod(annotation)
                        ?: "GET"
                    val consumes = extractMediaTypes(annotation, "consumes")
                    val produces = extractMediaTypes(annotation, "produces")
                    val returnType = method.returnType?.presentableText ?: "void"
                    val paramsSummary = formatRichParams(method)
                    val filePath = cls.containingFile?.virtualFile?.path
                        ?.let { PsiToolUtils.relativePath(project, it) }
                        ?: ""

                    endpoints.add(
                        RichEndpointInfo(
                            httpMethod = resolvedMethod,
                            fullUrl = fullPath,
                            className = cls.name ?: "(anonymous)",
                            methodName = method.name,
                            params = paramsSummary,
                            returnType = returnType,
                            consumes = consumes,
                            produces = produces,
                            filePath = filePath
                        )
                    )
                }
            }
        }

        val filtered = if (filter != null) {
            endpoints.filter { ep ->
                ep.fullUrl.contains(filter, ignoreCase = true) ||
                    ep.httpMethod.contains(filter, ignoreCase = true) ||
                    ep.className.contains(filter, ignoreCase = true)
            }
        } else {
            endpoints
        }

        if (filtered.isEmpty()) {
            return "No endpoints found${if (filter != null) " matching '$filter'" else ""}."
        }

        val sorted = filtered.sortedWith(compareBy({ it.fullUrl }, { it.httpMethod }))

        val sb = StringBuilder()
        if (contextPath.isNotBlank()) {
            sb.appendLine("Context path: $contextPath")
        }
        sb.appendLine("Endpoints (${sorted.size}):")
        sb.appendLine()

        val displayed = sorted.take(100)
        for (ep in displayed) {
            sb.appendLine("${ep.httpMethod.padEnd(7)} ${ep.fullUrl}")
            sb.appendLine("  Handler: ${ep.className}.${ep.methodName}()")
            if (ep.params.isNotBlank()) {
                sb.appendLine("  Params:  ${ep.params}")
            }
            sb.appendLine("  Returns: ${ep.returnType}")
            if (ep.consumes.isNotBlank()) {
                sb.appendLine("  Consumes: ${ep.consumes}")
            }
            if (ep.produces.isNotBlank()) {
                sb.appendLine("  Produces: ${ep.produces}")
            }
            if (ep.filePath.isNotBlank()) {
                sb.appendLine("  File:    ${ep.filePath}")
            }
            sb.appendLine()
        }

        if (sorted.size > 100) {
            sb.appendLine("... (${sorted.size - 100} more endpoints not shown)")
        }

        return sb.toString().trimEnd()
    }

    private fun resolveContextPath(project: Project): String {
        return try {
            val basePath = project.basePath ?: return ""
            val resourcesRoot = "$basePath/src/main/resources"

            val propsFile = File("$resourcesRoot/application.properties")
            if (propsFile.exists()) {
                propsFile.readLines()
                    .firstOrNull { it.trimStart().startsWith("server.servlet.context-path") }
                    ?.substringAfter("=")
                    ?.trim()
                    ?.let { if (it.isNotBlank()) return it }
            }

            val ymlFile = File("$resourcesRoot/application.yml")
            if (ymlFile.exists()) {
                val lines = ymlFile.readLines()
                var inServer = false
                var inServlet = false
                for (line in lines) {
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("server:") -> { inServer = true; inServlet = false }
                        inServer && trimmed.startsWith("servlet:") -> inServlet = true
                        inServer && inServlet && trimmed.startsWith("context-path:") -> {
                            val value = trimmed.substringAfter("context-path:").trim().removeSurrounding("\"").removeSurrounding("'")
                            if (value.isNotBlank()) return value
                        }
                        !trimmed.startsWith(" ") && !trimmed.startsWith("\t") && trimmed.isNotBlank() -> {
                            if (!trimmed.startsWith("server:")) { inServer = false; inServlet = false }
                        }
                    }
                }
            }

            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun buildPath(contextPath: String, classPrefix: String, methodPath: String): String {
        val segments = listOf(contextPath, classPrefix, methodPath)
            .map { it.trim('/') }
            .filter { it.isNotBlank() }
        return if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}"
    }

    private fun extractBootMappingPath(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        val value = annotation.findAttributeValue("value")
            ?: annotation.findAttributeValue("path")
        return value?.text
            ?.removeSurrounding("\"")
            ?.removeSurrounding("{", "}")
            ?.trim('/')
            ?: ""
    }

    private fun extractBootRequestMethod(annotation: PsiAnnotation): String? {
        val method = annotation.findAttributeValue("method") ?: return null
        val text = method.text ?: return null
        return text.replace("RequestMethod.", "").removeSurrounding("{", "}").trim()
    }

    private fun extractMediaTypes(annotation: PsiAnnotation, attribute: String): String {
        val value = annotation.findAttributeValue(attribute) ?: return ""
        val text = value.text ?: return ""
        return text.removeSurrounding("{", "}").removeSurrounding("\"").trim()
    }

    private fun formatRichParams(method: PsiMethod): String {
        val parts = method.parameterList.parameters.mapNotNull { p ->
            val annotations = buildList {
                val checked = mutableSetOf<String>()
                for ((shortName, fqn) in bootParamAnnotations) {
                    if (checked.contains(shortName)) continue
                    if (p.getAnnotation(fqn) != null) {
                        add("@$shortName")
                        checked.add(shortName)
                    }
                }
            }
            val annotationPrefix = if (annotations.isNotEmpty()) "${annotations.joinToString(" ")} " else ""
            "$annotationPrefix${p.type.presentableText} ${p.name}"
        }
        return parts.joinToString(", ")
    }

    private data class RichEndpointInfo(
        val httpMethod: String,
        val fullUrl: String,
        val className: String,
        val methodName: String,
        val params: String,
        val returnType: String,
        val consumes: String,
        val produces: String,
        val filePath: String
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: boot_autoconfig (from SpringBootAutoConfigTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeBootAutoConfig(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val filter = params["filter"]?.jsonPrimitive?.contentOrNull
        val projectOnly = params["project_only"]?.jsonPrimitive?.booleanOrNull ?: true

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectAutoConfigs(project, filter, projectOnly)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        return ToolResult(
            content = content,
            summary = "Spring Boot auto-configurations listed${if (filter != null) " (filter: $filter)" else ""}",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectAutoConfigs(project: Project, filter: String?, projectOnly: Boolean): String {
        val searchScope = if (projectOnly) {
            GlobalSearchScope.projectScope(project)
        } else {
            GlobalSearchScope.allScope(project)
        }
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val configs = mutableListOf<AutoConfigEntry>()

        val configurationClass = facade.findClass(
            "org.springframework.context.annotation.Configuration",
            allScope
        )
        if (configurationClass != null) {
            val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
                configurationClass, searchScope
            ).findAll()
            for (cls in annotatedClasses) {
                val entry = buildAutoConfigEntry(project, cls) ?: continue
                configs.add(entry)
            }
        }

        val autoConfigurationClass = facade.findClass(
            "org.springframework.boot.autoconfigure.AutoConfiguration",
            allScope
        )
        if (autoConfigurationClass != null) {
            val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(
                autoConfigurationClass, searchScope
            ).findAll()
            for (cls in annotatedClasses) {
                val fqn = cls.qualifiedName ?: continue
                if (configs.none { it.qualifiedName == fqn }) {
                    val entry = buildAutoConfigEntry(project, cls) ?: continue
                    configs.add(entry)
                }
            }
        }

        if (configs.isEmpty()) {
            val scopeLabel = if (projectOnly) "project" else "classpath"
            return "No @Configuration or @AutoConfiguration classes found in $scopeLabel scope."
        }

        val filtered = if (filter != null) {
            configs.filter { entry ->
                entry.qualifiedName.contains(filter, ignoreCase = true) ||
                    entry.conditions.any { it.contains(filter, ignoreCase = true) } ||
                    entry.enableAnnotations.any { it.contains(filter, ignoreCase = true) }
            }
        } else {
            configs
        }

        if (filtered.isEmpty()) {
            return "No auto-configuration classes found matching filter '$filter'."
        }

        val sorted = filtered.sortedBy { it.qualifiedName }
        val sb = StringBuilder("Auto-configurations (${sorted.size}):\n")

        for (entry in sorted) {
            sb.appendLine()
            sb.appendLine(entry.qualifiedName)
            for (enable in entry.enableAnnotations) {
                sb.appendLine("  $enable")
            }
            for (cond in entry.conditions) {
                sb.appendLine("  $cond")
            }
            sb.appendLine("  File: ${entry.filePath}")
        }

        if (filtered.size < configs.size) {
            sb.appendLine()
            sb.append("(Showing ${filtered.size} of ${configs.size} total)")
        }

        return sb.toString().trimEnd()
    }

    private fun buildAutoConfigEntry(project: Project, cls: PsiClass): AutoConfigEntry? {
        val fqn = cls.qualifiedName ?: return null

        val conditions = mutableListOf<String>()
        val enableAnnotations = mutableListOf<String>()

        for (annotation in cls.annotations) {
            val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
            when {
                shortName == "ConditionalOnClass" -> {
                    val names = extractAutoConfigClassNames(annotation)
                    conditions.add("@ConditionalOnClass(${names.joinToString(", ")})")
                }
                shortName == "ConditionalOnMissingClass" -> {
                    val names = extractAutoConfigClassNames(annotation)
                    conditions.add("@ConditionalOnMissingClass(${names.joinToString(", ")})")
                }
                shortName == "ConditionalOnBean" -> {
                    val names = extractAutoConfigClassNames(annotation)
                    conditions.add("@ConditionalOnBean(${names.joinToString(", ")})")
                }
                shortName == "ConditionalOnMissingBean" -> {
                    val names = extractAutoConfigClassNames(annotation)
                    conditions.add("@ConditionalOnMissingBean(${names.joinToString(", ")})")
                }
                shortName == "ConditionalOnProperty" -> {
                    val propDesc = extractAutoConfigPropertyCondition(annotation)
                    conditions.add("@ConditionalOnProperty($propDesc)")
                }
                shortName == "ConditionalOnResource" -> {
                    val resources = extractAutoConfigStringValues(annotation, "resources")
                        .ifEmpty { extractAutoConfigStringValues(annotation, "value") }
                    conditions.add("@ConditionalOnResource(${resources.joinToString(", ")})")
                }
                shortName == "ConditionalOnWebApplication" -> {
                    val type = extractAutoConfigStringValue(annotation, "type")
                    conditions.add(if (type != null) "@ConditionalOnWebApplication(type=$type)" else "@ConditionalOnWebApplication")
                }
                shortName == "ConditionalOnNotWebApplication" -> {
                    conditions.add("@ConditionalOnNotWebApplication")
                }
                shortName == "ConditionalOnExpression" -> {
                    val expr = extractAutoConfigStringValue(annotation, "value")
                    conditions.add("@ConditionalOnExpression(${expr ?: "..."})")
                }
                shortName == "ConditionalOnJava" -> {
                    val range = extractAutoConfigStringValue(annotation, "range")
                    val value = extractAutoConfigStringValue(annotation, "value")
                    val desc = listOfNotNull(range?.let { "range=$it" }, value?.let { "value=$it" })
                        .joinToString(", ")
                    conditions.add("@ConditionalOnJava($desc)")
                }
                shortName == "ConditionalOnSingleCandidate" -> {
                    val names = extractAutoConfigClassNames(annotation)
                    conditions.add("@ConditionalOnSingleCandidate(${names.joinToString(", ")})")
                }
                shortName == "ConditionalOnCloudPlatform" -> {
                    val value = extractAutoConfigStringValue(annotation, "value")
                    conditions.add("@ConditionalOnCloudPlatform(${value ?: "..."})")
                }
                shortName.startsWith("Enable") -> {
                    enableAnnotations.add("@$shortName")
                }
            }
        }

        val filePath = cls.containingFile?.virtualFile?.path
            ?.let { PsiToolUtils.relativePath(project, it) }
            ?: "(unknown)"

        return AutoConfigEntry(
            qualifiedName = fqn,
            conditions = conditions,
            enableAnnotations = enableAnnotations,
            filePath = filePath
        )
    }

    private fun extractAutoConfigClassNames(annotation: PsiAnnotation): List<String> {
        val results = mutableListOf<String>()
        for (attrName in listOf("value", "name", "type")) {
            val attrValue = annotation.findAttributeValue(attrName) ?: continue
            val text = attrValue.text ?: continue
            val cleaned = text
                .removeSurrounding("{", "}")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            for (token in cleaned) {
                val name = token
                    .removeSuffix(".class")
                    .removeSuffix("::class")
                    .removeSuffix("::class.java")
                    .removeSurrounding("\"")
                    .trim()
                if (name.isNotBlank()) results.add(name)
            }
            if (results.isNotEmpty()) break
        }
        return results.ifEmpty { listOf("...") }
    }

    private fun extractAutoConfigStringValue(annotation: PsiAnnotation, attr: String): String? {
        val value = annotation.findAttributeValue(attr) ?: return null
        val text = value.text ?: return null
        val stripped = text.removeSurrounding("\"")
        return if (stripped.isNotBlank() && stripped != text) stripped else text.trim()
    }

    private fun extractAutoConfigStringValues(annotation: PsiAnnotation, attr: String): List<String> {
        val value = annotation.findAttributeValue(attr) ?: return emptyList()
        val text = value.text ?: return emptyList()
        return text
            .removeSurrounding("{", "}")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }

    private fun extractAutoConfigPropertyCondition(annotation: PsiAnnotation): String {
        val name = extractAutoConfigStringValues(annotation, "name")
            .ifEmpty { extractAutoConfigStringValues(annotation, "value") }
        val havingValue = extractAutoConfigStringValue(annotation, "havingValue")
        val prefix = extractAutoConfigStringValue(annotation, "prefix")
        val matchIfMissing = extractAutoConfigStringValue(annotation, "matchIfMissing")

        val parts = mutableListOf<String>()
        if (prefix != null && prefix.isNotBlank()) parts.add("prefix=$prefix")
        if (name.isNotEmpty()) parts.add("name=${name.joinToString(", ")}")
        if (havingValue != null && havingValue.isNotBlank()) parts.add("havingValue=$havingValue")
        if (matchIfMissing != null && matchIfMissing != "false") parts.add("matchIfMissing=$matchIfMissing")
        return parts.joinToString(", ").ifBlank { "..." }
    }

    private data class AutoConfigEntry(
        val qualifiedName: String,
        val conditions: List<String>,
        val enableAnnotations: List<String>,
        val filePath: String
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: boot_config_properties (from SpringBootConfigPropertiesTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private suspend fun executeBootConfigProperties(params: JsonObject, project: Project): ToolResult {
        if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

        val prefixFilter = params["prefix"]?.jsonPrimitive?.contentOrNull
        val classNameFilter = params["class_name"]?.jsonPrimitive?.contentOrNull

        val content: String = try {
            ReadAction.nonBlocking<String> {
                collectConfigProperties(project, prefixFilter, classNameFilter)
            }.inSmartMode(project).executeSynchronously()
        } catch (e: NoClassDefFoundError) {
            return ToolResult("Spring plugin not available.", "No Spring", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            return ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val filterDesc = listOfNotNull(
            prefixFilter?.let { "prefix=$it" },
            classNameFilter?.let { "class=$it" }
        ).joinToString(", ")

        return ToolResult(
            content = content,
            summary = "Spring Boot @ConfigurationProperties listed${if (filterDesc.isNotEmpty()) " ($filterDesc)" else ""}",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun collectConfigProperties(
        project: Project,
        prefixFilter: String?,
        classNameFilter: String?
    ): String {
        val scope = GlobalSearchScope.projectScope(project)
        val allScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val annotationFqn = "org.springframework.boot.context.properties.ConfigurationProperties"
        val annotationClass = facade.findClass(annotationFqn, allScope)
            ?: return "No @ConfigurationProperties classes found — Spring Boot not on classpath."

        val annotatedClasses = AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll()

        if (annotatedClasses.isEmpty()) {
            return "No @ConfigurationProperties classes found in project."
        }

        val entries = annotatedClasses.mapNotNull { cls -> buildConfigPropertiesEntry(project, cls) }

        val filtered = entries.filter { entry ->
            val matchesPrefix = prefixFilter == null ||
                entry.prefix.contains(prefixFilter, ignoreCase = true)
            val matchesClass = classNameFilter == null ||
                entry.simpleClassName.contains(classNameFilter, ignoreCase = true) ||
                entry.qualifiedName.contains(classNameFilter, ignoreCase = true)
            matchesPrefix && matchesClass
        }

        if (filtered.isEmpty()) {
            val filterDesc = listOfNotNull(
                prefixFilter?.let { "prefix='$it'" },
                classNameFilter?.let { "class='$it'" }
            ).joinToString(", ")
            return "No @ConfigurationProperties classes found matching $filterDesc."
        }

        val sorted = filtered.sortedWith(compareBy({ it.prefix }, { it.simpleClassName }))

        val sb = StringBuilder("@ConfigurationProperties classes (${sorted.size}):\n")
        for (entry in sorted) {
            sb.appendLine()
            sb.appendLine("@ConfigurationProperties(prefix = \"${entry.prefix}\")")
            sb.appendLine("class ${entry.simpleClassName}  (${entry.filePath})")
            for (field in entry.fields) {
                sb.appendLine(formatConfigPropertiesField(entry.prefix, field))
            }
        }

        if (filtered.size < entries.size) {
            sb.appendLine()
            sb.append("(Showing ${filtered.size} of ${entries.size} total)")
        }

        return sb.toString().trimEnd()
    }

    private fun buildConfigPropertiesEntry(project: Project, cls: PsiClass): ConfigPropertiesEntry? {
        val fqn = cls.qualifiedName ?: return null
        val simpleName = cls.name ?: return null

        val annotation = cls.getAnnotation("org.springframework.boot.context.properties.ConfigurationProperties")
            ?: return null

        val prefix = extractConfigPropertiesPrefix(annotation)

        val fields = cls.fields
            .filter { !it.hasModifierProperty(PsiModifier.STATIC) }
            .map { buildConfigFieldInfo(it) }

        val filePath = cls.containingFile?.virtualFile?.path
            ?.let { PsiToolUtils.relativePath(project, it) }
            ?: "(unknown)"

        return ConfigPropertiesEntry(
            qualifiedName = fqn,
            simpleClassName = simpleName,
            prefix = prefix,
            fields = fields,
            filePath = filePath
        )
    }

    private fun extractConfigPropertiesPrefix(annotation: PsiAnnotation): String {
        for (attrName in listOf("prefix", "value")) {
            val attrValue = annotation.findAttributeValue(attrName) ?: continue
            val text = attrValue.text ?: continue
            val stripped = text.removeSurrounding("\"")
            if (stripped.isNotBlank() && stripped != text) return stripped
        }
        return ""
    }

    private fun buildConfigFieldInfo(field: PsiField): ConfigFieldInfo {
        val name = field.name
        val type = field.type.presentableText
        val defaultValue = field.initializer?.text

        val constraints = mutableListOf<String>()
        for (annotation in field.annotations) {
            val shortName = annotation.qualifiedName?.substringAfterLast('.') ?: continue
            when (shortName) {
                "NotNull", "NotBlank", "NotEmpty" -> constraints.add("@$shortName")
                "Min" -> {
                    val v = extractConfigAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@Min($v)" else "@Min")
                }
                "Max" -> {
                    val v = extractConfigAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@Max($v)" else "@Max")
                }
                "Size" -> {
                    val min = extractConfigAnnotationStringValue(annotation, "min")
                    val max = extractConfigAnnotationStringValue(annotation, "max")
                    val args = listOfNotNull(min?.let { "min=$it" }, max?.let { "max=$it" })
                        .joinToString(", ")
                    constraints.add(if (args.isNotEmpty()) "@Size($args)" else "@Size")
                }
                "Pattern" -> {
                    val regexp = extractConfigAnnotationStringValue(annotation, "regexp")
                    constraints.add(if (regexp != null) "@Pattern(\"$regexp\")" else "@Pattern")
                }
                "Email" -> constraints.add("@Email")
                "Valid" -> constraints.add("@Valid")
                "Validated" -> constraints.add("@Validated")
                "Positive" -> constraints.add("@Positive")
                "PositiveOrZero" -> constraints.add("@PositiveOrZero")
                "Negative" -> constraints.add("@Negative")
                "NegativeOrZero" -> constraints.add("@NegativeOrZero")
                "DecimalMin" -> {
                    val v = extractConfigAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@DecimalMin(\"$v\")" else "@DecimalMin")
                }
                "DecimalMax" -> {
                    val v = extractConfigAnnotationStringValue(annotation, "value")
                    constraints.add(if (v != null) "@DecimalMax(\"$v\")" else "@DecimalMax")
                }
            }
        }

        return ConfigFieldInfo(name = name, type = type, defaultValue = defaultValue, constraints = constraints)
    }

    private fun formatConfigPropertiesField(prefix: String, field: ConfigFieldInfo): String {
        val qualifiedName = if (prefix.isNotEmpty()) "$prefix.${field.name}" else field.name
        val sb = StringBuilder("  $qualifiedName: ${field.type}")
        if (field.defaultValue != null) {
            sb.append(" = ${field.defaultValue}")
        }
        if (field.constraints.isNotEmpty()) {
            sb.append("  ${field.constraints.joinToString(" ")}")
        }
        return sb.toString()
    }

    private fun extractConfigAnnotationStringValue(annotation: PsiAnnotation, attr: String): String? {
        val value = annotation.findAttributeValue(attr) ?: return null
        val text = value.text ?: return null
        val stripped = text.removeSurrounding("\"")
        return if (stripped != text) stripped else text.trim().takeIf { it.isNotBlank() }
    }

    private data class ConfigPropertiesEntry(
        val qualifiedName: String,
        val simpleClassName: String,
        val prefix: String,
        val fields: List<ConfigFieldInfo>,
        val filePath: String
    )

    private data class ConfigFieldInfo(
        val name: String,
        val type: String,
        val defaultValue: String?,
        val constraints: List<String>
    )

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: boot_actuator (from SpringBootActuatorTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val defaultActuatorEndpoints = listOf(
        "health"         to "Shows application health",
        "info"           to "Application info",
        "metrics"        to "Application metrics",
        "env"            to "Environment properties",
        "beans"          to "All Spring beans",
        "configprops"    to "Configuration properties",
        "mappings"       to "Request mapping paths",
        "loggers"        to "Logger levels",
        "threaddump"     to "Thread dump",
        "heapdump"       to "Heap dump",
        "conditions"     to "Auto-configuration conditions",
        "shutdown"       to "Graceful shutdown (disabled by default)",
        "scheduledtasks" to "Scheduled tasks",
        "caches"         to "Cache managers",
        "prometheus"     to "Prometheus metrics"
    )

    private suspend fun executeBootActuator(params: JsonObject, project: Project): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult(
                "Error: project base path not available",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            withContext(Dispatchers.IO) {
                analyzeActuator(project, File(basePath))
            }
        } catch (e: Exception) {
            ToolResult(
                "Error analyzing Spring Boot Actuator: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun analyzeActuator(project: Project, baseDir: File): ToolResult {
        val actuatorDetected = checkActuatorDependency(project, baseDir)

        val mgmtProps = readManagementProperties(baseDir)

        val basePath = mgmtProps["management.endpoints.web.base-path"]
            ?: mgmtProps["management.server.base-path"]
            ?: "/actuator"

        val port = mgmtProps["management.server.port"]

        val includeRaw = mgmtProps["management.endpoints.web.exposure.include"] ?: ""
        val excludeRaw = mgmtProps["management.endpoints.web.exposure.exclude"] ?: ""

        val includeSet: Set<String> = if (includeRaw.isBlank()) {
            setOf("health", "info")
        } else {
            includeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        val excludeSet: Set<String> = excludeRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val showDetails = mgmtProps["management.endpoint.health.show-details"]

        val content = buildString {
            appendLine("Spring Boot Actuator:")
            appendLine()

            if (actuatorDetected) {
                appendLine("OK spring-boot-starter-actuator detected")
            } else {
                appendLine("MISSING spring-boot-starter-actuator NOT detected")
                appendLine("  Add to pom.xml: <artifactId>spring-boot-starter-actuator</artifactId>")
                appendLine("  or build.gradle: implementation 'org.springframework.boot:spring-boot-starter-actuator'")
            }

            appendLine()
            appendLine("Management:")
            appendLine("  Base path: $basePath")
            if (port != null) {
                appendLine("  Port: $port (separate from app)")
            } else {
                appendLine("  Port: same as application")
            }

            appendLine()
            appendLine("Web exposure:")
            appendLine("  Include: ${if (includeSet.contains("*")) "*" else includeSet.sorted().joinToString(",")}")
            appendLine("  Exclude: ${excludeSet.sorted().joinToString(",")}")

            appendLine()
            appendLine("Endpoints:")

            val exposedAll = includeSet.contains("*")
            for ((endpointId, desc) in defaultActuatorEndpoints) {
                val exposed = (exposedAll || includeSet.contains(endpointId)) && !excludeSet.contains(endpointId)
                val marker = if (exposed) "[x]" else "[ ]"
                val paddedId = endpointId.padEnd(16)
                appendLine("  $marker $paddedId — $desc")
            }

            if (showDetails != null) {
                appendLine()
                appendLine("Health endpoint:")
                appendLine("  Show details: $showDetails")
            }

            val extraProps = mgmtProps.filterKeys { key ->
                key != "management.endpoints.web.base-path" &&
                    key != "management.server.base-path" &&
                    key != "management.server.port" &&
                    key != "management.endpoints.web.exposure.include" &&
                    key != "management.endpoints.web.exposure.exclude" &&
                    key != "management.endpoint.health.show-details" &&
                    key.startsWith("management.")
            }
            if (extraProps.isNotEmpty()) {
                appendLine()
                appendLine("Other management properties:")
                for ((key, value) in extraProps.toSortedMap()) {
                    appendLine("  $key = $value")
                }
            }
        }.trimEnd()

        val exposedCount = if (includeSet.contains("*")) {
            defaultActuatorEndpoints.count { !excludeSet.contains(it.first) }
        } else {
            includeSet.count { !excludeSet.contains(it) }
        }

        return ToolResult(
            content = content,
            summary = if (actuatorDetected) "Actuator detected, $exposedCount endpoints exposed" else "Actuator not detected",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun checkActuatorDependency(project: Project, baseDir: File): Boolean {
        try {
            val manager = MavenUtils.getMavenManager(project)
            if (manager != null) {
                val mavenProjects = MavenUtils.getMavenProjects(manager)
                for (mavenProject in mavenProjects) {
                    val deps = MavenUtils.getDependencies(mavenProject)
                    if (deps.any { it.artifactId == "spring-boot-starter-actuator" }) {
                        return true
                    }
                }
                if (mavenProjects.isNotEmpty()) return false
            }
        } catch (_: Exception) { /* fall through to file-based check */ }

        val buildFileNames = listOf("build.gradle", "build.gradle.kts", "pom.xml")
        val searchRoots = mutableListOf(baseDir)
        baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.let { searchRoots.addAll(it) }

        for (root in searchRoots) {
            for (buildFileName in buildFileNames) {
                val buildFile = File(root, buildFileName)
                if (buildFile.isFile) {
                    val text = buildFile.readText()
                    if (text.contains("spring-boot-starter-actuator")) return true
                }
            }
        }

        return false
    }

    private fun readManagementProperties(baseDir: File): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val searchDirs = listOf(
            "src/main/resources",
            "src/main/resources/config"
        )
        val fileNames = listOf(
            "application.properties",
            "application.yml",
            "application.yaml"
        )

        val roots = mutableListOf(baseDir)
        baseDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.let { roots.addAll(it) }

        for (root in roots) {
            for (dir in searchDirs) {
                val resourceDir = File(root, dir)
                if (!resourceDir.isDirectory) continue
                for (fileName in fileNames) {
                    val file = File(resourceDir, fileName)
                    if (!file.isFile) continue
                    when (file.extension.lowercase()) {
                        "properties" -> readPropertiesManagement(file, result)
                        "yml", "yaml" -> readYamlManagement(file, result)
                    }
                }
            }
        }

        return result
    }

    private fun readPropertiesManagement(file: File, target: MutableMap<String, String>) {
        val props = Properties()
        file.inputStream().use { props.load(it) }
        for ((key, value) in props) {
            val k = key.toString()
            if (k.startsWith("management.")) {
                target[k] = value.toString()
            }
        }
    }

    private fun readYamlManagement(file: File, target: MutableMap<String, String>) {
        val lines = file.readLines()
        val keyStack = mutableListOf<Pair<Int, String>>()

        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue

            val indent = line.length - line.trimStart().length

            while (keyStack.isNotEmpty() && keyStack.last().first >= indent) {
                keyStack.removeAt(keyStack.size - 1)
            }

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex < 0) continue

            val key = trimmed.substring(0, colonIndex).trim()
            val value = trimmed.substring(colonIndex + 1).trim()

            keyStack.add(indent to key)

            if (value.isNotEmpty() && !value.startsWith("#")) {
                val fullKey = keyStack.joinToString(".") { it.second }
                if (fullKey.startsWith("management.")) {
                    val cleanValue = value.removeSurrounding("\"").removeSurrounding("'")
                    target[fullKey] = cleanValue
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Action: jpa_entities (from JpaEntitiesTool)
    // ─────────────────────────────────────────────────────────────────────────────

    private val jpaEntityAnnotations = listOf(
        "jakarta.persistence.Entity",
        "javax.persistence.Entity"
    )
    private val jpaTableAnnotations = listOf(
        "jakarta.persistence.Table",
        "javax.persistence.Table"
    )
    private val jpaRelationshipAnnotations = listOf(
        "OneToMany", "ManyToOne", "OneToOne", "ManyToMany"
    )
    private val jpaRelationshipFqnPrefixes = listOf(
        "jakarta.persistence.",
        "javax.persistence."
    )

    private suspend fun executeJpaEntities(params: JsonObject, project: Project): ToolResult {
        if (DumbService.isDumb(project)) return PsiToolUtils.dumbModeError()

        val entityFilter = params["entity"]?.jsonPrimitive?.content

        return try {
            val content = ReadAction.nonBlocking<String> {
                val scope = GlobalSearchScope.projectScope(project)
                val allScope = GlobalSearchScope.allScope(project)
                val facade = JavaPsiFacade.getInstance(project)

                val entityAnnotationClass = jpaEntityAnnotations.firstNotNullOfOrNull { fqn ->
                    facade.findClass(fqn, allScope)
                } ?: return@nonBlocking "JPA not found in project dependencies. Ensure jakarta.persistence or javax.persistence is on the classpath."

                val entities = AnnotatedElementsSearch.searchPsiClasses(entityAnnotationClass, scope).findAll()

                if (entities.isEmpty()) {
                    return@nonBlocking "No @Entity classes found in project."
                }

                val targetEntities = if (entityFilter != null) {
                    val filtered = entities.filter { cls ->
                        cls.name.equals(entityFilter, ignoreCase = true) ||
                            cls.qualifiedName.equals(entityFilter, ignoreCase = true)
                    }
                    if (filtered.isEmpty()) {
                        return@nonBlocking "Entity '$entityFilter' not found. Available entities: ${entities.mapNotNull { it.name }.sorted().joinToString(", ")}"
                    }
                    filtered
                } else {
                    entities.toList()
                }

                buildString {
                    appendLine("JPA Entities (${targetEntities.size}):")
                    appendLine()

                    for (psiClass in targetEntities.sortedBy { it.name }) {
                        formatJpaEntity(psiClass, this, detailed = entityFilter != null)
                    }
                }
            }.inSmartMode(project).executeSynchronously()

            ToolResult(
                content = content,
                summary = if (entityFilter != null) "JPA entity: $entityFilter" else "JPA entities",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error scanning JPA entities: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun formatJpaEntity(psiClass: PsiClass, sb: StringBuilder, detailed: Boolean) {
        val className = psiClass.qualifiedName ?: psiClass.name ?: "?"
        val tableName = getJpaTableName(psiClass) ?: psiClass.name?.lowercase() ?: "?"
        val file = psiClass.containingFile?.virtualFile?.path ?: ""

        sb.appendLine("@Entity ${psiClass.name} (table: $tableName)")
        if (detailed) {
            sb.appendLine("  Class: $className")
            sb.appendLine("  File: $file")
        }

        val allFields = psiClass.allFields.filter { field ->
            !field.hasModifierProperty(PsiModifier.STATIC) &&
                !field.hasModifierProperty(PsiModifier.TRANSIENT)
        }

        val idFields = mutableListOf<PsiField>()
        val columnFields = mutableListOf<PsiField>()
        val relationshipFields = mutableListOf<PsiField>()

        for (field in allFields) {
            when {
                hasJpaAnnotation(field, "Id") -> idFields.add(field)
                hasJpaRelationshipAnnotation(field) -> relationshipFields.add(field)
                else -> columnFields.add(field)
            }
        }

        if (idFields.isNotEmpty()) {
            sb.appendLine("  ID:")
            for (field in idFields) {
                val colName = getJpaColumnName(field) ?: field.name
                val genStrategy = getJpaGenerationStrategy(field)
                val gen = if (genStrategy != null) " ($genStrategy)" else ""
                sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName$gen")
            }
        }

        if (detailed && columnFields.isNotEmpty()) {
            sb.appendLine("  Columns:")
            for (field in columnFields) {
                val colName = getJpaColumnName(field) ?: field.name
                sb.appendLine("    ${field.type.presentableText} ${field.name} -> $colName")
            }
        } else if (columnFields.isNotEmpty()) {
            sb.appendLine("  Columns: ${columnFields.size} fields")
        }

        if (relationshipFields.isNotEmpty()) {
            sb.appendLine("  Relationships:")
            for (field in relationshipFields) {
                val relType = getJpaRelationshipType(field)
                val targetType = field.type.presentableText
                sb.appendLine("    @$relType ${field.name}: $targetType")
            }
        }

        sb.appendLine()
    }

    private fun getJpaTableName(psiClass: PsiClass): String? {
        for (prefix in jpaTableAnnotations) {
            val annotation = psiClass.getAnnotation(prefix)
            if (annotation != null) {
                return getJpaAnnotationStringValue(annotation, "name")
            }
        }
        return null
    }

    private fun getJpaColumnName(field: PsiField): String? {
        for (prefix in listOf("jakarta.persistence.Column", "javax.persistence.Column")) {
            val annotation = field.getAnnotation(prefix)
            if (annotation != null) {
                return getJpaAnnotationStringValue(annotation, "name")
            }
        }
        return null
    }

    private fun getJpaGenerationStrategy(field: PsiField): String? {
        for (prefix in listOf("jakarta.persistence.GeneratedValue", "javax.persistence.GeneratedValue")) {
            val annotation = field.getAnnotation(prefix)
            if (annotation != null) {
                return getJpaAnnotationStringValue(annotation, "strategy") ?: "AUTO"
            }
        }
        return null
    }

    private fun hasJpaAnnotation(field: PsiField, simpleName: String): Boolean {
        return field.getAnnotation("jakarta.persistence.$simpleName") != null ||
            field.getAnnotation("javax.persistence.$simpleName") != null
    }

    private fun hasJpaRelationshipAnnotation(field: PsiField): Boolean {
        return jpaRelationshipAnnotations.any { rel ->
            jpaRelationshipFqnPrefixes.any { prefix ->
                field.getAnnotation("$prefix$rel") != null
            }
        }
    }

    private fun getJpaRelationshipType(field: PsiField): String {
        for (rel in jpaRelationshipAnnotations) {
            for (prefix in jpaRelationshipFqnPrefixes) {
                if (field.getAnnotation("$prefix$rel") != null) return rel
            }
        }
        return "?"
    }

    private fun getJpaAnnotationStringValue(annotation: PsiAnnotation, attribute: String): String? {
        val value = annotation.findAttributeValue(attribute) ?: return null
        val text = value.text.removeSurrounding("\"")
        return if (text.isBlank() || text == "\"\"") null else text
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    companion object {
        internal const val SPRING_PLUGIN_MISSING_MSG =
            "Error: Spring plugin is not available. Install 'Spring' plugin in IntelliJ Ultimate to use this tool."
    }
}
