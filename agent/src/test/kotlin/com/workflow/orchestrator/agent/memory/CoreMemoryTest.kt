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
        fun `readAll returns all blocks`() {
            memory.append("a", "1")
            memory.append("b", "2")
            val all = memory.readAll()
            assertEquals(2, all.size)
        }
    }
}
