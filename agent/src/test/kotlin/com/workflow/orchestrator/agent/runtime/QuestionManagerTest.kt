package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class QuestionManagerTest {

    private lateinit var manager: QuestionManager

    private val sampleQuestions = QuestionSet(
        title = "Setup Options",
        questions = listOf(
            Question(
                id = "q1",
                question = "Choose a framework",
                type = "single",
                options = listOf(
                    QuestionOption(id = "o1", label = "Spring Boot", description = "Java framework"),
                    QuestionOption(id = "o2", label = "Micronaut", description = "Alternative framework")
                )
            ),
            Question(
                id = "q2",
                question = "Select features",
                type = "multiple",
                options = listOf(
                    QuestionOption(id = "o3", label = "Security"),
                    QuestionOption(id = "o4", label = "Caching")
                )
            ),
            Question(
                id = "q3",
                question = "Pick a database",
                type = "single",
                options = listOf(
                    QuestionOption(id = "o5", label = "PostgreSQL"),
                    QuestionOption(id = "o6", label = "MySQL")
                )
            )
        )
    )

    @BeforeEach
    fun setUp() {
        manager = QuestionManager()
    }

    @Test
    fun `submitQuestions returns future and fires callback`() {
        var callbackFired = false
        var receivedQuestions: QuestionSet? = null
        manager.onQuestionsCreated = {
            callbackFired = true
            receivedQuestions = it
        }

        val future = manager.submitQuestions(sampleQuestions)

        assertNotNull(future)
        assertFalse(future.isDone)
        assertTrue(callbackFired)
        assertEquals(sampleQuestions, receivedQuestions)
        assertEquals(sampleQuestions, manager.currentQuestions)
    }

    @Test
    fun `answerQuestion stores answer`() {
        manager.submitQuestions(sampleQuestions)

        manager.answerQuestion("q1", listOf("o1"))

        val result = manager.buildResult()
        assertTrue(result.answers.containsKey("q1"))
        assertEquals(listOf("o1"), result.answers["q1"]!!.selectedOptions)
    }

    @Test
    fun `skipQuestion adds to skipped`() {
        manager.submitQuestions(sampleQuestions)

        manager.answerQuestion("q1", listOf("o1"))
        manager.skipQuestion("q1")

        val result = manager.buildResult()
        assertFalse(result.answers.containsKey("q1"))
        assertTrue(result.skipped.contains("q1"))
    }

    @Test
    fun `setChatMessage stores note with option label`() {
        var summaryCalled = false
        manager.onShowSummary = { summaryCalled = true }
        manager.submitQuestions(sampleQuestions)

        manager.answerQuestion("q1", listOf("o1"))
        manager.setChatMessage("q1", "Spring Boot", "I prefer this for microservices")

        val result = manager.buildResult()
        val answer = result.answers["q1"]!!
        assertEquals("Re: Spring Boot — I prefer this for microservices", answer.chatMessage)
        assertEquals(listOf("o1"), answer.selectedOptions)
        assertTrue(summaryCalled)
    }

    @Test
    fun `setChatMessage creates answer if none exists`() {
        manager.submitQuestions(sampleQuestions)

        manager.setChatMessage("q2", "Security", "Must have this")

        val result = manager.buildResult()
        val answer = result.answers["q2"]!!
        assertEquals(emptyList<String>(), answer.selectedOptions)
        assertEquals("Re: Security — Must have this", answer.chatMessage)
    }

    @Test
    fun `submitAnswers completes future`() {
        var fired = false
        manager.onSubmitted = { fired = true }
        val future = manager.submitQuestions(sampleQuestions)
        manager.answerQuestion("q1", listOf("o1"))

        manager.submitAnswers()

        assertTrue(future.isDone)
        assertFalse(future.get().cancelled)
        assertTrue(future.get().answers.containsKey("q1"))
        assertTrue(fired)
    }

    @Test
    fun `cancelQuestions completes future with cancelled`() {
        var fired = false
        manager.onSubmitted = { fired = true }
        val future = manager.submitQuestions(sampleQuestions)

        manager.cancelQuestions()

        assertTrue(future.isDone)
        assertTrue(future.get().cancelled)
        assertTrue(fired)
    }

    @Test
    fun `editQuestion fires onShowQuestion callback`() {
        manager.submitQuestions(sampleQuestions)
        var shownIndex: Int? = null
        manager.onShowQuestion = { shownIndex = it }

        manager.editQuestion("q2")

        assertEquals(1, shownIndex)
    }

    @Test
    fun `clear resets all state`() {
        manager.submitQuestions(sampleQuestions)
        manager.answerQuestion("q1", listOf("o1"))
        manager.skipQuestion("q2")

        manager.clear()

        assertNull(manager.currentQuestions)
        // After clear, buildResult should have no answers and no skipped
        // (no currentQuestions means no unanswered questions either)
        val result = manager.buildResult()
        assertTrue(result.answers.isEmpty())
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `buildResult marks unanswered questions as skipped`() {
        manager.submitQuestions(sampleQuestions)

        manager.answerQuestion("q1", listOf("o1"))
        // q2 and q3 are not answered and not explicitly skipped

        val result = manager.buildResult()
        assertEquals(1, result.answers.size)
        assertTrue(result.answers.containsKey("q1"))
        // q2 and q3 should both be in skipped
        assertTrue(result.skipped.contains("q2"))
        assertTrue(result.skipped.contains("q3"))
        assertEquals(2, result.skipped.size)
    }
}
