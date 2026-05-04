package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.core.ai.dto.ContentPart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiMessageImageRefRoundTripTest {

    @Test
    fun `toChatMessage preserves ImageRef as ContentPart Image on parts list`() {
        val api = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.Text("here is the screenshot"),
                ContentBlock.ImageRef(
                    sha256 = "deadbeef",
                    mime = "image/png",
                    size = 12345L,
                    originalFilename = "screenshot.png"
                )
            )
        )

        val chat = api.toChatMessage()

        val parts = chat.parts
        assertNotNull(parts, "parts must be populated when ImageRef is present")
        assertEquals(2, parts!!.size)
        assertTrue(parts[0] is ContentPart.Text)
        assertEquals("here is the screenshot", (parts[0] as ContentPart.Text).text)
        assertTrue(parts[1] is ContentPart.Image)
        val img = parts[1] as ContentPart.Image
        assertEquals("deadbeef", img.sha256)
        assertEquals("image/png", img.mime)
    }

    @Test
    fun `toChatMessage tool_result with ImageRef keeps both blocks visible`() {
        val api = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.ToolResult(
                    toolUseId = "call_abc",
                    content = "downloaded screenshot.png (image/png, 12345 bytes)",
                    isError = false
                ),
                ContentBlock.ImageRef(
                    sha256 = "cafef00d",
                    mime = "image/png",
                    size = 12345L,
                    originalFilename = "screenshot.png"
                )
            )
        )

        val chat = api.toChatMessage()

        // role stays "tool" (Cody coerces to "human" downstream — see F-P6-5)
        assertEquals("tool", chat.role)
        assertEquals("call_abc", chat.toolCallId)
        assertEquals("downloaded screenshot.png (image/png, 12345 bytes)", chat.content)
        // parts list carries the image so BrainRouter.hasImageParts() detects it
        assertNotNull(chat.parts)
        assertTrue(chat.parts!!.any { it is ContentPart.Image && it.sha256 == "cafef00d" })
    }
}
