package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [TaskProgress] — Cline's focus-chain task progress tracking.
 *
 * Covers:
 * - Markdown parsing (fromMarkdown) matching Cline's focus-chain-utils.ts
 * - Markdown rendering (toMarkdown) roundtrip
 * - Item mutation (withUpdatedItem, withNewItem)
 * - Count and completion metrics
 * - Edge cases (empty, whitespace, mixed content)
 */
class TaskProgressTest {

    @Nested
    inner class FromMarkdown {

        @Test
        fun `parses completed and incomplete items`() {
            val md = """
                - [x] Set up project structure
                - [x] Install dependencies
                - [ ] Create components
                - [ ] Test application
            """.trimIndent()

            val progress = TaskProgress.fromMarkdown(md)

            assertEquals(4, progress.totalCount)
            assertEquals(2, progress.completedCount)
            assertEquals("Set up project structure", progress.items[0].description)
            assertTrue(progress.items[0].completed)
            assertEquals("Install dependencies", progress.items[1].description)
            assertTrue(progress.items[1].completed)
            assertEquals("Create components", progress.items[2].description)
            assertFalse(progress.items[2].completed)
            assertEquals("Test application", progress.items[3].description)
            assertFalse(progress.items[3].completed)
        }

        @Test
        fun `handles uppercase X as completed`() {
            // Cline's isCompletedFocusChainItem checks both [x] and [X]
            val md = "- [X] Done item"
            val progress = TaskProgress.fromMarkdown(md)

            assertEquals(1, progress.totalCount)
            assertTrue(progress.items[0].completed)
            assertEquals("Done item", progress.items[0].description)
        }

        @Test
        fun `handles flexible spacing`() {
            // Cline's FOCUS_CHAIN_ITEM_REGEX: /^-\s*\[([ xX])\]\s*(.+)$/
            val md = """
                -  [ ]  Item with extra spaces
                - [x]Item without space after bracket
            """.trimIndent()

            val progress = TaskProgress.fromMarkdown(md)

            assertEquals(2, progress.totalCount)
            assertEquals("Item with extra spaces", progress.items[0].description)
            assertFalse(progress.items[0].completed)
            assertEquals("Item without space after bracket", progress.items[1].description)
            assertTrue(progress.items[1].completed)
        }

        @Test
        fun `ignores non-checklist lines`() {
            val md = """
                # Task Plan
                - [x] Step one
                Some description text
                - [ ] Step two

                Another paragraph
            """.trimIndent()

            val progress = TaskProgress.fromMarkdown(md)

            assertEquals(2, progress.totalCount)
            assertEquals("Step one", progress.items[0].description)
            assertEquals("Step two", progress.items[1].description)
        }

        @Test
        fun `returns empty progress for blank string`() {
            val progress = TaskProgress.fromMarkdown("")
            assertTrue(progress.isEmpty)
            assertEquals(0, progress.totalCount)
        }

        @Test
        fun `returns empty progress for whitespace-only string`() {
            val progress = TaskProgress.fromMarkdown("   \n  \n  ")
            assertTrue(progress.isEmpty)
        }

        @Test
        fun `returns empty progress for string with no checklist items`() {
            val progress = TaskProgress.fromMarkdown("Just some text\nwith no checkboxes")
            assertTrue(progress.isEmpty)
        }
    }

    @Nested
    inner class ToMarkdown {

        @Test
        fun `renders completed and incomplete items`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("Step one", completed = true),
                    TaskProgressItem("Step two", completed = false),
                    TaskProgressItem("Step three", completed = true)
                )
            )

            val md = progress.toMarkdown()

            assertEquals(
                "- [x] Step one\n- [ ] Step two\n- [x] Step three",
                md
            )
        }

        @Test
        fun `empty progress renders empty string`() {
            assertEquals("", TaskProgress().toMarkdown())
        }
    }

    @Nested
    inner class Roundtrip {

        @Test
        fun `fromMarkdown then toMarkdown preserves content`() {
            val original = """- [x] Set up project structure
- [x] Install dependencies
- [ ] Create components
- [ ] Test application"""

            val roundtripped = TaskProgress.fromMarkdown(original).toMarkdown()
            assertEquals(original, roundtripped)
        }

        @Test
        fun `toMarkdown then fromMarkdown preserves structure`() {
            val original = TaskProgress(
                listOf(
                    TaskProgressItem("Read config file", completed = true),
                    TaskProgressItem("Fix null check in processUser()", completed = false),
                    TaskProgressItem("Run tests to verify", completed = false)
                )
            )

            val roundtripped = TaskProgress.fromMarkdown(original.toMarkdown())
            assertEquals(original, roundtripped)
        }
    }

    @Nested
    inner class Mutation {

        @Test
        fun `withUpdatedItem marks item as completed`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("Step one", completed = false),
                    TaskProgressItem("Step two", completed = false)
                )
            )

            val updated = progress.withUpdatedItem(0, completed = true)

            assertTrue(updated.items[0].completed)
            assertFalse(updated.items[1].completed)
            // Original unchanged (immutable)
            assertFalse(progress.items[0].completed)
        }

        @Test
        fun `withUpdatedItem marks item as incomplete`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("Step one", completed = true),
                    TaskProgressItem("Step two", completed = true)
                )
            )

            val updated = progress.withUpdatedItem(1, completed = false)

            assertTrue(updated.items[0].completed)
            assertFalse(updated.items[1].completed)
        }

        @Test
        fun `withUpdatedItem returns same instance for out-of-bounds index`() {
            val progress = TaskProgress(
                listOf(TaskProgressItem("Step one", completed = false))
            )

            val updated = progress.withUpdatedItem(5, completed = true)
            assertSame(progress, updated)
        }

        @Test
        fun `withNewItem appends incomplete item`() {
            val progress = TaskProgress(
                listOf(TaskProgressItem("Step one", completed = true))
            )

            val updated = progress.withNewItem("Step two")

            assertEquals(2, updated.totalCount)
            assertEquals("Step two", updated.items[1].description)
            assertFalse(updated.items[1].completed)
        }

        @Test
        fun `withNewItem on empty progress creates single-item list`() {
            val updated = TaskProgress().withNewItem("First step")
            assertEquals(1, updated.totalCount)
            assertEquals("First step", updated.items[0].description)
        }
    }

    @Nested
    inner class Metrics {

        @Test
        fun `completedCount counts completed items`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("A", completed = true),
                    TaskProgressItem("B", completed = false),
                    TaskProgressItem("C", completed = true)
                )
            )
            assertEquals(2, progress.completedCount)
        }

        @Test
        fun `totalCount returns total number of items`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("A"),
                    TaskProgressItem("B"),
                    TaskProgressItem("C")
                )
            )
            assertEquals(3, progress.totalCount)
        }

        @Test
        fun `isComplete returns true when all items done`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("A", completed = true),
                    TaskProgressItem("B", completed = true)
                )
            )
            assertTrue(progress.isComplete)
        }

        @Test
        fun `isComplete returns false when items remain`() {
            val progress = TaskProgress(
                listOf(
                    TaskProgressItem("A", completed = true),
                    TaskProgressItem("B", completed = false)
                )
            )
            assertFalse(progress.isComplete)
        }

        @Test
        fun `isComplete returns false for empty list`() {
            assertFalse(TaskProgress().isComplete)
        }

        @Test
        fun `isEmpty returns true for empty list`() {
            assertTrue(TaskProgress().isEmpty)
        }

        @Test
        fun `isEmpty returns false for non-empty list`() {
            assertFalse(TaskProgress(listOf(TaskProgressItem("A"))).isEmpty)
        }
    }
}
