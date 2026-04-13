package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AskQuestionsToolTest {

    private val tool = AskQuestionsTool()

    @AfterEach
    fun cleanup() {
        AskQuestionsTool.showSimpleQuestionCallback = null
        AskQuestionsTool.showQuestionsCallback = null
        AskQuestionsTool.pendingQuestions = null
    }

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

    // ── Bug 2: simple question with no options must still surface question text ──

    @Nested
    inner class SimpleQuestionNoOptions {

        @Test
        fun `simple question callback receives question text even with null options`() = runTest {
            var receivedQuestion: String? = null
            var receivedOptions: String? = null

            AskQuestionsTool.showSimpleQuestionCallback = { q, opts ->
                receivedQuestion = q
                receivedOptions = opts
            }

            val project = io.mockk.mockk<com.intellij.openapi.project.Project>(relaxed = true)

            // Launch tool execution in background — it will block on the deferred
            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "What database should we use?")
                }, project)
            }

            // Give the coroutine a chance to run and set up the callback
            kotlinx.coroutines.yield()

            // Verify the callback was invoked with the question text
            assertEquals("What database should we use?", receivedQuestion,
                "showSimpleQuestionCallback must receive the question text")
            assertNull(receivedOptions,
                "Options should be null when not provided")

            // The pending deferred should be set (tool is blocking)
            assertNotNull(AskQuestionsTool.pendingQuestions,
                "pendingQuestions should be set while waiting for answer")

            // Resolve to let the coroutine complete
            AskQuestionsTool.pendingQuestions?.complete("user answer")
            job.join()
        }

        @Test
        fun `simple question callback receives question text with empty options string`() = runTest {
            var receivedQuestion: String? = null
            var receivedOptions: String? = null

            AskQuestionsTool.showSimpleQuestionCallback = { q, opts ->
                receivedQuestion = q
                receivedOptions = opts
            }

            val project = io.mockk.mockk<com.intellij.openapi.project.Project>(relaxed = true)

            val job = launch {
                tool.execute(buildJsonObject {
                    put("question", "Which approach?")
                    put("options", "")
                }, project)
            }

            kotlinx.coroutines.yield()

            assertEquals("Which approach?", receivedQuestion)
            // Empty string options are normalized to null (no options = plain text mode)
            assertNull(receivedOptions)

            AskQuestionsTool.pendingQuestions?.complete("answer")
            job.join()
        }
    }
}
