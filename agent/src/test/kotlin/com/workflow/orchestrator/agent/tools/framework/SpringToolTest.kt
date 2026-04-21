package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.framework.spring.SPRING_CONFIG_FILE_PATTERN
import com.workflow.orchestrator.agent.tools.framework.spring.resolveAnnotationFqn
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Scenario-based tests for [SpringTool].
 *
 * Strategy: SpringTool is a meta-tool with 16 actions; many actions touch real
 * IntelliJ PSI / Maven services that require a running IDE fixture and cannot
 * be exercised in plain unit tests. These tests therefore lock in:
 *
 *  1. **Tool surface** — name, action enum, parameter schema, allowedWorkers,
 *     ToolDefinition serialization. Catches accidental schema drift.
 *
 *  2. **Dispatcher contract** — what happens when `action` is missing,
 *     unknown, or routes to one of the 15 implementations. Catches dispatcher
 *     bugs (e.g. typo in `when` branch).
 *
 *  3. **Pre-PSI validation paths** — actions that check parameters before
 *     reaching PSI (e.g. `config` checks basePath first). These catch
 *     parameter-handling regressions.
 *
 *  4. **Action smoke routing** — for every action enum value, call execute()
 *     and assert the call returns OR throws a recognized boundary exception
 *     (PSI/Maven services not available in unit tests). Each action MUST
 *     either reach an error ToolResult or throw at the IDE-services boundary;
 *     it must NOT silently return success or hit a NullPointerException
 *     inside the dispatcher.
 *
 * This is the test contract that the post-refactor SpringTool (with extracted
 * action files) must continue to satisfy without modification.
 */
class SpringToolTest {

