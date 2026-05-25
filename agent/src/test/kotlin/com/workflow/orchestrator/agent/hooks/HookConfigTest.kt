package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class HookConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load returns empty list when file does not exist`() {
        val configs = HookConfigLoader.load(tempDir.toString())
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `load parses valid config file`() {
        val configContent = """
            {
              "hooks": [
                {"type": "PreToolUse", "command": "echo check"},
                {"type": "TaskStart", "command": "notify-send 'started'", "timeout": 5000}
              ]
            }
        """.trimIndent()

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())

        assertEquals(2, configs.size)

        assertEquals(HookType.PRE_TOOL_USE, configs[0].type)
        assertEquals("echo check", configs[0].command)
        assertEquals(HookConfig.DEFAULT_TIMEOUT_MS, configs[0].timeout)

        assertEquals(HookType.TASK_START, configs[1].type)
        assertEquals("notify-send 'started'", configs[1].command)
        assertEquals(5000L, configs[1].timeout)
    }

    @Test
    fun `load accepts UPPER_SNAKE_CASE type names`() {
        val configContent = """
            {
              "hooks": [
                {"type": "PRE_TOOL_USE", "command": "echo check"}
              ]
            }
        """.trimIndent()

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertEquals(1, configs.size)
        assertEquals(HookType.PRE_TOOL_USE, configs[0].type)
    }

    @Test
    fun `load skips entries with unknown type`() {
        val configContent = """
            {
              "hooks": [
                {"type": "UnknownHookType", "command": "echo bad"},
                {"type": "TaskStart", "command": "echo good"}
              ]
            }
        """.trimIndent()

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertEquals(1, configs.size)
        assertEquals(HookType.TASK_START, configs[0].type)
    }

    @Test
    fun `load skips entries with blank command`() {
        val configContent = """
            {
              "hooks": [
                {"type": "TaskStart", "command": "  "},
                {"type": "TaskCancel", "command": "echo cleanup"}
              ]
            }
        """.trimIndent()

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertEquals(1, configs.size)
        assertEquals(HookType.TASK_CANCEL, configs[0].type)
    }

    @Test
    fun `load clamps timeout to bounds`() {
        val configContent = """
            {
              "hooks": [
                {"type": "TaskStart", "command": "echo test", "timeout": 100},
                {"type": "TaskCancel", "command": "echo test", "timeout": 999999}
              ]
            }
        """.trimIndent()

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertEquals(2, configs.size)
        assertEquals(1000L, configs[0].timeout) // clamped to minimum 1s
        assertEquals(120_000L, configs[1].timeout) // clamped to maximum 120s
    }

    @Test
    fun `load returns empty for malformed JSON`() {
        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText("not json at all")

        val configs = HookConfigLoader.load(tempDir.toString())
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `load returns empty for empty hooks array`() {
        val configContent = """{"hooks": []}"""

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `load handles all hook types`() {
        val allTypes = HookType.entries.map { it.hookName }
        val hooksJson = allTypes.joinToString(",\n") { type ->
            """{"type": "$type", "command": "echo $type"}"""
        }
        val configContent = """{"hooks": [$hooksJson]}"""

        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(configContent)

        val configs = HookConfigLoader.load(tempDir.toString())
        assertEquals(HookType.entries.size, configs.size)
    }

    // ════════════════════════════════════════════
    //  Trust gate (audit finding agent-runtime:F-1)
    //  When a Project is supplied, .agent-hooks.json is gated on
    //  per-SHA trust via HookTrustStore.
    // ════════════════════════════════════════════

    private val validConfig = """
        {"hooks": [{"type": "TaskStart", "command": "echo hi"}]}
    """.trimIndent()

    private fun projectWith(trustStore: HookTrustStore): Project {
        val project = mockk<Project>(relaxed = true)
        every { project.getService(HookTrustStore::class.java) } returns trustStore
        return project
    }

    @Test
    fun `computeSha256 is deterministic and content-sensitive`() {
        val a1 = HookConfigLoader.computeSha256("abc".toByteArray())
        val a2 = HookConfigLoader.computeSha256("abc".toByteArray())
        val b = HookConfigLoader.computeSha256("abd".toByteArray())
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertEquals(64, a1.length) // 32 bytes hex
    }

    @Test
    fun `trusted SHA loads hooks normally`() {
        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(validConfig)
        val sha = HookConfigLoader.computeSha256(validConfig.toByteArray())
        val store = HookTrustStore().apply { setTrusted(sha) }

        val configs = HookConfigLoader.load(tempDir.toString(), projectWith(store))
        assertEquals(1, configs.size)
        assertEquals(HookType.TASK_START, configs[0].type)
    }

    @Test
    fun `rejected SHA returns empty config`() {
        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(validConfig)
        val sha = HookConfigLoader.computeSha256(validConfig.toByteArray())
        val store = HookTrustStore().apply { setRejected(sha) }

        val configs = HookConfigLoader.load(tempDir.toString(), projectWith(store))
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `unknown SHA returns empty config (degraded mode)`() {
        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(validConfig)
        val store = HookTrustStore() // empty → every SHA is UNKNOWN

        val configs = HookConfigLoader.load(tempDir.toString(), projectWith(store))
        assertTrue(configs.isEmpty())
    }

    @Test
    fun `file content change invalidates prior trust`() {
        val file = File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME)
        file.writeText(validConfig)
        val originalSha = HookConfigLoader.computeSha256(validConfig.toByteArray())
        val store = HookTrustStore().apply { setTrusted(originalSha) }
        val project = projectWith(store)

        // First load with the trusted content → hooks present.
        assertEquals(1, HookConfigLoader.load(tempDir.toString(), project).size)

        // Modify the file: new SHA is UNKNOWN → degraded to empty.
        file.writeText("""{"hooks": [{"type": "TaskStart", "command": "echo CHANGED"}]}""")
        assertTrue(HookConfigLoader.load(tempDir.toString(), project).isEmpty())
    }

    @Test
    fun `null project skips the gate (legacy callers)`() {
        File(tempDir.toFile(), HookConfigLoader.CONFIG_FILE_NAME).writeText(validConfig)
        // No project → no trust check → hooks load directly.
        val configs = HookConfigLoader.load(tempDir.toString(), null)
        assertEquals(1, configs.size)
    }
}
