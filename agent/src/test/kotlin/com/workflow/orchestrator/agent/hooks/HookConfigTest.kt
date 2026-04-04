package com.workflow.orchestrator.agent.hooks

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
}