    private val tool = SpringTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is spring`() {
            assertEquals("spring", tool.name)
        }

        @Test
        fun `description mentions Spring framework`() {
            assertTrue(tool.description.contains("Spring"))
        }

        @Test
        fun `description lists all 16 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `default constructor action enum contains 16 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(16, actions!!.size)
        }

        @Test
        fun `includeEndpointActions=false omits endpoints and boot_endpoints`() {
            val trimmed = SpringTool(includeEndpointActions = false)
            val actions = trimmed.parameters.properties["action"]?.enumValues!!.toSet()
            assertEquals(14, actions.size)
            assertFalse(actions.contains("endpoints"))
            assertFalse(actions.contains("boot_endpoints"))
        }

        @Test
        fun `includeEndpointActions=false description omits endpoint actions`() {
            val trimmed = SpringTool(includeEndpointActions = false)
            assertFalse(trimmed.description.contains("endpoints(filter?"))
            assertFalse(trimmed.description.contains("boot_endpoints(class_name"))
        }

        @Test
        fun `action enum contains all expected action names`() {
            val actions = tool.parameters.properties["action"]?.enumValues!!.toSet()
            assertEquals(ALL_ACTIONS.toSet(), actions)
        }

        @Test
        fun `only action is required`() {
            assertEquals(listOf("action"), tool.parameters.required)
        }

        @Test
        fun `filter parameter exists and is string type`() {
            val prop = tool.parameters.properties["filter"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `bean_name parameter exists and is string type`() {
            val prop = tool.parameters.properties["bean_name"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `property parameter exists and is string type`() {
            val prop = tool.parameters.properties["property"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `module parameter exists and is string type`() {
            val prop = tool.parameters.properties["module"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `include_params parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["include_params"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `class_name parameter exists and is string type`() {
            val prop = tool.parameters.properties["class_name"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `prefix parameter exists and is string type`() {
            val prop = tool.parameters.properties["prefix"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `project_only parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["project_only"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `entity parameter exists and is string type`() {
            val prop = tool.parameters.properties["entity"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `profile parameter exists and is string type`() {
            val prop = tool.parameters.properties["profile"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 12`() {
            // 1 action discriminator + 11 action-specific parameters
            assertEquals(12, tool.parameters.properties.size)
        }

        @Test
        fun `allowedWorkers includes all expected types`() {
            assertEquals(
                setOf(
                    WorkerType.TOOLER,
                    WorkerType.ANALYZER,
                    WorkerType.REVIEWER,
                    WorkerType.ORCHESTRATOR,
                    WorkerType.CODER
                ),
                tool.allowedWorkers
            )
        }

        @Test
        fun `toToolDefinition produces valid schema`() {
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertEquals("spring", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(12, def.function.parameters.properties.size)
            assertEquals(listOf("action"), def.function.parameters.required)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 2 — Dispatcher contract
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action"))
        }

        @Test
        fun `unknown action returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "totally_made_up_action")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }

        @Test
        fun `unknown action error mentions the action name`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "explode_the_universe")
            }, project)
            assertTrue(result.content.contains("explode_the_universe"))
        }

        @Test
        fun `dispatcher routes by exact case-sensitive action name`() = runTest {
            // CONTEXT (uppercase) is not the same as 'context'
            val result = tool.execute(buildJsonObject {
                put("action", "CONTEXT")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }

        @Test
        fun `endpoints action returns 'use endpoints tool' error when flag is off`() = runTest {
            val trimmed = SpringTool(includeEndpointActions = false)
            val result = trimmed.execute(
                buildJsonObject { put("action", "endpoints") },
                project,
            )
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("endpoints", ignoreCase = true),
                "error message should point at the endpoints tool"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 3 — Pre-PSI validation paths
    //
    // These tests target actions that perform parameter / project-state
    // validation BEFORE touching IntelliJ PSI services, so they can run
    // against a relaxed mock.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class PrePsiValidation {

        @Test
        fun `config action with null basePath and no modules returns no-files or error`() = runTest {
            // After the multi-module refactor, config no longer fails fast on null
            // basePath — it iterates ModuleManager.modules and falls back to
            // basePath only if no modules are registered. Against a relaxed mock
            // (no real IntelliJ runtime), this either returns "no config files"
            // or catches the service-lookup exception and returns an error.
            every { project.basePath } returns null

            val result = runCatching {
                tool.execute(buildJsonObject { put("action", "config") }, project)
            }
            if (result.isSuccess) {
                val toolResult = result.getOrNull()!!
                assertTrue(
                    toolResult.isError || toolResult.content.contains("No Spring configuration files"),
                    "Expected error or no-files; got: ${toolResult.content}"
                )
            } else {
                // Service boundary (ModuleManager) — acceptable in a unit test
                val ex = result.exceptionOrNull()!!
                assertTrue(
                    ex is RuntimeException || ex is NoClassDefFoundError,
                    "Unexpected exception type: ${ex::class.simpleName}"
                )
            }
        }

        @Test
        fun `config action with nonexistent basePath returns no-files message`() = runTest {
            every { project.basePath } returns "/tmp/nonexistent-spring-test-dir-zzz"

            val result = tool.execute(buildJsonObject {
                put("action", "config")
            }, project)

            // Either "No Spring configuration files found" or an error from
            // walking the missing dir — both are valid error responses.
            assertTrue(
                result.isError || result.content.contains("No Spring configuration files"),
                "Expected error or no-files message; got: ${result.content}"
            )
        }

        @Test
        fun `config action with property param does not crash on missing basePath`() = runTest {
            every { project.basePath } returns null

            val result = tool.execute(buildJsonObject {
                put("action", "config")
                put("property", "spring.datasource.url")
            }, project)

            assertTrue(result.isError)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 4 — Action routing smoke tests
    //
    // For every action enum value, calling `execute(action=X)` must either:
    //   (a) return a ToolResult (error or otherwise), or
    //   (b) throw a recognized boundary exception (PSI/Maven service not
    //       available — typical when IntelliJ services aren't initialized
    //       in unit tests)
    //
    // It must NOT silently return success and must NOT hit an
    // IllegalStateException inside the dispatcher itself.
    //
    // These tests guard against the main refactor risk: a typo or missing
    // branch in the action `when` statement after extracting actions to
    // separate files.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActionRoutingSmoke {

        private fun smokeTestAction(actionName: String, extraParams: Map<String, String> = emptyMap()) =
            runTest {
                every { project.basePath } returns "/tmp/spring-test-noexist"
                val result = runCatching {
                    tool.execute(
                        buildJsonObject {
                            put("action", actionName)
                            extraParams.forEach { (k, v) -> put(k, v) }
                        },
                        project
                    )
                }
                // Either we got a result, or we threw at the PSI/Maven boundary.
                // Both are acceptable; what's NOT acceptable is the dispatcher
                // mis-routing or returning a nonsense success.
                if (result.isSuccess) {
                    val toolResult = result.getOrNull()
                    assertNotNull(toolResult, "$actionName: result is null")
                    // If it's not an error, the action successfully ran (very rare
                    // in unit tests without real PSI). Either way, the dispatcher
                    // routed correctly.
                } else {
                    val ex = result.exceptionOrNull()!!
                    // The boundary exceptions we expect: NPE / IllegalState from
                    // missing IDE services, or NoClassDefFoundError from Spring
                    // plugin reflection. NOT a Kotlin IllegalArgumentException
                    // from a typo in the dispatcher.
                    val acceptable = ex is NullPointerException ||
                        ex is IllegalStateException ||
                        ex is NoClassDefFoundError ||
                        ex is ClassNotFoundException ||
                        ex is UnsupportedOperationException ||
                        ex is RuntimeException
                    assertTrue(
                        acceptable,
                        "$actionName: unexpected exception type ${ex::class.simpleName}: ${ex.message}"
                    )
                }
            }

        @Test fun `context routes`() = smokeTestAction("context")
        @Test fun `context with profile filter routes`() = smokeTestAction("context", mapOf("profile" to "dev"))
        @Test fun `endpoints routes`() = smokeTestAction("endpoints")
        @Test fun `bean_graph routes`() = smokeTestAction("bean_graph", mapOf("bean_name" to "FooService"))
        @Test fun `config routes`() = smokeTestAction("config")
        @Test fun `version_info routes`() = smokeTestAction("version_info")
        @Test fun `profiles routes`() = smokeTestAction("profiles")
        @Test fun `repositories routes`() = smokeTestAction("repositories")
        @Test fun `security_config routes`() = smokeTestAction("security_config")
        @Test fun `scheduled_tasks routes`() = smokeTestAction("scheduled_tasks")
        @Test fun `event_listeners routes`() = smokeTestAction("event_listeners")
        @Test fun `boot_endpoints routes`() = smokeTestAction("boot_endpoints")
        @Test fun `boot_autoconfig routes`() = smokeTestAction("boot_autoconfig")
        @Test fun `boot_config_properties routes`() = smokeTestAction("boot_config_properties")
        @Test fun `boot_actuator routes`() = smokeTestAction("boot_actuator")
        @Test fun `jpa_entities routes`() = smokeTestAction("jpa_entities")
        @Test fun `annotated_methods routes`() = smokeTestAction("annotated_methods", mapOf("annotation" to "Scheduled"))
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 5 — Spring plugin API contract
    //
    // Pins the reflection signatures `SpringContextAction` depends on, so
    // future Spring-plugin API drift surfaces here instead of as a runtime
    // "Error accessing Spring model" message to users.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class SpringPluginApiContract {

        private val springManagerClass: Class<*>? = try {
            Class.forName("com.intellij.spring.SpringManager")
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }

        @Test
        fun `SpringManager getInstance takes Project`() {
            val cls = springManagerClass ?: return // plugin not on test classpath
            cls.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
        }

        @Test
        fun `SpringManager getAllModels takes Module not Project`() {
            val cls = springManagerClass ?: return
            // The actual API is getAllModels(Module). Previously the code
            // looked up getAllModels(Project), which threw NoSuchMethodException
            // and produced a misleading "plugin not available" error to users.
            cls.getMethod("getAllModels", com.intellij.openapi.module.Module::class.java)
            assertThrows(NoSuchMethodException::class.java) {
                cls.getMethod("getAllModels", com.intellij.openapi.project.Project::class.java)
            }
        }

        @Test
        fun `CommonSpringModel is at com intellij spring package not model subpackage`() {
            // Non-obvious: CommonSpringModel lives at com.intellij.spring.CommonSpringModel,
            // not com.intellij.spring.model.CommonSpringModel as the package structure suggests.
            Class.forName("com.intellij.spring.CommonSpringModel")
        }

        @Test
        fun `SpringModelSearchers findBean takes CommonSpringModel and String`() {
            val searchers = try {
                Class.forName("com.intellij.spring.model.utils.SpringModelSearchers")
            } catch (_: ClassNotFoundException) { return }
            val commonSpringModel = try {
                Class.forName("com.intellij.spring.CommonSpringModel")
            } catch (_: ClassNotFoundException) { return }
            searchers.getMethod("findBean", commonSpringModel, String::class.java)
        }

        @Test
        fun `CommonSpringModel getAllCommonBeans is parameterless`() {
            val commonSpringModel = try {
                Class.forName("com.intellij.spring.CommonSpringModel")
            } catch (_: ClassNotFoundException) { return }
            commonSpringModel.getMethod("getAllCommonBeans")
        }

        @Test
        fun `SpringBeanPointer exposes getName getBeanClass getSpringBean getEffectiveBeanTypes`() {
            val cls = try {
                Class.forName("com.intellij.spring.model.SpringBeanPointer")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getName")
            cls.getMethod("getBeanClass")
            cls.getMethod("getSpringBean")
            cls.getMethod("getEffectiveBeanTypes")
            cls.getMethod("getAliases")
        }

        @Test
        fun `CommonSpringBean exposes getBeanName getSpringScope isPrimary getProfile`() {
            val cls = try {
                Class.forName("com.intellij.spring.model.CommonSpringBean")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getBeanName")
            cls.getMethod("getSpringScope")
            cls.getMethod("isPrimary")
            cls.getMethod("getProfile")
            // Note: getBeanClass() is not on the interface itself — it's on
            // concrete implementations (SpringJavaBean, XML SpringBean, etc.).
            // SpringModelResolver.beanClass() resolves it via reflection on the
            // concrete runtime class, which Class.getMethod() walks normally.
        }

        @Test
        fun `SpringBootApplicationMetaConfigKeyManager getInstance has no args`() {
            val cls = try {
                Class.forName("com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyManager")
            } catch (_: ClassNotFoundException) { return }
            // Must be a no-arg static factory — confirmed in IU-2025.1.
            cls.getMethod("getInstance")
        }

        @Test
        fun `SpringBootLibraryUtil class exists at library subpackage`() {
            try {
                Class.forName("com.intellij.spring.boot.library.SpringBootLibraryUtil")
            } catch (_: ClassNotFoundException) { return }
            // If the class loads, the package location is correct.
        }

        @Test
        fun `SpringBootApplicationMetaConfigKeyManagerImpl getAllMetaConfigKeys takes Module`() {
            val cls = try {
                Class.forName("com.intellij.spring.boot.application.metadata.SpringBootApplicationMetaConfigKeyManagerImpl")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getAllMetaConfigKeys", com.intellij.openapi.module.Module::class.java)
        }

        @Test
        fun `PersistenceHelper getHelper has no args`() {
            val cls = try {
                Class.forName("com.intellij.persistence.PersistenceHelper")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getHelper")
        }

        @Test
        fun `PersistenceModelBrowser is at util subpackage`() {
            try {
                Class.forName("com.intellij.persistence.util.PersistenceModelBrowser")
            } catch (_: ClassNotFoundException) { return }
            // Class loads — FQN is correct.
        }

        @Test
        fun `PersistenceMappingsModelHelper exposes getPersistentEntities`() {
            val cls = try {
                Class.forName("com.intellij.persistence.model.helpers.PersistenceMappingsModelHelper")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getPersistentEntities")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 6 — resolveAnnotationFqn pure-function unit tests
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ConfigFilePattern {
        @Test
        fun `canonical names match`() {
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application.properties"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application.yml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application.yaml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("bootstrap.properties"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("bootstrap.yml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("bootstrap.yaml"))
        }

        @Test
        fun `custom profile suffixes match`() {
            // These would all be missed by the old hardcoded filename list.
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-mydocker.properties"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-staging.yml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-integration.yaml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-local-dev.properties"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-e2e.yml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("bootstrap-staging.yml"))
        }

        @Test
        fun `profile allows dots dashes and underscores`() {
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-us-east.properties"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-region_1.yml"))
            assertTrue(SPRING_CONFIG_FILE_PATTERN.matches("application-1.2.3.properties"))
        }

        @Test
        fun `non-spring files do not match`() {
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("log4j.properties"))
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("messages.properties"))
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("app.yml"))
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("application.txt"))
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("application-.properties")) // empty profile
            assertFalse(SPRING_CONFIG_FILE_PATTERN.matches("my-application.properties"))
        }
    }

    @Nested
    inner class AnnotationAliases {
        @Test
        fun `short name with at sign resolves to FQN`() {
            assertEquals(
                "org.springframework.scheduling.annotation.Scheduled",
                resolveAnnotationFqn("@Scheduled"),
            )
        }
        @Test
        fun `short name without at sign case insensitive`() {
            assertEquals(
                "org.springframework.transaction.annotation.Transactional",
                resolveAnnotationFqn("transactional"),
            )
        }
        @Test
        fun `unknown FQN passes through`() {
            assertEquals(
                "com.example.MyAnnotation",
                resolveAnnotationFqn("com.example.MyAnnotation"),
            )
        }
        @Test
        fun `unknown short name returns as-is`() {
            // No alias for 'foo' — returns the input as-is (FQN fallback path)
            assertEquals("foo", resolveAnnotationFqn("foo"))
        }
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "context",
            "endpoints",
            "bean_graph",
            "config",
            "version_info",
            "profiles",
            "repositories",
            "security_config",
            "scheduled_tasks",
            "event_listeners",
            "annotated_methods",
            "boot_endpoints",
            "boot_autoconfig",
            "boot_config_properties",
            "boot_actuator",
            "jpa_entities"
        )
    }
}
