package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HttpScratchTemplateTest {
    @Test
    fun `renders GET endpoint with no body`() {
        val block = renderHttpBlock(
            handlerId = "UserController.getById",
            method = "GET",
            url = "http://localhost:8080/api/users/{id}",
            bodyPlaceholder = null,
        )
        val expected = """
            ### UserController.getById
            GET http://localhost:8080/api/users/{id}
        """.trimIndent() + "\n"
        assertEquals(expected, block)
    }

    @Test
    fun `renders POST endpoint with JSON body and content type`() {
        val block = renderHttpBlock(
            handlerId = "UserController.create",
            method = "POST",
            url = "http://localhost:8080/api/users",
            bodyPlaceholder = """{"name":"","email":""}""",
        )
        val expected = """
            ### UserController.create
            POST http://localhost:8080/api/users
            Content-Type: application/json

            {"name":"","email":""}
        """.trimIndent() + "\n"
        assertEquals(expected, block)
    }
}

class DtoPlaceholderTest {
    @Test
    fun `primitive Kotlin types return valid JSON literals`() {
        assertEquals("\"\"", defaultJsonLiteral("String"))
        assertEquals("0", defaultJsonLiteral("Int"))
        assertEquals("0", defaultJsonLiteral("Long"))
        assertEquals("0.0", defaultJsonLiteral("Double"))
        assertEquals("false", defaultJsonLiteral("Boolean"))
    }

    @Test
    fun `collections return empty array`() {
        assertEquals("[]", defaultJsonLiteral("List<String>"))
        assertEquals("[]", defaultJsonLiteral("Set<Int>"))
    }

    @Test
    fun `unknown type returns null literal`() {
        assertEquals("null", defaultJsonLiteral("SomeCustomType"))
    }
}

/**
 * Surface + dispatcher contract tests for [EndpointsTool].
 *
 * Integration behaviour (actual microservices EP iteration) is covered by
 * running the plugin under a real IntelliJ fixture — plain JUnit cannot spin
 * up `EndpointsProvider`. These tests lock down schema and dispatcher routing.
 */
class EndpointsToolTest {

    private val tool = EndpointsTool()
    private val project = mockk<Project>(relaxed = true)

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is endpoints`() {
            assertEquals("endpoints", tool.name)
        }

        @Test
        fun `action enum contains all six actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues?.toSet()
            assertEquals(
                setOf("list", "find_usages", "export_openapi", "export_http_scratch", "list_async", "service_graph"),
                actions,
            )
        }

        @Test
        fun `description mentions all six actions`() {
            val d = tool.description
            assertTrue(d.contains("list"))
            assertTrue(d.contains("find_usages"))
            assertTrue(d.contains("export_openapi"))
            assertTrue(d.contains("export_http_scratch"))
            assertTrue(d.contains("list_async"))
            assertTrue(d.contains("service_graph"))
        }

        @Test
        fun `allowedWorkers includes tooler, analyzer, reviewer, orchestrator, coder`() {
            assertEquals(
                setOf(
                    WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER,
                    WorkerType.ORCHESTRATOR, WorkerType.CODER
                ),
                tool.allowedWorkers,
            )
        }

        @Test
        fun `only action is required`() {
            assertEquals(listOf("action"), tool.parameters.required)
        }
    }

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error result`() = runTest {
            val result = tool.execute(buildJsonObject {}, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action", ignoreCase = true))
        }

        @Test
        fun `unknown action returns error result`() = runTest {
            val result = tool.execute(buildJsonObject { put("action", "banana") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("banana") || result.content.contains("Unknown"))
        }

        @Test
        fun `find_usages without url parameter returns error result`() = runTest {
            val result = tool.execute(buildJsonObject { put("action", "find_usages") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("url", ignoreCase = true))
        }

        @Test
        fun `export_http_scratch action is in enum`() {
            val actions = tool.parameters.properties["action"]?.enumValues ?: emptyList()
            assertTrue(actions.contains("export_http_scratch"),
                "action enum should include export_http_scratch; got $actions")
        }

        @Test
        fun `list_async action is in enum`() {
            val actions = tool.parameters.properties["action"]?.enumValues ?: emptyList()
            assertTrue(actions.contains("list_async"),
                "action enum should include list_async; got $actions")
        }

        @Test
        fun `service_graph action is in enum`() {
            val actions = tool.parameters.properties["action"]?.enumValues ?: emptyList()
            assertTrue(actions.contains("service_graph"),
                "action enum should include service_graph; got $actions")
        }

        @Test
        fun `unknown action error message lists all six actions`() = runTest {
            val result = tool.execute(buildJsonObject { put("action", "unknown_action") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("list_async"), "error message should mention list_async")
            assertTrue(result.content.contains("service_graph"), "error message should mention service_graph")
        }
    }

    @Nested
    inner class MicroservicesPluginApiContract {

        @Test
        fun `gRPC ProtoEndpointsProvider loads when bundled`() {
            try {
                Class.forName("com.intellij.grpc.endpoints.ProtoEndpointsProvider")
            } catch (_: ClassNotFoundException) {
                // Plugin absent from test classpath — skip gracefully
                return
            }
        }

        @Test
        fun `MQResolverManager class exists at jvm mq subpackage`() {
            try {
                Class.forName("com.intellij.microservices.jvm.mq.MQResolverManager")
            } catch (_: ClassNotFoundException) {
                // Not on classpath — skip gracefully
                return
            }
        }

        @Test
        fun `MQTypes exposes KAFKA_TOPIC_TYPE static field`() {
            val cls = try {
                Class.forName("com.intellij.microservices.jvm.mq.MQTypes")
            } catch (_: ClassNotFoundException) {
                // Not on classpath — skip gracefully
                return
            }
            // If the class loads, KAFKA_TOPIC_TYPE must be present
            cls.getField("KAFKA_TOPIC_TYPE")
        }

        @Test
        fun `MQResolverManager getAllVariants takes one MQType param`() {
            val cls = try {
                Class.forName("com.intellij.microservices.jvm.mq.MQResolverManager")
            } catch (_: ClassNotFoundException) { return }
            // getAllVariants takes a single MQType parameter (any subtype). We don't pin the
            // exact parameter type because MQType is an interface and the method may take
            // a narrower subtype; finding a method named "getAllVariants" with arity 1
            // is the practical drift check.
            val method = cls.methods.firstOrNull { it.name == "getAllVariants" && it.parameterCount == 1 }
            org.junit.jupiter.api.Assertions.assertNotNull(method,
                "MQResolverManager.getAllVariants(MQType) not found — Spring messaging API may have drifted")
        }

        @Test
        fun `MQTargetInfo exposes getDestination getAccessType resolveToPsiElement`() {
            val cls = try {
                Class.forName("com.intellij.microservices.jvm.mq.MQTargetInfo")
            } catch (_: ClassNotFoundException) { return }
            cls.getMethod("getDestination")
            cls.getMethod("getAccessType")
            cls.getMethod("resolveToPsiElement")
        }
    }
}
