package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MemoryIndexTest {

    @Test
    fun `load returns null when MEMORY_md is missing`(@TempDir tmp: Path) {
        val result = MemoryIndex.load(tmp)
        assertNull(result)
    }

    @Test
    fun `load returns null when memory dir does not exist`(@TempDir tmp: Path) {
        val nonexistent = tmp.resolve("does-not-exist")
        val result = MemoryIndex.load(nonexistent)
        assertNull(result)
    }

    @Test
    fun `load returns file content when MEMORY_md exists`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Index\n- [A](a.md) — hook")
        val result = MemoryIndex.load(tmp)
        assertEquals("# Index\n- [A](a.md) — hook", result)
    }

    @Test
    fun `load truncates content past line 200 keeping the LAST 200 lines with marker at top`(@TempDir tmp: Path) {
        val lines = (1..300).map { "- [Entry $it](e$it.md) — hook" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        val resultLines = result.lines()

        // 1 marker at top + last 200 lines (entries 101..300)
        assertEquals(201, resultLines.size)
        assertTrue(resultLines[0].startsWith("<!-- MEMORY.md truncated"))
        assertEquals("- [Entry 101](e101.md) — hook", resultLines[1])
        assertEquals("- [Entry 300](e300.md) — hook", resultLines[200])
    }

    @Test
    fun `load returns content unchanged when at the 200 line cap`(@TempDir tmp: Path) {
        val lines = (1..200).map { "line $it" }
        Files.writeString(tmp.resolve("MEMORY.md"), lines.joinToString("\n"))

        val result = MemoryIndex.load(tmp)!!
        assertEquals(200, result.lines().size)
    }

    @Test
    fun `seedIfMissing creates a stub MEMORY_md when missing`(@TempDir tmp: Path) {
        val file = tmp.resolve("MEMORY.md")
        assertTrue(!Files.exists(file))

        MemoryIndex.seedIfMissing(tmp)

        assertTrue(Files.exists(file))
        assertEquals("# Memory Index\n", Files.readString(file))
        // After seeding, load() must return non-null so the prompt's Section 10b fires.
        assertEquals("# Memory Index\n", MemoryIndex.load(tmp))
    }

    @Test
    fun `seedIfMissing does not overwrite existing MEMORY_md`(@TempDir tmp: Path) {
        val existing = "# Memory Index\n- [Existing](e.md) — hook"
        Files.writeString(tmp.resolve("MEMORY.md"), existing)

        MemoryIndex.seedIfMissing(tmp)

        assertEquals(existing, Files.readString(tmp.resolve("MEMORY.md")))
    }

    @Test
    fun `onMemoryFileCreated appends entry under correct section header`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), """
            # Memory Index

            ## Feedback
            - [Existing](feedback_existing.md) — keep me
        """.trimIndent())
        val newFile = tmp.resolve("feedback_new.md")
        Files.writeString(newFile, """
            ---
            name: new-thing
            description: New thing description.
            type: feedback
            ---
            body
        """.trimIndent())

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("- [new-thing](feedback_new.md) — New thing description."))
        assertTrue(updated.contains("- [Existing](feedback_existing.md) — keep me"))
    }

    @Test
    fun `onMemoryFileCreated creates missing section header at end of file`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n")
        val newFile = tmp.resolve("project_alpha.md")
        Files.writeString(newFile, """
            ---
            name: alpha
            description: Alpha description.
            type: project
            ---
        """.trimIndent())

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("## Project"))
        assertTrue(updated.contains("- [alpha](project_alpha.md) — Alpha description."))
    }

    @Test
    fun `onMemoryFileCreated is idempotent — duplicate calls append once`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n\n## Feedback\n")
        val newFile = tmp.resolve("feedback_x.md")
        Files.writeString(newFile, "---\nname: x\ndescription: d.\ntype: feedback\n---\n")

        MemoryIndex.onMemoryFileCreated(tmp, newFile)
        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val occurrences = Files.readString(tmp.resolve("MEMORY.md"))
            .lines().count { it.contains("(feedback_x.md)") }
        assertEquals(1, occurrences)
    }

    @Test
    fun `onMemoryFileCreated falls back to filename slug when name is missing`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n")
        val newFile = tmp.resolve("project_noname.md")
        Files.writeString(newFile, "---\ndescription: only desc.\ntype: project\n---\n")

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("- [project_noname](project_noname.md) — only desc."))
    }

    @Test
    fun `onMemoryFileCreated falls back to type-from-prefix when type frontmatter is absent`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n")
        val newFile = tmp.resolve("user_role.md")
        Files.writeString(newFile, "---\nname: role\ndescription: a role.\n---\n")

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("## User"))
        assertTrue(updated.contains("- [role](user_role.md) — a role."))
    }

    @Test
    fun `onMemoryFileCreated defaults type to Reference when prefix is unrecognized`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n")
        val newFile = tmp.resolve("foobar.md")
        Files.writeString(newFile, "---\nname: thing\ndescription: a thing.\n---\n")

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("## Reference"))
    }

    @Test
    fun `onMemoryFileCreated is no-op when target is MEMORY_md itself`(@TempDir tmp: Path) {
        val seed = "# Memory Index\n"
        Files.writeString(tmp.resolve("MEMORY.md"), seed)

        MemoryIndex.onMemoryFileCreated(tmp, tmp.resolve("MEMORY.md"))

        assertEquals(seed, Files.readString(tmp.resolve("MEMORY.md")))
    }

    @Test
    fun `onMemoryFileDeleted removes the matching line and only that line`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), """
            # Memory Index

            ## Feedback
            - [A](feedback_a.md) — keep
            - [B](feedback_b.md) — remove me
            - [C](feedback_c.md) — keep
        """.trimIndent())

        MemoryIndex.onMemoryFileDeleted(tmp, "feedback_b.md")

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("(feedback_a.md)"))
        assertTrue(!updated.contains("(feedback_b.md)"))
        assertTrue(updated.contains("(feedback_c.md)"))
        assertTrue(updated.contains("## Feedback"))
    }

    @Test
    fun `onMemoryFileDeleted is no-op when no matching line exists`(@TempDir tmp: Path) {
        val original = "# Memory Index\n\n## Feedback\n- [Other](feedback_other.md) — hook"
        Files.writeString(tmp.resolve("MEMORY.md"), original)

        MemoryIndex.onMemoryFileDeleted(tmp, "feedback_missing.md")

        assertEquals(original, Files.readString(tmp.resolve("MEMORY.md")))
    }

    @Test
    fun `onMemoryFileDeleted is no-op when MEMORY_md does not exist`(@TempDir tmp: Path) {
        // Just verify no exception is thrown.
        MemoryIndex.onMemoryFileDeleted(tmp, "feedback_x.md")
        assertTrue(!Files.exists(tmp.resolve("MEMORY.md")))
    }

    @Test
    fun `onMemoryFileCreated seeds MEMORY_md when missing`(@TempDir tmp: Path) {
        val newFile = tmp.resolve("project_alpha.md")
        Files.writeString(newFile, "---\nname: alpha\ndescription: d.\ntype: project\n---\n")

        MemoryIndex.onMemoryFileCreated(tmp, newFile)

        assertTrue(Files.exists(tmp.resolve("MEMORY.md")))
        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        assertTrue(updated.contains("- [alpha](project_alpha.md) — d."))
    }

    @Test
    fun `concurrent onMemoryFileCreated calls on different files do not interleave`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("MEMORY.md"), "# Memory Index\n")
        val n = 12
        val files = (1..n).map { i ->
            val f = tmp.resolve("project_p$i.md")
            Files.writeString(f, "---\nname: p$i\ndescription: d$i.\ntype: project\n---\n")
            f
        }

        val threads = files.map { f ->
            Thread {
                MemoryIndex.onMemoryFileCreated(tmp, f)
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        val updated = Files.readString(tmp.resolve("MEMORY.md"))
        for (i in 1..n) {
            assertTrue(updated.contains("- [p$i](project_p$i.md) — d$i."),
                "expected entry for p$i to survive concurrent writes, got:\n$updated")
        }
    }
}
