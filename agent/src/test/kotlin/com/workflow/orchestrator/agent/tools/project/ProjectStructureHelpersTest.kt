package com.workflow.orchestrator.agent.tools.project

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectStructureHelpersTest {

    @Test fun `relativizeToProject strips basePath when present`() {
        assertEquals("core/src/Foo.kt", relativizeToProject("/proj/core/src/Foo.kt", "/proj"))
    }

    @Test fun `relativizeToProject returns absolute when no basePath match`() {
        assertEquals("/other/Foo.kt", relativizeToProject("/other/Foo.kt", "/proj"))
    }

    @Test fun `relativizeToProject handles null basePath`() {
        assertEquals("/any/Foo.kt", relativizeToProject("/any/Foo.kt", null))
    }

    @Test fun `relativizeToProject handles exact match`() {
        assertEquals(".", relativizeToProject("/proj", "/proj"))
    }

    @Test fun `sourceRootKindLabel maps all four kinds`() {
        assertEquals("source", sourceRootKindLabel(SourceRootKind.SOURCE))
        assertEquals("test_source", sourceRootKindLabel(SourceRootKind.TEST_SOURCE))
        assertEquals("resource", sourceRootKindLabel(SourceRootKind.RESOURCE))
        assertEquals("test_resource", sourceRootKindLabel(SourceRootKind.TEST_RESOURCE))
    }

    @Test fun `parseSourceRootKind round-trips all labels`() {
        SourceRootKind.entries.forEach { kind ->
            assertEquals(kind, parseSourceRootKind(sourceRootKindLabel(kind)))
        }
    }

    @Test fun `parseSourceRootKind returns null for unknown`() {
        assertNull(parseSourceRootKind("unknown"))
        assertNull(parseSourceRootKind(null))
    }
}
