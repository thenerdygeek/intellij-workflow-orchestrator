package com.workflow.orchestrator.agent.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandShapeTest {

    // ── splitSubCommands ──
    @Test fun `splits on operators and newlines`() {
        assertEquals(listOf("git status", "rm -rf x"), CommandShape.splitSubCommands("git status && rm -rf x"))
        assertEquals(listOf("git status", "rm -rf x"), CommandShape.splitSubCommands("git status\nrm -rf x"))
        assertEquals(listOf("cat a", "grep b"), CommandShape.splitSubCommands("cat a | grep b"))
        assertEquals(listOf("ls"), CommandShape.splitSubCommands("ls"))
    }

    // ── isAutoApprovable: allowed shapes ──
    @Test fun `simple commands and pipes and chains are approvable`() {
        assertTrue(CommandShape.isAutoApprovable("ls -la"))
        assertTrue(CommandShape.isAutoApprovable("git status"))
        assertTrue(CommandShape.isAutoApprovable("git add . && git status"))
        assertTrue(CommandShape.isAutoApprovable("cat a | grep b"))
        assertTrue(CommandShape.isAutoApprovable("python script.py"))
    }

    // ── isAutoApprovable: rejected shapes ──
    @Test fun `redirection subshell expansion wrapper assignment inline-eval background are rejected`() {
        assertFalse(CommandShape.isAutoApprovable("git status > out.txt"))
        assertFalse(CommandShape.isAutoApprovable("git status >> out.txt"))
        assertFalse(CommandShape.isAutoApprovable("sort < in.txt"))
        assertFalse(CommandShape.isAutoApprovable("ls 2> err.txt"))
        assertFalse(CommandShape.isAutoApprovable("echo \$(rm -rf /)"))
        assertFalse(CommandShape.isAutoApprovable("echo `whoami`"))
        assertFalse(CommandShape.isAutoApprovable("echo \$HOME"))
        assertFalse(CommandShape.isAutoApprovable("FOO=bar git push"))
        assertFalse(CommandShape.isAutoApprovable("timeout 60 git push"))
        assertFalse(CommandShape.isAutoApprovable("env git push"))
        assertFalse(CommandShape.isAutoApprovable("python -c \"import os\""))
        assertFalse(CommandShape.isAutoApprovable("node -e \"x\""))
        assertFalse(CommandShape.isAutoApprovable("bash -c \"x\""))
        assertFalse(CommandShape.isAutoApprovable("sleep 10 & rm x"))
        assertFalse(CommandShape.isAutoApprovable("echo x | sh"))
        assertFalse(CommandShape.isAutoApprovable(""))
    }

    // ── derivePrefix ──
    @Test fun `derivePrefix uses verb for multi-verb tools and bare name otherwise`() {
        assertEquals("git add", CommandShape.derivePrefix("git add Foo.kt"))
        assertEquals("npm install", CommandShape.derivePrefix("npm install left-pad"))
        assertEquals("docker build", CommandShape.derivePrefix("docker build ."))
        assertEquals("ls", CommandShape.derivePrefix("ls -la"))
        assertEquals("./gradlew", CommandShape.derivePrefix("./gradlew"))
        assertEquals("git", CommandShape.derivePrefix("git -C /tmp")) // 2nd token is a flag → bare
    }

    @Test fun `derivePrefix returns null for compound wrapper assignment inline-eval`() {
        assertNull(CommandShape.derivePrefix("git add . && git status"))
        assertNull(CommandShape.derivePrefix("timeout 60 git push"))
        assertNull(CommandShape.derivePrefix("FOO=bar git push"))
        assertNull(CommandShape.derivePrefix("python -c \"x\""))
        assertNull(CommandShape.derivePrefix("git status > out"))
    }

    @Test fun `derivePrefix uses verb for multi-verb tool with subcommand`() {
        assertEquals("./gradlew clean", CommandShape.derivePrefix("./gradlew clean build"))
    }

    @Test fun `path-prefixed interpreters and structural patterns are rejected`() {
        // Fix 1: path-prefixed shell and code interpreters must be rejected
        assertFalse(CommandShape.isAutoApprovable("echo x | /bin/bash"))
        assertFalse(CommandShape.isAutoApprovable("/usr/bin/python -c \"x\""))
        // Lock-in: structural patterns already correct but now explicitly tested
        assertFalse(CommandShape.isAutoApprovable("git status &> out.txt"))
        assertFalse(CommandShape.isAutoApprovable("bash <<< \"cmd\""))
        assertFalse(CommandShape.isAutoApprovable("sort <(cat f1)"))
        assertFalse(CommandShape.isAutoApprovable("tee >(wc -l)"))
    }

    // ── coveringPrefixes ──
    @Test fun `coveringPrefixes token-boundary matches and requires all sub-commands`() {
        val allow = setOf("git add", "git status")
        assertEquals(listOf("git add"), CommandShape.coveringPrefixes("git add Foo.kt", allow))
        assertEquals(listOf("git add", "git status"), CommandShape.coveringPrefixes("git add . && git status", allow))
        assertNull(CommandShape.coveringPrefixes("git addendum", allow)) // token boundary
        assertNull(CommandShape.coveringPrefixes("git add . && rm -rf x", allow)) // one sub uncovered
        assertNull(CommandShape.coveringPrefixes("git add x > out", allow)) // not simple
        assertNull(CommandShape.coveringPrefixes("git status & rm -rf x", allow)) // lone & → not simple
        assertNull(CommandShape.coveringPrefixes("ls", emptySet())) // empty allowlist
    }

    @Test fun `coveringPrefixes lowercases the live command but assumes prefixes normalized`() {
        assertEquals(listOf("git add"), CommandShape.coveringPrefixes("GIT ADD Foo.kt", setOf("git add")))
    }
}
