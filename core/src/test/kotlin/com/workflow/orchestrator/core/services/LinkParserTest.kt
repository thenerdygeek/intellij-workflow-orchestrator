package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.ChatLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LinkParserTest {

    @Nested
    inner class FileLinks {

        @Test
        fun `parse path only returns FileLink with null line`() {
            val r = LinkParser.parse("file:Foo.kt")
            assertEquals(ChatLink.FileLink(raw = "file:Foo.kt", path = "Foo.kt"), r)
        }

        @Test
        fun `parse nested path only`() {
            val r = LinkParser.parse("file:core/services/Foo.kt")
            assertEquals(
                ChatLink.FileLink(raw = "file:core/services/Foo.kt", path = "core/services/Foo.kt"),
                r
            )
        }

        @Test
        fun `parse with single line`() {
            val r = LinkParser.parse("file:Foo.kt:42")
            assertEquals(
                ChatLink.FileLink(raw = "file:Foo.kt:42", path = "Foo.kt", line = 42),
                r
            )
        }

        @Test
        fun `parse with line range`() {
            val r = LinkParser.parse("file:core/services/Foo.kt:42-58")
            assertEquals(
                ChatLink.FileLink(
                    raw = "file:core/services/Foo.kt:42-58",
                    path = "core/services/Foo.kt",
                    line = 42,
                    endLine = 58
                ),
                r
            )
        }

        @Test
        fun `parse path with spaces`() {
            val r = LinkParser.parse("file:my dir/Foo.kt:42")
            assertEquals(
                ChatLink.FileLink(raw = "file:my dir/Foo.kt:42", path = "my dir/Foo.kt", line = 42),
                r
            )
        }

        @Test
        fun `parse path with colons in directory`() {
            val r = LinkParser.parse("file:weird:dir/Foo.kt:42")
            assertEquals(
                ChatLink.FileLink(
                    raw = "file:weird:dir/Foo.kt:42",
                    path = "weird:dir/Foo.kt",
                    line = 42
                ),
                r
            )
        }

        @Test
        fun `parse path with multiple colons keeps last digit suffix as line`() {
            val r = LinkParser.parse("file:a:b:c/Foo.kt:42-58")
            assertEquals(
                ChatLink.FileLink(
                    raw = "file:a:b:c/Foo.kt:42-58",
                    path = "a:b:c/Foo.kt",
                    line = 42,
                    endLine = 58
                ),
                r
            )
        }

        @Test
        fun `parse rejects zero line`() {
            assertNull(LinkParser.parse("file:Foo.kt:0"))
        }

        @Test
        fun `parse rejects zero in range`() {
            assertNull(LinkParser.parse("file:Foo.kt:0-5"))
        }

        @Test
        fun `parse rejects inverted range`() {
            assertNull(LinkParser.parse("file:Foo.kt:50-42"))
        }

        @Test
        fun `parse accepts same-line range and preserves endLine`() {
            val r = LinkParser.parse("file:Foo.kt:42-42")
            assertEquals(
                ChatLink.FileLink(
                    raw = "file:Foo.kt:42-42",
                    path = "Foo.kt",
                    line = 42,
                    endLine = 42
                ),
                r
            )
        }

        @Test
        fun `parse rejects non-integer line`() {
            assertNull(LinkParser.parse("file:Foo.kt:abc"))
        }

        @Test
        fun `parse rejects empty path`() {
            assertNull(LinkParser.parse("file:"))
        }

        @Test
        fun `parse rejects empty path with line`() {
            // ":42" alone — after stripping scheme there is no path before the digit suffix
            assertNull(LinkParser.parse("file::42"))
        }

        @Test
        fun `parse preserves raw verbatim`() {
            val href = "file:core/services/Foo.kt:42-58"
            val r = LinkParser.parse(href) as ChatLink.FileLink
            assertEquals(href, r.raw)
        }
    }

    @Nested
    inner class ClassLinks {

        @Test
        fun `parse fqn only`() {
            val r = LinkParser.parse("class:com.foo.Bar")
            assertEquals(ChatLink.ClassLink(raw = "class:com.foo.Bar", fqn = "com.foo.Bar"), r)
        }

        @Test
        fun `parse fqn with method`() {
            val r = LinkParser.parse("class:com.foo.Bar#run")
            assertEquals(
                ChatLink.ClassLink(raw = "class:com.foo.Bar#run", fqn = "com.foo.Bar", method = "run"),
                r
            )
        }

        @Test
        fun `parse inner-class fqn with method`() {
            val r = LinkParser.parse("class:com.foo.Bar.Inner#method")
            assertEquals(
                ChatLink.ClassLink(
                    raw = "class:com.foo.Bar.Inner#method",
                    fqn = "com.foo.Bar.Inner",
                    method = "method"
                ),
                r
            )
        }

        @Test
        fun `parse deep fqn`() {
            val r = LinkParser.parse("class:com.workflow.orchestrator.core.services.AgentService")
            assertEquals(
                ChatLink.ClassLink(
                    raw = "class:com.workflow.orchestrator.core.services.AgentService",
                    fqn = "com.workflow.orchestrator.core.services.AgentService"
                ),
                r
            )
        }

        @Test
        fun `parse rejects empty fqn`() {
            assertNull(LinkParser.parse("class:"))
        }

        @Test
        fun `parse rejects empty fqn with method`() {
            assertNull(LinkParser.parse("class:#method"))
        }

        @Test
        fun `parse rejects trailing dot`() {
            assertNull(LinkParser.parse("class:com.foo."))
        }

        @Test
        fun `parse rejects leading dot`() {
            assertNull(LinkParser.parse("class:.com.foo.Bar"))
        }

        @Test
        fun `parse rejects empty method`() {
            assertNull(LinkParser.parse("class:com.foo.Bar#"))
        }

        @Test
        fun `parse preserves raw verbatim`() {
            val href = "class:com.foo.Bar#run"
            val r = LinkParser.parse(href) as ChatLink.ClassLink
            assertEquals(href, r.raw)
        }
    }

    @Nested
    inner class JiraLinks {

        @Test
        fun `parse valid ticket`() {
            val r = LinkParser.parse("jira:WORK-1234")
            assertEquals(ChatLink.JiraLink(raw = "jira:WORK-1234", ticketId = "WORK-1234"), r)
        }

        @Test
        fun `parse ticket with mixed alphanumeric key`() {
            val r = LinkParser.parse("jira:AFTER8TE-912")
            assertEquals(ChatLink.JiraLink(raw = "jira:AFTER8TE-912", ticketId = "AFTER8TE-912"), r)
        }

        @Test
        fun `parse rejects lowercase project key`() {
            assertNull(LinkParser.parse("jira:work-1234"))
        }

        @Test
        fun `parse rejects mixed-case project key`() {
            assertNull(LinkParser.parse("jira:Work-1234"))
        }

        @Test
        fun `parse rejects missing hyphen`() {
            assertNull(LinkParser.parse("jira:WORK1234"))
        }

        @Test
        fun `parse rejects missing digits`() {
            assertNull(LinkParser.parse("jira:WORK-"))
        }

        @Test
        fun `parse rejects empty`() {
            assertNull(LinkParser.parse("jira:"))
        }

        @Test
        fun `parse rejects trailing garbage`() {
            assertNull(LinkParser.parse("jira:WORK-1234-extra"))
        }

        @Test
        fun `parse rejects key starting with digit`() {
            assertNull(LinkParser.parse("jira:1WORK-1234"))
        }

        @Test
        fun `parse preserves raw verbatim`() {
            val href = "jira:WORK-1234"
            val r = LinkParser.parse(href) as ChatLink.JiraLink
            assertEquals(href, r.raw)
        }
    }

    @Nested
    inner class WebLinks {

        @Test
        fun `parse https url`() {
            val r = LinkParser.parse("https://github.com/x")
            assertEquals(
                ChatLink.WebLink(raw = "https://github.com/x", url = "https://github.com/x"),
                r
            )
        }

        @Test
        fun `parse http url`() {
            val r = LinkParser.parse("http://example.com/path?q=1")
            assertEquals(
                ChatLink.WebLink(
                    raw = "http://example.com/path?q=1",
                    url = "http://example.com/path?q=1"
                ),
                r
            )
        }

        @Test
        fun `parse rejects empty https`() {
            assertNull(LinkParser.parse("https://"))
        }

        @Test
        fun `parse rejects empty http`() {
            assertNull(LinkParser.parse("http://"))
        }

        @Test
        fun `parse preserves raw verbatim`() {
            val href = "https://github.com/anthropics/claude-code/issues/1"
            val r = LinkParser.parse(href) as ChatLink.WebLink
            assertEquals(href, r.raw)
        }
    }

    @Nested
    inner class Malformed {

        @Test
        fun `empty string returns null`() {
            assertNull(LinkParser.parse(""))
        }

        @Test
        fun `bare word returns null`() {
            assertNull(LinkParser.parse("just-a-word"))
        }

        @Test
        fun `unknown scheme returns null`() {
            assertNull(LinkParser.parse("ftp://example.com/foo"))
        }

        @Test
        fun `scheme without colon returns null`() {
            assertNull(LinkParser.parse("file"))
        }

        @Test
        fun `whitespace-only returns null`() {
            assertNull(LinkParser.parse("   "))
        }
    }
}
