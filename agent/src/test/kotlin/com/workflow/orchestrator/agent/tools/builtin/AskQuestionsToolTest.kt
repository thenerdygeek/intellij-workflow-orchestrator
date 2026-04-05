package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AskQuestionsToolTest {

    private val tool = AskQuestionsTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("ask_followup_question", tool.name)
        assertTrue(tool.parameters.properties.containsKey("question"))
        assertTrue(tool.parameters.properties.containsKey("questions"))
        assertEquals(
            setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
        assertTrue(tool.description.isNotBlank())
    }

    @Test
    fun `returns error for empty questions array`() {
        val result = tool.validateQuestions("[]")
        assertNotNull(result)
        assertTrue(result!!.isError)
    }

    @Test
    fun `returns error for question with no options`() {
        val questionsJson = """[{"id":"q1","question":"Pick one","type":"single","options":[]}]"""
        val result = tool.validateQuestions(questionsJson)
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("options"))
    }

    @Test
    fun `returns error for duplicate question IDs`() {
        val questionsJson = """[
            {"id":"q1","question":"First","type":"single","options":[{"id":"o1","label":"A"}]},
            {"id":"q1","question":"Second","type":"single","options":[{"id":"o2","label":"B"}]}
        ]"""
        val result = tool.validateQuestions(questionsJson)
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("Duplicate question ID"))
    }

    @Test
    fun `returns error for more than 20 questions`() {
        val questions = (1..21).map {
            """{"id":"q$it","question":"Q$it","type":"single","options":[{"id":"o$it","label":"Opt$it"}]}"""
        }.joinToString(",")
        val result = tool.validateQuestions("[$questions]")
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("20"))
    }

    @Test
    fun `returns error for invalid question type`() {
        val questionsJson = """[{"id":"q1","question":"Pick","type":"radio","options":[{"id":"o1","label":"A"}]}]"""
        val result = tool.validateQuestions(questionsJson)
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("invalid type"))
    }

    @Test
    fun `returns error for duplicate option IDs within question`() {
        val questionsJson = """[{"id":"q1","question":"Pick","type":"single","options":[
            {"id":"o1","label":"A"},
            {"id":"o1","label":"B"}
        ]}]"""
        val result = tool.validateQuestions(questionsJson)
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("Duplicate option ID"))
    }

    @Test
    fun `returns error for invalid JSON`() {
        val result = tool.validateQuestions("not valid json at all")
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("invalid"))
    }

    @Test
    fun `returns error for more than 10 options`() {
        val options = (1..11).map { """{"id":"o$it","label":"Option $it"}""" }.joinToString(",")
        val questionsJson = """[{"id":"q1","question":"Pick","type":"single","options":[$options]}]"""
        val result = tool.validateQuestions(questionsJson)
        assertNotNull(result)
        assertTrue(result!!.isError)
        assertTrue(result.content.contains("too many options"))
    }

    @Test
    fun `validation passes for valid questions`() {
        val questionsJson = """[
            {"id":"q1","question":"Framework?","type":"single","options":[
                {"id":"o1","label":"Spring Boot","description":"Java framework"},
                {"id":"o2","label":"Micronaut"}
            ]},
            {"id":"q2","question":"Features?","type":"multiple","options":[
                {"id":"o3","label":"Security"},
                {"id":"o4","label":"Caching"}
            ]}
        ]"""
        val result = tool.validateQuestions(questionsJson)
        assertNull(result)
    }
}
