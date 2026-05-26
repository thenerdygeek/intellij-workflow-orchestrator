package com.workflow.orchestrator.agent.tools.apidocs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiDocLoaderTest {

    @Test
    fun `loadFamily returns null and records error for missing resource`() {
        val result = ApiDocLoader.loadFamily("does-not-exist")
        assertEquals(null, result.family)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("does-not-exist"))
    }

    @Test
    fun `loadFamily deserializes a well-formed resource from a string`() {
        val json = """
            {"id":"demo","displayName":"Demo","authScheme":"Bearer x",
             "probedServerVersion":"v1","description":"d",
             "categories":[{"name":"Cat","endpoints":[
               {"method":"GET","pathTemplate":"/x","status":"USED",
                "summary":"s","provenance":"probe demo","callSite":"X.kt:1"}]}]}
        """.trimIndent()
        val family = ApiDocLoader.parse(json)
        assertEquals("demo", family.id)
        assertEquals(1, family.categories.size)
        assertEquals(ApiEndpointStatus.USED, family.categories[0].endpoints[0].status)
    }

    @Test
    fun `parse throws on malformed JSON (the contract loadFamily's catch relies on)`() {
        assertThrows(Exception::class.java) { ApiDocLoader.parse("{ not valid json }") }
    }
}
