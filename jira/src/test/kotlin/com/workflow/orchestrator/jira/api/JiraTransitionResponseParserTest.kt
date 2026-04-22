package com.workflow.orchestrator.jira.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.StatusCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JiraTransitionResponseParserTest {

    private val parser = JiraTransitionResponseParser(ObjectMapper())

    @Test
    fun `parses transition with required user field`() {
        val json = """
        {"transitions":[{
          "id":"31","name":"In Review",
          "to":{"id":"3","name":"In Review","statusCategory":{"key":"indeterminate"}},
          "fields":{
            "assignee":{
              "required":true,
              "schema":{"type":"user","system":"assignee"},
              "name":"Assignee",
              "autoCompleteUrl":"/rest/api/2/user/assignable/search?issueKey=ABC-1"
            }
          }
        }]}
        """.trimIndent()

        val result = parser.parse(json)
        val field = result.single().fields.single()

        assertEquals("assignee", field.id)
        assertEquals(true, field.required)
        assertEquals(FieldSchema.User(multi = false), field.schema)
        assertEquals(StatusCategory.IN_PROGRESS, result.single().toStatus.category)
        assertTrue(result.single().hasScreen)
    }

    @Test
    fun `parses labels as array-of-string with system=labels`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"labels":{"required":false,"schema":{"type":"array","items":"string","system":"labels"},"name":"Labels"}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.Labels, field.schema)
    }

    @Test
    fun `parses priority with allowedValues`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"priority":{"required":true,"schema":{"type":"priority"},"name":"Priority",
                    "allowedValues":[{"id":"1","name":"Blocker"},{"id":"2","name":"Critical"}]}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.Priority, field.schema)
        assertEquals(2, field.allowedValues.size)
        assertEquals("Blocker", field.allowedValues[0].value)
    }

    @Test
    fun `parses array of versions as multi version`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"fixVersions":{"required":true,"schema":{"type":"array","items":"version"},"name":"Fix Version"}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.Version(multi = true), field.schema)
    }

    @Test
    fun `parses string with allowedValues as SingleSelect AllowedValues`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"customfield_1":{"required":true,"schema":{"type":"string","custom":"...:select"},
           "name":"Env","allowedValues":[{"id":"10","value":"Prod"}]}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.SingleSelect(SelectSource.AllowedValues), field.schema)
    }

    @Test
    fun `parses string with autoCompleteUrl as SingleSelect AutoComplete`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"customfield_1":{"required":false,"schema":{"type":"string"},
           "name":"Env","autoCompleteUrl":"/rest/foo?query="}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.SingleSelect(SelectSource.AutoCompleteUrl), field.schema)
    }

    @Test
    fun `parses unknown schema type as Unknown`() {
        val json = """
        {"transitions":[{"id":"31","name":"Done","to":{"id":"5","name":"Done","statusCategory":{"key":"done"}},
         "fields":{"mystery":{"required":false,"schema":{"type":"tesseract"},"name":"Mystery"}}}]}
        """.trimIndent()
        val field = parser.parse(json).single().fields.single()
        assertEquals(FieldSchema.Unknown("tesseract"), field.schema)
    }

    @Test
    fun `transition without fields has empty field list and hasScreen=false`() {
        val json = """
        {"transitions":[{"id":"21","name":"Start Progress",
         "to":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}}]}
        """.trimIndent()
        val meta = parser.parse(json).single()
        assertEquals(false, meta.hasScreen)
        assertEquals(0, meta.fields.size)
    }
}
