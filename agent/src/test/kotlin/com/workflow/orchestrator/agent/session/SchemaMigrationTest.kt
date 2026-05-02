package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 4 of multimodal-agent plan — schema versioning + backward-compat read.
 *
 * The pre-Phase-4 on-disk shape was a bare JSON array `[ApiMessage, ApiMessage, ...]`.
 * Phase 4 introduces a wrapper object `{"schemaVersion": 2, "messages": [...]}`. The
 * loader must:
 *   - Try v2 wrapper first
 *   - Fall back to v1 bare array
 *   - Synthesize a default `schemaVersion = 1` for v1 reads
 *
 * The writer must always emit the v2 wrapper.
 *
 * Note: `ApiRole` is `enum class ApiRole { USER, ASSISTANT }`. Default
 * kotlinx-serialization uses constant names (UPPERCASE). Tests use `"USER"`,
 * not `"user"`. The real `MessageStateHandler` API is:
 *   `MessageStateHandler(baseDir: File, sessionId: String, taskText: String)`
 *   `MessageStateHandler.Companion.loadApiHistory(sessionDir: File): List<ApiMessage>`  // STATIC
 *   suspend `addToApiConversationHistory(message: ApiMessage)`
 *   suspend `saveBoth()`
 */
class SchemaMigrationTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `v1 session JSON (List shape) loads cleanly via v2-aware reader`() {
        // v1 sessions on disk are a bare JSON array (no wrapper object, no schemaVersion)
        val v1Json = """
            [{
              "role": "USER",
              "content": [
                {"type": "text", "text": "hello"}
              ]
            }]
        """.trimIndent()
        val sessionDir = tempDir.resolve("sessions/v1session").also { Files.createDirectories(it) }
        Files.writeString(sessionDir.resolve("api_conversation_history.json"), v1Json)

        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        assertEquals(1, msgs.size)
        assertEquals(1, msgs[0].content.size)
        assertTrue(msgs[0].content[0] is ContentBlock.Text)
        assertEquals("hello", (msgs[0].content[0] as ContentBlock.Text).text)
    }

    @Test
    fun `v2 session JSON (wrapper object with schemaVersion) loads with ImageRef intact`() {
        val v2Json = """
            {
              "schemaVersion": 2,
              "messages": [{
                "role": "USER",
                "content": [
                  {"type": "image_url_ref", "sha256": "abc", "mime": "image/png", "size": 100, "originalFilename": "x.png"},
                  {"type": "text", "text": "what is this?"}
                ]
              }]
            }
        """.trimIndent()
        val sessionDir = tempDir.resolve("sessions/v2session").also { Files.createDirectories(it) }
        Files.writeString(sessionDir.resolve("api_conversation_history.json"), v2Json)

        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        assertEquals(1, msgs.size)
        assertEquals(2, msgs[0].content.size)
        assertTrue(msgs[0].content[0] is ContentBlock.ImageRef)
        val ref = msgs[0].content[0] as ContentBlock.ImageRef
        assertEquals("abc", ref.sha256)
        assertEquals("image/png", ref.mime)
        assertEquals(100L, ref.size)
        assertEquals("x.png", ref.originalFilename)
        assertTrue(msgs[0].content[1] is ContentBlock.Text)
    }

    @Test
    fun `writer emits schemaVersion 2 in new sessions`() = runBlocking {
        val baseDir = tempDir.resolve("base").toFile().apply { mkdirs() }
        val handler = MessageStateHandler(baseDir = baseDir, sessionId = "newsession", taskText = "test")
        handler.addToApiConversationHistory(
            ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("hi")))
        )
        handler.saveBoth()  // explicit flush

        val written = File(baseDir, "sessions/newsession/api_conversation_history.json").readText()
        assertTrue(
            written.contains("\"schemaVersion\":2"),
            "Expected schemaVersion field, got: $written"
        )
        assertTrue(
            written.contains("\"messages\""),
            "Expected messages wrapper field, got: $written"
        )
    }

    // ── Coverage beyond the plan minimum ─────────────────────────────────

    @Test
    fun `loadApiHistory returns empty list when file does not exist`() {
        val sessionDir = tempDir.resolve("sessions/missing").also { Files.createDirectories(it) }
        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        assertEquals(emptyList<ApiMessage>(), msgs)
    }

    @Test
    fun `loadApiHistory returns empty list when JSON is malformed`() {
        val sessionDir = tempDir.resolve("sessions/garbage").also { Files.createDirectories(it) }
        Files.writeString(
            sessionDir.resolve("api_conversation_history.json"),
            "not json at all { [ )"
        )
        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        // Both v2-wrapper try and v1-array fallback fail; loader should return [] not throw
        assertEquals(emptyList<ApiMessage>(), msgs)
    }

    @Test
    fun `v2 reader on an empty messages array round-trips cleanly`() {
        val v2Json = """{"schemaVersion":2,"messages":[]}"""
        val sessionDir = tempDir.resolve("sessions/empty-v2").also { Files.createDirectories(it) }
        Files.writeString(sessionDir.resolve("api_conversation_history.json"), v2Json)
        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        assertEquals(emptyList<ApiMessage>(), msgs)
    }

    @Test
    fun `v1 bare-array containing unknown discriminator still loads via Phase 1 fallback`() {
        // Belt-and-braces: even before v2, a v1 session that somehow contains
        // a future content type must not crash the loader (Phase 1 polymorphic fallback).
        val v1WithFuture = """
            [{
              "role": "USER",
              "content": [
                {"type": "text", "text": "see this:"},
                {"type": "some_future_type", "anyKey": "anyValue"}
              ]
            }]
        """.trimIndent()
        val sessionDir = tempDir.resolve("sessions/v1future").also { Files.createDirectories(it) }
        Files.writeString(sessionDir.resolve("api_conversation_history.json"), v1WithFuture)
        val msgs = MessageStateHandler.loadApiHistory(sessionDir.toFile())
        assertEquals(1, msgs.size)
        assertEquals(2, msgs[0].content.size)
        assertTrue(msgs[0].content[1] is UnsupportedContentBlock)
    }

    @Test
    fun `writer round-trips ImageRef through saveBoth and loadApiHistory`() = runBlocking {
        val baseDir = tempDir.resolve("rtbase").toFile().apply { mkdirs() }
        val sessionId = "rt"
        val handler = MessageStateHandler(baseDir = baseDir, sessionId = sessionId, taskText = "rt")
        handler.addToApiConversationHistory(
            ApiMessage(
                role = ApiRole.USER,
                content = listOf(
                    ContentBlock.ImageRef(sha256 = "deadbeef", mime = "image/png", size = 42L, originalFilename = "img.png"),
                    ContentBlock.Text("describe this")
                )
            )
        )
        handler.saveBoth()

        val sessionDir = File(baseDir, "sessions/$sessionId")
        val reloaded = MessageStateHandler.loadApiHistory(sessionDir)
        assertEquals(1, reloaded.size)
        assertEquals(2, reloaded[0].content.size)
        val firstBlock = reloaded[0].content[0]
        assertNotNull(firstBlock)
        assertTrue(firstBlock is ContentBlock.ImageRef)
        val ref = firstBlock as ContentBlock.ImageRef
        assertEquals("deadbeef", ref.sha256)
        assertEquals(42L, ref.size)
        assertEquals("img.png", ref.originalFilename)
    }
}
