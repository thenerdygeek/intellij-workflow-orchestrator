package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class OutputCollectorTest {

    // ── stripAnsi ───────────────────────────────────────────────────────

    @Nested
    inner class StripAnsi {
        @Test
        fun `removes color escape codes`() {
            val input = "\u001B[31mERROR\u001B[0m: something failed"
            assertEquals("ERROR: something failed", OutputCollector.stripAnsi(input))
        }

        @Test
        fun `removes bold and underline codes`() {
            val input = "\u001B[1mBold\u001B[0m \u001B[4mUnderline\u001B[0m"
            assertEquals("Bold Underline", OutputCollector.stripAnsi(input))
        }

        @Test
        fun `preserves plain text without escape codes`() {
            val input = "Hello, world! Line 2\nLine 3"
            assertEquals(input, OutputCollector.stripAnsi(input))
        }

        @Test
        fun `removes multi-param escape codes`() {
            // e.g. \e[38;5;196m (256-color)
            val input = "\u001B[38;5;196mRed text\u001B[0m"
            assertEquals("Red text", OutputCollector.stripAnsi(input))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", OutputCollector.stripAnsi(""))
        }

        @Test
        fun `handles string that is only escape codes`() {
            val input = "\u001B[31m\u001B[0m"
            assertEquals("", OutputCollector.stripAnsi(input))
        }
    }

    // ── sanitizeForLLM ──────────────────────────────────────────────────

    @Nested
    inner class SanitizeForLLM {
        @Test
        fun `removes zero-width space`() {
            val input = "hello\u200Bworld"
            assertEquals("helloworld", OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `removes zero-width non-joiner`() {
            val input = "hello\u200Cworld"
            assertEquals("helloworld", OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `removes zero-width joiner`() {
            val input = "hello\u200Dworld"
            assertEquals("helloworld", OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `removes RTL override characters`() {
            val input = "text\u202Amore\u202Eend"
            assertEquals("textmoreend", OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `removes BOM`() {
            val input = "\uFEFFcontent"
            assertEquals("content", OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `preserves normal unicode text`() {
            val greek = "αβγδε"
            assertEquals(greek, OutputCollector.sanitizeForLLM(greek))
        }

        @Test
        fun `preserves CJK characters`() {
            val japanese = "日本語テスト"
            assertEquals(japanese, OutputCollector.sanitizeForLLM(japanese))
        }

        @Test
        fun `preserves emojis`() {
            // Basic emojis are not in Cf category
            val input = "Hello world"
            assertEquals(input, OutputCollector.sanitizeForLLM(input))
        }

        @Test
        fun `handles empty string`() {
            assertEquals("", OutputCollector.sanitizeForLLM(""))
        }

        @Test
        fun `removes multiple format control characters`() {
            val input = "\u200B\u200C\u200D\u202A\u202E\uFEFFclean"
            assertEquals("clean", OutputCollector.sanitizeForLLM(input))
        }
    }

    // ── truncateByLines ─────────────────────────────────────────────────

    @Nested
    inner class TruncateByLines {
        @Test
        fun `content under limit returns as-is`() {
            val content = "line1\nline2\nline3"
            assertEquals(content, OutputCollector.truncateByLines(content, maxLines = 10))
        }

        @Test
        fun `content at exact limit returns as-is`() {
            val content = (1..10).joinToString("\n") { "line $it" }
            assertEquals(content, OutputCollector.truncateByLines(content, maxLines = 10))
        }

        @Test
        fun `content over limit does 50-50 split`() {
            val content = (1..20).joinToString("\n") { "line $it" }
            val result = OutputCollector.truncateByLines(content, maxLines = 10)

            // First 5 lines preserved (half of maxLines)
            assertTrue(result.contains("line 1"))
            assertTrue(result.contains("line 5"))
            // Last 5 lines preserved
            assertTrue(result.contains("line 16"))
            assertTrue(result.contains("line 20"))
            // Middle omitted notice
            assertTrue(result.contains("[... 10 lines omitted ...]"))
            // Middle lines should NOT be present
            assertFalse(result.contains("line 6\n"))
            assertFalse(result.contains("line 15\n"))
        }

        @Test
        fun `preserves first and last lines for large content`() {
            val content = (1..100).joinToString("\n") { "line $it" }
            val result = OutputCollector.truncateByLines(content, maxLines = 20)

            // First 10 lines
            assertTrue(result.contains("line 1"))
            assertTrue(result.contains("line 10"))
            // Last 10 lines
            assertTrue(result.contains("line 91"))
            assertTrue(result.contains("line 100"))
            // Omission notice
            assertTrue(result.contains("[... 80 lines omitted ...]"))
        }

        @Test
        fun `single line content is returned as-is`() {
            val content = "just a single line"
            assertEquals(content, OutputCollector.truncateByLines(content, maxLines = 5))
        }

        @Test
        fun `empty string returns empty`() {
            assertEquals("", OutputCollector.truncateByLines("", maxLines = 10))
        }

        @Test
        fun `odd maxLines splits correctly`() {
            val content = (1..20).joinToString("\n") { "line $it" }
            // maxLines = 11 -> head = 5, tail = 6 (integer division: 11/2=5 head, 11-5=6 tail)
            val result = OutputCollector.truncateByLines(content, maxLines = 11)

            assertTrue(result.contains("line 1"))
            assertTrue(result.contains("line 5"))
            assertTrue(result.contains("line 15"))
            assertTrue(result.contains("line 20"))
            assertTrue(result.contains("[... 9 lines omitted ...]"))
        }
    }

    // ── truncateToTail ──────────────────────────────────────────────────

    @Nested
    inner class TruncateToTail {
        @Test
        fun `short content returned as-is`() {
            val content = "line1\nline2\nline3"
            assertEquals(content, OutputCollector.truncateToTail(content, maxChars = 10_000))
        }

        @Test
        fun `keeps last lines and prepends head-omission marker`() {
            val lines = (1..1000).map { "line $it" }
            val content = lines.joinToString("\n")
            val result = OutputCollector.truncateToTail(content, maxChars = 5_000)

            assertTrue(result.startsWith("[... "))
            assertTrue(result.contains("lines omitted from head ..."))
            // Last line must be present
            assertTrue(result.contains("line 1000"))
            // A line from the head should not be present
            assertFalse(result.contains("line 1\n"))
        }

        @Test
        fun `last line always survives truncation`() {
            val content = (1..500).joinToString("\n") { "line $it" } + "\nBUILD FAILURE"
            val result = OutputCollector.truncateToTail(content, maxChars = 1_000)
            assertTrue(result.contains("BUILD FAILURE"), "Last line (BUILD FAILURE) must survive truncation")
        }

        @Test
        fun `single line larger than maxChars returns marker only`() {
            val hugeLine = "x".repeat(10_000)
            val result = OutputCollector.truncateToTail(hugeLine, maxChars = 500)
            assertTrue(result.startsWith("[... 1 lines omitted from head ...]"), "Expected marker: got: ${result.take(80)}")
            assertFalse(result.contains("x".repeat(10)))
        }

        @Test
        fun `omission count is accurate`() {
            // 100 lines of exactly 10 chars each (10 + 1 newline = 11 chars/line)
            // maxChars=55 → fits 5 lines (5*11=55), omits 95
            val content = (1..100).joinToString("\n") { "0123456789" }
            val result = OutputCollector.truncateToTail(content, maxChars = 55)
            assertTrue(result.contains("[... 95 lines omitted from head ...]"), "got: ${result.take(80)}")
        }
    }

    // ── processOutputTailBiased ─────────────────────────────────────────

    @Nested
    inner class ProcessOutputTailBiased {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `empty output returns no output message`() {
            val result = OutputCollector.processOutputTailBiased("")
            assertEquals("(No output)", result.content)
            assertFalse(result.wasTruncated)
        }

        @Test
        fun `short output returned unchanged`() {
            val output = "BUILD SUCCESSFUL in 2s"
            val result = OutputCollector.processOutputTailBiased(output)
            assertEquals(output, result.content)
            assertFalse(result.wasTruncated)
        }

        @Test
        fun `strips ANSI codes`() {
            val input = "\u001B[31mBUILD FAILURE\u001B[0m"
            val result = OutputCollector.processOutputTailBiased(input)
            assertEquals("BUILD FAILURE", result.content)
        }

        @Test
        fun `large output keeps tail and uses head-omission marker`() {
            val lines = (1..1000).map { "output line $it" }
            val content = lines.joinToString("\n")
            val result = OutputCollector.processOutputTailBiased(content, maxResultChars = 5_000)

            assertTrue(result.wasTruncated)
            assertTrue(result.content.contains("lines omitted from head ..."))
            // Last line must survive
            assertTrue(result.content.contains("output line 1000"))
            // First line must NOT be in the result body (only in the marker count)
            assertFalse(result.content.contains("output line 1\n"))
        }

        @Test
        fun `exit code line survives when appended before call`() {
            // RunCommandTool prepends "Exit code: N\n" AFTER processOutputTailBiased —
            // this test verifies the last meaningful output line survives truncation.
            val failureLine = "BUILD FAILURE"
            val content = (1..500).joinToString("\n") { "test output $it" } + "\n$failureLine"
            val result = OutputCollector.processOutputTailBiased(content, maxResultChars = 2_000)

            assertTrue(result.wasTruncated)
            assertTrue(result.content.contains(failureLine))
        }

        @Test
        fun `spill path set when over maxMemoryChars`() {
            val line = "y".repeat(200)
            val content = (1..6_000).joinToString("\n") { line }
            val result = OutputCollector.processOutputTailBiased(
                rawOutput = content,
                maxResultChars = 5_000,
                maxMemoryChars = 100_000,
                spillDir = tempDir.toFile(),
                toolCallId = "tail-spill-1"
            )

            assertNotNull(result.spillPath)
            assertTrue(result.content.contains("Full output:"))
            assertTrue(File(result.spillPath!!).exists())
        }

        @Test
        fun `no spill when under maxMemoryChars`() {
            val line = "z".repeat(100)
            val content = (1..100).joinToString("\n") { line }
            val result = OutputCollector.processOutputTailBiased(
                rawOutput = content,
                maxResultChars = 500,
                maxMemoryChars = 1_000_000,
                spillDir = tempDir.toFile(),
                toolCallId = "no-spill"
            )

            assertTrue(result.wasTruncated)
            assertNull(result.spillPath)
        }

        @Test
        fun `totalChars and totalLines reflect sanitized input`() {
            val content = "line1\nline2\nline3"
            val result = OutputCollector.processOutputTailBiased(content)
            assertEquals(3, result.totalLines)
            assertEquals(content.length, result.totalChars)
        }
    }

    // ── spillToFile ─────────────────────────────────────────────────────

    @Nested
    inner class SpillToFile {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `writes content to file and returns path`() {
            val content = "full command output here"
            val path = OutputCollector.spillToFile(content, tempDir.toFile(), "call-123")

            assertNotNull(path)
            val file = File(path!!)
            assertTrue(file.exists())
            assertEquals(content, file.readText())
        }

        @Test
        fun `file name contains tool call id`() {
            val path = OutputCollector.spillToFile("content", tempDir.toFile(), "abc-456")

            assertNotNull(path)
            assertTrue(path!!.contains("abc-456"))
        }

        @Test
        fun `file name starts with run-cmd prefix`() {
            val path = OutputCollector.spillToFile("content", tempDir.toFile(), "test-id")

            assertNotNull(path)
            assertTrue(File(path!!).name.startsWith("run-cmd-"))
        }

        @Test
        fun `creates directory if it does not exist`() {
            val subDir = tempDir.resolve("nested/deep/dir").toFile()
            val path = OutputCollector.spillToFile("data", subDir, "id-1")

            assertNotNull(path)
            assertTrue(File(path!!).exists())
        }

        @Test
        fun `returns absolute path`() {
            val path = OutputCollector.spillToFile("data", tempDir.toFile(), "id-2")

            assertNotNull(path)
            assertTrue(File(path!!).isAbsolute)
        }
    }

    // ── buildContent ────────────────────────────────────────────────────

    @Nested
    inner class BuildContent {
        @Test
        fun `empty string produces no output message`() {
            val result = OutputCollector.buildContent("", maxResultChars = 30_000)
            assertEquals("(No output)", result.content)
            assertFalse(result.wasTruncated)
            assertEquals(0, result.totalLines)
            assertEquals(0, result.totalChars)
        }

        @Test
        fun `blank string produces no output message`() {
            val result = OutputCollector.buildContent("   \n  \n  ", maxResultChars = 30_000)
            assertEquals("(No output)", result.content)
            assertFalse(result.wasTruncated)
        }

        @Test
        fun `short output returned as-is`() {
            val output = "BUILD SUCCESSFUL in 2s"
            val result = OutputCollector.buildContent(output, maxResultChars = 30_000)
            assertEquals(output, result.content)
            assertFalse(result.wasTruncated)
            assertEquals(1, result.totalLines)
            assertEquals(output.length, result.totalChars)
        }

        @Test
        fun `output exceeding limit is truncated`() {
            val line = "x".repeat(100)
            val content = (1..500).joinToString("\n") { line }
            val result = OutputCollector.buildContent(content, maxResultChars = 5_000)

            assertTrue(result.wasTruncated)
            assertTrue(result.content.contains("[... "))
            assertTrue(result.content.contains("lines omitted ..."))
            assertTrue(result.content.contains("[Total output:"))
            assertTrue(result.content.length <= content.length)
        }
    }

    // ── processOutput ───────────────────────────────────────────────────

    @Nested
    inner class ProcessOutput {
        @TempDir
        lateinit var tempDir: Path

        @Test
        fun `empty output returns no output message`() {
            val result = OutputCollector.processOutput("")
            assertEquals("(No output)", result.content)
            assertFalse(result.wasTruncated)
            assertNull(result.spillPath)
        }

        @Test
        fun `strips ANSI codes from output`() {
            val input = "\u001B[32mSUCCESS\u001B[0m: done"
            val result = OutputCollector.processOutput(input)
            assertEquals("SUCCESS: done", result.content)
            assertFalse(result.wasTruncated)
        }

        @Test
        fun `sanitizes unicode control characters`() {
            val input = "result\u200B: \u202Avalue\u202E"
            val result = OutputCollector.processOutput(input)
            assertEquals("result: value", result.content)
        }

        @Test
        fun `short output not truncated and no spill`() {
            val output = "Hello world\nLine 2"
            val result = OutputCollector.processOutput(output)
            assertEquals(output, result.content)
            assertFalse(result.wasTruncated)
            assertNull(result.spillPath)
            assertEquals(2, result.totalLines)
        }

        @Test
        fun `large output triggers truncation with advice message`() {
            val line = "x".repeat(200)
            val content = (1..300).joinToString("\n") { line }
            val result = OutputCollector.processOutput(content, maxResultChars = 5_000)

            assertTrue(result.wasTruncated)
            assertTrue(result.content.contains("Use a more targeted command"))
            assertNull(result.spillPath)
        }

        @Test
        fun `large output with spillDir triggers spill and includes path`() {
            val line = "y".repeat(200)
            val content = (1..6_000).joinToString("\n") { line }
            val result = OutputCollector.processOutput(
                content,
                maxResultChars = 5_000,
                maxMemoryChars = 100_000,
                spillDir = tempDir.toFile(),
                toolCallId = "spill-test-1"
            )

            assertTrue(result.wasTruncated)
            assertNotNull(result.spillPath)
            assertTrue(result.content.contains("Full output:"))
            assertTrue(File(result.spillPath!!).exists())
        }

        @Test
        fun `spill only when over maxMemoryChars`() {
            // Content under maxMemoryChars but over maxResultChars
            val line = "z".repeat(100)
            val content = (1..100).joinToString("\n") { line }
            val result = OutputCollector.processOutput(
                content,
                maxResultChars = 500,
                maxMemoryChars = 1_000_000,
                spillDir = tempDir.toFile(),
                toolCallId = "no-spill"
            )

            assertTrue(result.wasTruncated)
            assertNull(result.spillPath) // Under maxMemoryChars, no spill
        }

        @Test
        fun `processOutput without spillDir does not spill even if large`() {
            val line = "a".repeat(200)
            val content = (1..1000).joinToString("\n") { line }
            val result = OutputCollector.processOutput(
                content,
                maxResultChars = 5_000,
                maxMemoryChars = 100,
                spillDir = null,
                toolCallId = "no-dir"
            )

            assertTrue(result.wasTruncated)
            assertNull(result.spillPath)
        }

        @Test
        fun `total chars and lines are accurate for raw input`() {
            val content = "line1\nline2\nline3"
            val result = OutputCollector.processOutput(content)
            assertEquals(3, result.totalLines)
            assertEquals(content.length, result.totalChars)
        }

        @Test
        fun `pipeline applies strip then sanitize then truncate`() {
            // Input with ANSI + zero-width + enough lines to truncate
            val lines = (1..20).joinToString("\n") { i ->
                "\u001B[31m\u200Bline $i\u001B[0m"
            }
            val result = OutputCollector.processOutput(lines, maxResultChars = 100)

            // ANSI stripped
            assertFalse(result.content.contains("\u001B"))
            // Zero-width stripped
            assertFalse(result.content.contains("\u200B"))
            // Truncated
            assertTrue(result.wasTruncated)
            assertTrue(result.content.contains("line 1"))
            assertTrue(result.content.contains("line 20"))
        }
    }
}
