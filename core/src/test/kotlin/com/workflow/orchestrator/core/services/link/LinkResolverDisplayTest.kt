package com.workflow.orchestrator.core.services.link

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ChatLink
import com.workflow.orchestrator.core.model.LinkResolution
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LinkResolverDisplayTest {

    private val projectStub = mockk<Project>(relaxed = true)

    @Nested
    inner class FileLinks {
        private val resolver = FileLinkResolver(projectStub)

        @Test
        fun `path only`() {
            val r = resolver.resolve(ChatLink.FileLink(raw = "file:Foo.kt", path = "Foo.kt"))
            assertEquals(LinkResolution.Kind.FILE, r.kind)
            assertEquals("file:Foo.kt", r.raw)
            assertEquals("Foo.kt", r.displayLabel)
            assertEquals("Opens file", r.targetDescription)
        }

        @Test
        fun `nested path only uses last segment as label`() {
            val r = resolver.resolve(
                ChatLink.FileLink(raw = "file:core/services/Foo.kt", path = "core/services/Foo.kt")
            )
            assertEquals("Foo.kt", r.displayLabel)
            assertEquals("Opens file", r.targetDescription)
        }

        @Test
        fun `with single line`() {
            val r = resolver.resolve(
                ChatLink.FileLink(raw = "file:Foo.kt:42", path = "Foo.kt", line = 42)
            )
            assertEquals("Foo.kt:42", r.displayLabel)
            assertEquals("Opens file at line 42", r.targetDescription)
        }

        @Test
        fun `with multi-line range`() {
            val r = resolver.resolve(
                ChatLink.FileLink(
                    raw = "file:core/Foo.kt:42-58",
                    path = "core/Foo.kt",
                    line = 42,
                    endLine = 58,
                )
            )
            assertEquals("Foo.kt:42-58", r.displayLabel)
            assertEquals("Opens file at lines 42–58", r.targetDescription)
        }

        @Test
        fun `same-line range collapses to single line in label and description`() {
            val r = resolver.resolve(
                ChatLink.FileLink(
                    raw = "file:Foo.kt:42-42",
                    path = "Foo.kt",
                    line = 42,
                    endLine = 42,
                )
            )
            assertEquals("Foo.kt:42", r.displayLabel)
            assertEquals("Opens file at line 42", r.targetDescription)
        }

        @Test
        fun `raw is verbatim`() {
            val raw = "file:weird:dir/Foo.kt:42-58"
            val r = resolver.resolve(
                ChatLink.FileLink(raw = raw, path = "weird:dir/Foo.kt", line = 42, endLine = 58)
            )
            assertEquals(raw, r.raw)
        }
    }

    @Nested
    inner class ClassLinks {
        private val resolver = ClassLinkResolver(projectStub)

        @Test
        fun `class only`() {
            val r = resolver.resolve(ChatLink.ClassLink(raw = "class:com.foo.Bar", fqn = "com.foo.Bar"))
            assertEquals(LinkResolution.Kind.CLASS, r.kind)
            assertEquals("Bar", r.displayLabel)
            assertEquals("Opens class com.foo.Bar", r.targetDescription)
        }

        @Test
        fun `class with method`() {
            val r = resolver.resolve(
                ChatLink.ClassLink(raw = "class:com.foo.Bar#run", fqn = "com.foo.Bar", method = "run")
            )
            assertEquals("Bar#run", r.displayLabel)
            assertEquals("Opens method com.foo.Bar#run", r.targetDescription)
        }

        @Test
        fun `deeply-nested fqn extracts simple name`() {
            val r = resolver.resolve(
                ChatLink.ClassLink(
                    raw = "class:com.workflow.orchestrator.core.services.AgentService",
                    fqn = "com.workflow.orchestrator.core.services.AgentService",
                )
            )
            assertEquals("AgentService", r.displayLabel)
            assertEquals(
                "Opens class com.workflow.orchestrator.core.services.AgentService",
                r.targetDescription,
            )
        }

        @Test
        fun `inner class fqn uses last dotted segment`() {
            val r = resolver.resolve(
                ChatLink.ClassLink(
                    raw = "class:com.foo.Bar.Inner#method",
                    fqn = "com.foo.Bar.Inner",
                    method = "method",
                )
            )
            assertEquals("Inner#method", r.displayLabel)
            assertEquals("Opens method com.foo.Bar.Inner#method", r.targetDescription)
        }

        @Test
        fun `raw is verbatim`() {
            val raw = "class:com.foo.Bar#run"
            val r = resolver.resolve(
                ChatLink.ClassLink(raw = raw, fqn = "com.foo.Bar", method = "run")
            )
            assertEquals(raw, r.raw)
        }
    }

    @Nested
    inner class JiraLinks {
        private val resolver = JiraLinkResolver(
            getJiraBaseUrl = { "https://jira.example.com" },
            notifyMissingUrl = {},
        )

        @Test
        fun `ticket id echoed verbatim`() {
            val r = resolver.resolve(ChatLink.JiraLink(raw = "jira:WORK-1234", ticketId = "WORK-1234"))
            assertEquals(LinkResolution.Kind.JIRA, r.kind)
            assertEquals("WORK-1234", r.displayLabel)
            assertEquals("Opens Jira ticket WORK-1234 in browser", r.targetDescription)
            assertEquals("jira:WORK-1234", r.raw)
        }

        @Test
        fun `mixed alphanumeric ticket id`() {
            val r = resolver.resolve(
                ChatLink.JiraLink(raw = "jira:AFTER8TE-912", ticketId = "AFTER8TE-912")
            )
            assertEquals("AFTER8TE-912", r.displayLabel)
            assertEquals("Opens Jira ticket AFTER8TE-912 in browser", r.targetDescription)
        }
    }

    @Nested
    inner class WebLinks {
        private val resolver = WebLinkResolver()

        @Test
        fun `url with host extracts host as label`() {
            val r = resolver.resolve(
                ChatLink.WebLink(raw = "https://github.com/x", url = "https://github.com/x")
            )
            assertEquals(LinkResolution.Kind.WEB, r.kind)
            assertEquals("github.com", r.displayLabel)
            assertEquals("Opens in external browser", r.targetDescription)
        }

        @Test
        fun `url with path and query still extracts host`() {
            val r = resolver.resolve(
                ChatLink.WebLink(
                    raw = "http://example.com/path?q=1",
                    url = "http://example.com/path?q=1",
                )
            )
            assertEquals("example.com", r.displayLabel)
        }

        @Test
        fun `malformed url falls back to full url`() {
            // URI parses this without throwing but yields a null host
            val malformed = "https:///no-host-here"
            val r = resolver.resolve(ChatLink.WebLink(raw = malformed, url = malformed))
            assertEquals(malformed, r.displayLabel)
        }

        @Test
        fun `unparseable url falls back to full url`() {
            val bad = "https://[invalid"
            val r = resolver.resolve(ChatLink.WebLink(raw = bad, url = bad))
            assertEquals(bad, r.displayLabel)
        }

        @Test
        fun `raw is verbatim`() {
            val raw = "https://github.com/anthropics/claude-code/issues/1"
            val r = resolver.resolve(ChatLink.WebLink(raw = raw, url = raw))
            assertEquals(raw, r.raw)
        }
    }
}
