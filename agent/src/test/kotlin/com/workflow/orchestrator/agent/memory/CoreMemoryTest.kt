package com.workflow.orchestrator.agent.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CoreMemoryTest {

    @TempDir lateinit var tempDir: Path
    private lateinit var storageFile: File
    private lateinit var memory: CoreMemory

    @BeforeEach
    fun setUp() {
        storageFile = tempDir.resolve("core-memory.json").toFile()
        memory = CoreMemory(storageFile)
    }

    @Nested
    inner class AppendTests {
        @Test
        fun `append creates block if absent`() {
            val result = memory.append("project", "Uses Gradle with Kotlin DSL")
            assertEquals("Uses Gradle with Kotlin DSL", result)
            assertEquals("Uses Gradle with Kotlin DSL", memory.read("project"))
        }

        @Test
        fun `append adds newline between entries`() {
            memory.append("project", "Line 1")
            val result = memory.append("project", "Line 2")
            assertEquals("Line 1\nLine 2", result)
        }

        @Test
        fun `append rejects read-only block`() {
            memory.setBlock("system", "Do not change", readOnly = true)
            assertThrows(IllegalArgumentException::class.java) {
                memory.append("system", "extra")
            }
        }

        @Test
        fun `append rejects if over limit`() {
            memory.setBlock("small", "", limit = 10)
            assertThrows(IllegalArgumentException::class.java) {
                memory.append("small", "A".repeat(11))
            }
        }
    }

    @Nested
    inner class ReplaceTests {
        @Test
        fun `replace exact match`() {
            memory.append("user", "Name: Alice")
            val result = memory.replace("user", "Alice", "Bob")
            assertEquals("Name: Bob", result)
        }

        @Test
        fun `replace rejects no match`() {
            memory.append("user", "Name: Alice")
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("user", "Charlie", "Bob")
            }
        }

        @Test
        fun `replace rejects multiple matches`() {
            memory.append("user", "foo foo")
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("user", "foo", "bar")
            }
        }

        @Test
        fun `replace rejects unknown block`() {
            assertThrows(IllegalArgumentException::class.java) {
                memory.replace("nonexistent", "a", "b")
            }
        }
    }

    @Nested
    inner class ReplaceFlexibleTests {

        @Test
        fun `replaceFlexible matches despite case differences`() {
            memory.append("project", "Auth migration in progress")
            memory.replaceFlexible("project", "auth migration in progress", "Auth migration complete")
            assertEquals("Auth migration complete", memory.read("project"))
        }

        @Test
        fun `replaceFlexible matches despite trailing punctuation`() {
            memory.append("project", "Deploy via Bamboo PROJ-DEPLOY-PROD")
            memory.replaceFlexible("project", "Deploy via Bamboo PROJ-DEPLOY-PROD.", "Deploy via Bamboo PROJ-DEPLOY-STAGING")
            assertEquals("Deploy via Bamboo PROJ-DEPLOY-STAGING", memory.read("project"))
        }

        @Test
        fun `replaceFlexible matches despite whitespace differences`() {
            memory.append("patterns", "Always use ToolResult for services")
            memory.replaceFlexible("patterns", "Always  use ToolResult\tfor services", "Always return ToolResult from services")
            assertEquals("Always return ToolResult from services", memory.read("patterns"))
        }

        @Test
        fun `replaceFlexible throws when no approximate match found`() {
            memory.append("user", "Backend developer")
            assertThrows(IllegalArgumentException::class.java) {
                memory.replaceFlexible("user", "Frontend developer", "Full-stack developer")
            }
        }

        @Test
        fun `replaceFlexible prefers longer matches over shorter`() {
            memory.append("patterns", "Use ToolResult. Use strict null checks. Use ToolResult for API boundaries.")
            // "Use ToolResult" appears twice but "Use ToolResult for API boundaries" is the intended target
            memory.replaceFlexible(
                "patterns",
                "use toolresult for api boundaries",
                "Use ToolResult everywhere"
            )
            val value = memory.read("patterns")!!
            assertTrue(value.contains("Use ToolResult everywhere"))
            // The first "Use ToolResult" should be preserved
            assertTrue(value.contains("Use ToolResult. Use strict null checks."))
        }
    }

    @Nested
    inner class CompileTests {
        @Test
        fun `compile returns null when empty`() {
            assertNull(memory.compile())
        }

        @Test
        fun `compile renders XML with block labels`() {
            memory.append("project", "Spring Boot app")
            memory.append("user", "Prefers Kotlin")
            val xml = memory.compile()!!
            assertTrue(xml.contains("<core_memory>"))
            assertTrue(xml.contains("<project>"))
            assertTrue(xml.contains("Spring Boot app"))
            assertTrue(xml.contains("<user>"))
            assertTrue(xml.contains("Prefers Kotlin"))
            assertTrue(xml.contains("</core_memory>"))
        }

        @Test
        fun `compile shows character usage`() {
            memory.append("project", "test")
            val xml = memory.compile()!!
            assertTrue(xml.contains("4/${CoreMemory.DEFAULT_BLOCK_LIMIT} chars used"))
        }
    }

    @Nested
    inner class PersistenceTests {
        @Test
        fun `persists and reloads`() {
            memory.append("project", "Gradle build")
            memory.append("user", "Uses dark theme")

            val reloaded = CoreMemory(storageFile)
            assertEquals("Gradle build", reloaded.read("project"))
            assertEquals("Uses dark theme", reloaded.read("user"))
        }

        @Test
        fun `handles missing file gracefully`() {
            val missing = File(tempDir.toFile(), "does-not-exist.json")
            val m = CoreMemory(missing)
            assertTrue(m.isEmpty())
        }
    }

    @Nested
    inner class HelperTests {
        @Test
        fun `isEmpty returns true initially`() {
            assertTrue(memory.isEmpty())
        }

        @Test
        fun `isEmpty returns false after append`() {
            memory.append("x", "data")
            assertFalse(memory.isEmpty())
        }

        @Test
        fun `totalChars counts all blocks`() {
            memory.append("a", "123")
            memory.append("b", "45678")
            assertEquals(8, memory.totalChars())
        }

        @Test
        fun `readAll returns all blocks including seeds`() {
            val seedCount = memory.readAll().size // pre-seeded blocks (user, project, patterns)
            memory.append("a", "1")
            memory.append("b", "2")
            val all = memory.readAll()
            assertEquals(seedCount + 2, all.size)
        }
    }

    @Nested
    inner class ReloadTests {

        @Test
        fun `reload picks up external changes to the JSON file`() {
            memory.append("user", "original content")

            // Simulate another process writing to the same file
            val otherInstance = CoreMemory(storageFile)
            otherInstance.append("user", "external addition")

            // Original instance still sees stale data
            assertEquals("original content", memory.read("user"))

            // After reload, it sees the external change
            memory.reload()
            assertEquals("original content\nexternal addition", memory.read("user"))
        }

        @Test
        fun `reload on missing file leaves empty state`() {
            memory.append("project", "something")
            storageFile.delete()

            memory.reload()
            assertTrue(memory.read("project").isNullOrBlank())
        }

        @Test
        fun `reload preserves default block seeding`() {
            memory.reload()
            // Default blocks user/project/patterns should still exist
            assertNotNull(memory.readAll()["user"])
            assertNotNull(memory.readAll()["project"])
            assertNotNull(memory.readAll()["patterns"])
        }
    }
}
