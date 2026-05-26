package com.workflow.orchestrator.agent.tools.apidocs

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ApiDocResourceInvariantTest {

    @TestFactory
    fun `every shipped family resource loads and satisfies invariants`(): List<DynamicTest> =
        ApiDocLoader.FAMILY_IDS.map { id ->
            DynamicTest.dynamicTest("family $id") {
                val result = ApiDocLoader.loadFamily(id)
                assertTrue(result.family != null, "Resource /api-docs/$id.json must load: ${result.error}")
                val fam = result.family!!
                assertTrue(fam.categories.isNotEmpty(), "$id has no categories")
                fam.categories.flatMap { it.endpoints }.forEach { ep ->
                    val where = "$id ${ep.method} ${ep.pathTemplate}"
                    assertTrue(ep.method.isNotBlank(), "$where: blank method")
                    assertTrue(ep.pathTemplate.isNotBlank(), "$where: blank path")
                    assertTrue(ep.provenance.isNotBlank(), "$where: missing provenance")
                    if (ep.status == ApiEndpointStatus.USED) {
                        assertTrue(!ep.callSite.isNullOrBlank(), "$where: USED endpoint needs a callSite")
                    }
                }
            }
        }
}
