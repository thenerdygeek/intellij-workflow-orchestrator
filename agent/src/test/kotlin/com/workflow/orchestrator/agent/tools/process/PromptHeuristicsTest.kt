package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptHeuristicsTest {
    @Test
    fun `password prompt is classified`() {
        val r = PromptHeuristics.classify("Please enter your password: ")
        assertTrue(r is IdleClassification.LikelyPasswordPrompt, "expected LikelyPasswordPrompt, got $r")
    }

    @Test
    fun `passphrase prompt is classified`() {
        val r = PromptHeuristics.classify("Enter passphrase for key '/home/me/.ssh/id_rsa':")
        assertTrue(r is IdleClassification.LikelyPasswordPrompt, "expected LikelyPasswordPrompt, got $r")
    }

    @Test
    fun `npm create vite style prompt is stdin`() {
        val r = PromptHeuristics.classify("> create-vite@latest\n? Project name: › vite-project")
        assertTrue(r is IdleClassification.LikelyStdinPrompt, "expected LikelyStdinPrompt, got $r")
        assertTrue((r as IdleClassification.LikelyStdinPrompt).promptText.contains("Project name"))
    }

    @Test
    fun `y n confirm prompt is stdin`() {
        val r = PromptHeuristics.classify("Proceed with installation? [Y/n] ")
        assertTrue(r is IdleClassification.LikelyStdinPrompt, "expected LikelyStdinPrompt, got $r")
    }

    @Test
    fun `trailing greater-than prompt is stdin`() {
        val r = PromptHeuristics.classify("Welcome to the REPL\n> ")
        assertTrue(r is IdleClassification.LikelyStdinPrompt, "expected LikelyStdinPrompt, got $r")
    }

    @Test
    fun `trailing colon without newline is stdin`() {
        val r = PromptHeuristics.classify("Enter your name:")
        assertTrue(r is IdleClassification.LikelyStdinPrompt, "expected LikelyStdinPrompt, got $r")
    }

    @Test
    fun `stacktrace tail is generic idle not prompt`() {
        val r = PromptHeuristics.classify(
            "Exception in thread main java.lang.NullPointerException\n" +
            "\tat com.foo.Bar.doWork(Bar.java:42)\n"
        )
        assertTrue(r is IdleClassification.GenericIdle, "expected GenericIdle, got $r")
    }

    @Test
    fun `normal newline-terminated output is generic idle`() {
        val r = PromptHeuristics.classify("Running tests...\nDone.\n")
        assertTrue(r is IdleClassification.GenericIdle, "expected GenericIdle, got $r")
    }

    @Test
    fun `empty tail is generic idle`() {
        val r = PromptHeuristics.classify("")
        assertTrue(r is IdleClassification.GenericIdle, "expected GenericIdle, got $r")
    }
}
