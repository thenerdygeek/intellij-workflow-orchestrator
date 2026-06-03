// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryWriteClassifierTest {

    private val dir = "/home/u/.workflow-orchestrator/proj-abc123/agent/memory"

    private fun args(path: String) = """{"path":"$path","content":"x"}"""

    @Test
    fun `edit create delete under memory dir are memory writes`() {
        assertTrue(MemoryWriteClassifier.isMemoryWrite("edit_file", args("$dir/user_prefs.md"), dir))
        assertTrue(MemoryWriteClassifier.isMemoryWrite("create_file", args("$dir/project_x.md"), dir))
        assertTrue(MemoryWriteClassifier.isMemoryWrite("delete_file", args("$dir/feedback_y.md"), dir))
        assertTrue(MemoryWriteClassifier.isMemoryWrite("edit_file", args("$dir/MEMORY.md"), dir)) {
            "MEMORY.md index curation is a memory write"
        }
    }

    @Test
    fun `reads and non-write tools are not memory writes`() {
        assertFalse(MemoryWriteClassifier.isMemoryWrite("read_file", args("$dir/user_prefs.md"), dir))
        assertFalse(MemoryWriteClassifier.isMemoryWrite("run_command", args("$dir/user_prefs.md"), dir))
    }

    @Test
    fun `paths outside the memory dir are not memory writes`() {
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", args("/home/u/proj/src/Main.kt"), dir))
        // A sibling dir that merely shares a prefix must NOT match.
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", args("${dir}-other/x.md"), dir))
    }

    @Test
    fun `windows-style separators match`() {
        val winDir = "C:\\Users\\u\\.workflow-orchestrator\\proj\\agent\\memory"
        val winArgs = """{"path":"C:\\Users\\u\\.workflow-orchestrator\\proj\\agent\\memory\\user_a.md"}"""
        assertTrue(MemoryWriteClassifier.isMemoryWrite("edit_file", winArgs, winDir))
    }

    @Test
    fun `null or blank dir, missing path, or bad json are not memory writes`() {
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", args("$dir/x.md"), null))
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", args("$dir/x.md"), ""))
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", """{"content":"x"}""", dir))
        assertFalse(MemoryWriteClassifier.isMemoryWrite("edit_file", "not json", dir))
    }

    @Test
    fun `file_path is accepted as a path alias`() {
        assertTrue(MemoryWriteClassifier.isMemoryWrite("edit_file", """{"file_path":"$dir/u.md"}""", dir))
    }
}
