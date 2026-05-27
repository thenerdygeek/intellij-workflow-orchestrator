package com.workflow.orchestrator.agent.ui.approval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CommandApprovalPayload.build]. Pure function — no IntelliJ
 * services are exercised (project is passed as null; ShellResolver falls back
 * to the default bash path, ProcessEnvironment is called directly).
 */
class CommandApprovalPayloadTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun decode(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `emits full command, resolved shell, cwd and empty env for minimal args`() {
        val args = parse("""{"command":"echo hello","working_dir":"/tmp"}""")

        val result = CommandApprovalPayload.build(args, project = null)
        val payload = decode(result.commandPreviewJson)

        assertEquals("echo hello", payload["command"]!!.jsonPrimitive.content)
        assertEquals("/tmp", payload["cwd"]!!.jsonPrimitive.content)
        assertFalse(payload["separateStderr"]!!.jsonPrimitive.booleanOrNull ?: true)
        assertEquals(0, payload["env"]!!.jsonArray.size)
        // Shell resolves to a non-blank bash/cmd path on every platform.
        assertTrue(payload["shell"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `working_dir flows into the cwd payload field (the key the tool actually sends)`() {
        // Regression: the tool schema + execute() use `working_dir`; reading only
        // `cwd` made the approval card always show the project root.
        val args = parse("""{"command":"mvn test","working_dir":"services/foo"}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)
        assertEquals("services/foo", payload["cwd"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy cwd key is still honored as a fallback`() {
        val args = parse("""{"command":"ls","cwd":"/legacy/path"}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)
        assertEquals("/legacy/path", payload["cwd"]!!.jsonPrimitive.content)
    }

    @Test
    fun `working_dir wins over legacy cwd when both are present`() {
        val args = parse("""{"command":"ls","working_dir":"/new","cwd":"/old"}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)
        assertEquals("/new", payload["cwd"]!!.jsonPrimitive.content)
    }

    @Test
    fun `uses LLM-provided description when present, falls back to 'Run command' otherwise`() {
        val withDesc = CommandApprovalPayload.build(
            parse("""{"command":"ls","description":"List files"}"""),
            project = null,
        )
        assertEquals("List files", withDesc.description)

        val withoutDesc = CommandApprovalPayload.build(parse("""{"command":"ls"}"""), project = null)
        assertEquals("Run command", withoutDesc.description)
    }

    @Test
    fun `env entries are included as {key,value} objects in the order provided`() {
        val args = parse("""{"command":"x","env":{"FOO":"1","BAR":"2"}}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)

        val env = payload["env"]!!.jsonArray.map {
            val o = it.jsonObject
            o["key"]!!.jsonPrimitive.content to o["value"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("FOO" to "1", "BAR" to "2"), env)
    }

    @Test
    fun `blocked env vars are stripped from the preview payload`() {
        // LD_PRELOAD is in ProcessEnvironment.BLOCKED_ENV_VARS.
        val args = parse("""{"command":"x","env":{"SAFE":"ok","LD_PRELOAD":"/evil.so"}}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)

        val keys = payload["env"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(listOf("SAFE"), keys)
    }

    @Test
    fun `separateStderr true is preserved in the payload`() {
        val args = parse("""{"command":"x","separate_stderr":true}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)
        assertTrue(payload["separateStderr"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `multiline command is preserved verbatim (no truncation or diff formatting)`() {
        val cmd = "echo hello\nls -la /tmp\n./gradlew :agent:test"
        val jsonString = kotlinx.serialization.json.JsonPrimitive(cmd).toString()
        val args = parse("""{"command":$jsonString}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)

        val emitted = payload["command"]!!.jsonPrimitive.content
        assertEquals(cmd, emitted)
        assertFalse(emitted.startsWith("$ "), "Should NOT prefix command with '$ ' (that was the old diffContent behaviour).")
        assertFalse(emitted.contains("(shell:"), "Should NOT embed shell as text in the command field.")
    }

    @Test
    fun `unparseable env object falls back to empty`() {
        // env is a string, not an object — must not throw.
        val args = parse("""{"command":"x","env":"not-an-object"}""")
        val payload = decode(CommandApprovalPayload.build(args, project = null).commandPreviewJson)
        assertEquals(0, payload["env"]!!.jsonArray.size)
    }
}
