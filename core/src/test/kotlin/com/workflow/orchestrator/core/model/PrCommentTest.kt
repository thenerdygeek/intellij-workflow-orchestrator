package com.workflow.orchestrator.core.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PrCommentTest {
    @Test
    fun `constructs general comment without anchor`() {
        val c = PrComment(
            id = "1", version = 0, text = "hello",
            author = PrCommentAuthor("u", "U"),
            createdDate = 1, updatedDate = 1,
            state = PrCommentState.OPEN, severity = PrCommentSeverity.NORMAL,
        )
        assertNull(c.anchor)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun `constructs inline comment with anchor`() {
        val c = PrComment(
            id = "1", version = 0, text = "inline",
            author = PrCommentAuthor("u", "U"),
            createdDate = 1, updatedDate = 1,
            state = PrCommentState.OPEN, severity = PrCommentSeverity.BLOCKER,
            anchor = PrCommentAnchor(
                path = "Foo.kt",
                line = 42,
                lineType = PrCommentLineType.ADDED,
                fileType = PrCommentFileType.TO,
            ),
        )
        assertEquals("Foo.kt", c.anchor?.path)
        assertEquals(PrCommentSeverity.BLOCKER, c.severity)
    }
}
