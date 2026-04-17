package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Unit tests for [formatCompileErrors]. Exercises the shared helper used by both
 * `run_tests` (pre-test build failure) and `compile_module` (explicit compile).
 *
 * These tests matter because the pre-change path returned
 * `"Compilation result: 1 errors, 0 warnings, aborted=false"` which the LLM
 * interpreted as "TDD red phase — implement the code", missing that the real
 * problem was a typo like `asserT` instead of `assertTrue`. The summary line
 * must lead with the actual error so skim-reading can't go wrong.
 *
 * NOTE: we intentionally avoid MockK for [CompileContext] / [CompilerMessage].
 * MockK tries to instantiate [CompilerMessageCategory] (an abstract class with
 * anonymous inner subclasses for ERROR/WARNING/INFO/STATISTICS) via Objenesis
 * whenever a mocked method's signature mentions it, which blows up with
 * `java.lang.InstantiationError: CompilerMessageCategory`.
 *
 * Instead we use Java dynamic `Proxy` to synthesize just enough of the
 * [CompileContext] / [CompilerMessage] / [VirtualFile] contracts that
 * [formatCompileErrors] exercises. [OpenFileDescriptor] is a concrete class
 * that [Proxy] cannot synthesize, so we mock it via MockK directly — this
 * avoids the `MockProject(null)` constructor-signature-drift hazard used
 * by an earlier iteration of this test.
 */
class FormatCompileErrorsTest {

    /** Synthesize an interface-satisfying proxy that routes specific method names
     *  through the given handler. All un-handled methods return `null` / `0` / `false`. */
    private inline fun <reified T : Any> proxy(crossinline handler: (methodName: String, args: Array<out Any?>?) -> Any?): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val result = handler(method.name, args)
            if (result != null) return@newProxyInstance result
            // Reasonable defaults for primitive return types
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Void.TYPE -> null
                else -> null
            }
        } as T
    }

    private fun fakeVirtualFile(name: String): VirtualFile =
        com.intellij.testFramework.LightVirtualFile(name)

    /** OpenFileDescriptor is a concrete class, not an interface, so [Proxy] cannot
     *  synthesize it. Use MockK instead — we only need the runtime type to be
     *  [OpenFileDescriptor] (for the `is` check in the helper) and for `line` /
     *  `column` to return our stub values. This avoids the prior approach of
     *  subclassing with `MockProject(null)`, whose constructor shape is an
     *  internal-API moving target across IDE versions. */
    private fun fakeOpenFileDescriptor(line0: Int, col0: Int): OpenFileDescriptor {
        val mock = mockk<OpenFileDescriptor>(relaxed = true)
        every { mock.line } returns line0
        every { mock.column } returns col0
        return mock
    }

    private fun fakeCompilerMessage(
        fileName: String?,
        message: String,
        navigatable: Navigatable?
    ): CompilerMessage {
        val vf = fileName?.let { fakeVirtualFile(it) }
        return proxy<CompilerMessage> { m, _ ->
            when (m) {
                "getMessage" -> message
                "getVirtualFile" -> vf
                "getNavigatable" -> navigatable
                "getCategory" -> CompilerMessageCategory.ERROR
                "getExportTextPrefix" -> ""
                "getRenderTextPrefix" -> ""
                else -> null
            }
        }
    }

    private fun fakeContext(
        errors: List<CompilerMessage>,
        warnings: List<CompilerMessage> = emptyList()
    ): CompileContext {
        return proxy<CompileContext> { m, args ->
            when (m) {
                "getMessages" -> {
                    val category = args?.get(0) as? CompilerMessageCategory
                    when (category) {
                        CompilerMessageCategory.ERROR -> errors.toTypedArray()
                        CompilerMessageCategory.WARNING -> warnings.toTypedArray()
                        else -> emptyArray<CompilerMessage>()
                    }
                }
                "getMessageCount" -> {
                    val category = args?.get(0) as? CompilerMessageCategory
                    when (category) {
                        CompilerMessageCategory.ERROR -> errors.size
                        CompilerMessageCategory.WARNING -> warnings.size
                        else -> 0
                    }
                }
                else -> null
            }
        }
    }

    private class FakeNonDescriptorNavigatable : Navigatable {
        override fun navigate(requestFocus: Boolean) {}
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }

    private fun makeError(
        fileName: String?,
        message: String,
        line0: Int? = null,
        col0: Int? = null,
        withNonDescriptorNav: Boolean = false
    ): CompilerMessage {
        val nav: Navigatable? = when {
            line0 != null && col0 != null -> fakeOpenFileDescriptor(line0, col0)
            withNonDescriptorNav -> FakeNonDescriptorNavigatable()
            else -> null
        }
        return fakeCompilerMessage(fileName, message, nav)
    }

    @Test
    fun `single error with navigatable produces file_line_col — message format`() {
        val ctx = fakeContext(
            errors = listOf(
                makeError(
                    fileName = "MyTest.java",
                    message = "cannot find symbol: method asserT(boolean)",
                    line0 = 41,
                    col0 = 4
                )
            )
        )
        val result = formatCompileErrors(
            ctx,
            target = "module-core",
            leadingLine = "BUILD FAILED — 1 compile error(s) prevented tests from starting:"
        )

        assertTrue(result.isError, "compile failure must be reported as error")
        assertTrue(
            result.content.contains("BUILD FAILED — 1 compile error(s) prevented tests from starting:"),
            "leading line must appear in content"
        )
        assertTrue(
            result.content.contains("MyTest.java:42:5 — cannot find symbol: method asserT(boolean)"),
            "per-file line:col must be rendered 1-based. Got: ${result.content}"
        )
    }

    @Test
    fun `summary leads with first error so LLM skim-read is safe`() {
        val ctx = fakeContext(
            errors = listOf(
                makeError("MyTest.java", "cannot find symbol: method asserT", line0 = 41, col0 = 4),
                makeError("MyTest.java", "';' expected", line0 = 57, col0 = 11)
            )
        )
        val result = formatCompileErrors(
            ctx,
            target = "module-core",
            leadingLine = "BUILD FAILED — 2 compile error(s) prevented tests from starting:"
        )

        assertTrue(
            result.summary.startsWith("COMPILE FAILED:"),
            "summary must lead with COMPILE FAILED so the LLM cannot confuse it with a test failure. Got: ${result.summary}"
        )
        assertTrue(
            result.summary.contains("MyTest.java:42"),
            "summary must cite the first error's file:line. Got: ${result.summary}"
        )
        assertTrue(
            result.summary.contains("cannot find symbol"),
            "summary must include the actual error message gist. Got: ${result.summary}"
        )
    }

    @Test
    fun `message with null virtualFile renders as unknown and does not throw`() {
        val ctx = fakeContext(
            errors = listOf(
                makeError(
                    fileName = null,
                    message = "unexpected compiler failure"
                )
            )
        )
        val result = formatCompileErrors(
            ctx,
            target = "module-core",
            leadingLine = "Compilation of module-core failed: 1 error(s), 0 warning(s)."
        )

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("<unknown>"),
            "null virtualFile must render as <unknown>. Got: ${result.content}"
        )
        assertTrue(result.content.contains("unexpected compiler failure"))
    }

    @Test
    fun `message without OpenFileDescriptor navigatable shows filename only`() {
        val ctx = fakeContext(
            errors = listOf(
                makeError(
                    fileName = "Foo.kt",
                    message = "type mismatch",
                    withNonDescriptorNav = true
                )
            )
        )
        val result = formatCompileErrors(
            ctx,
            target = "foo-module",
            leadingLine = "BUILD FAILED — 1 compile error(s) prevented tests from starting:"
        )

        assertTrue(
            result.content.contains("Foo.kt — type mismatch"),
            "filename-only rendering must drop line/col. Got: ${result.content}"
        )
        assertFalse(
            result.content.contains("Foo.kt:"),
            "should NOT produce 'Foo.kt:null:null' or similar. Got: ${result.content}"
        )
    }

    @Test
    fun `respects COMPILE_MAX_ERROR_MESSAGES cap at 20`() {
        val errors = (1..35).map { i ->
            makeError("File$i.java", "error number $i", line0 = i - 1, col0 = 0)
        }
        val ctx = fakeContext(errors = errors)
        val result = formatCompileErrors(
            ctx,
            target = "big-module",
            leadingLine = "BUILD FAILED — 35 compile error(s) prevented tests from starting:"
        )

        // Exactly the first 20 error lines are rendered
        assertTrue(result.content.contains("File1.java:1:1"), "first error must appear")
        assertTrue(result.content.contains("File20.java:20:1"), "20th error must appear")
        assertFalse(
            result.content.contains("File21.java"),
            "21st error must NOT appear. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("... and 15 more error(s)"),
            "overflow indicator must be present. Got: ${result.content}"
        )
    }

    @Test
    fun `compile_module leading line format also works through the helper`() {
        val ctx = fakeContext(
            errors = listOf(
                makeError("MyService.java", "incompatible types", line0 = 107, col0 = 8)
            ),
            warnings = emptyList()
        )
        val leading = "Compilation of :core failed: 1 error(s), 0 warning(s)."
        val result = formatCompileErrors(ctx, target = ":core", leadingLine = leading)

        assertTrue(
            result.content.startsWith(leading),
            "caller-provided leading line must be preserved verbatim. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("MyService.java:108:9 — incompatible types"),
            "per-file rendering must still apply. Got: ${result.content}"
        )
    }

    @Test
    fun `summary truncates long messages to stay compact`() {
        val longMessage = "x".repeat(200)
        val ctx = fakeContext(
            errors = listOf(
                makeError("A.java", longMessage, line0 = 0, col0 = 0)
            )
        )
        val result = formatCompileErrors(
            ctx,
            target = "m",
            leadingLine = "BUILD FAILED — 1 compile error(s) prevented tests from starting:"
        )
        // Summary prefix is "COMPILE FAILED: " (16 chars) plus bounded tail (<=80 chars)
        assertTrue(
            result.summary.length <= "COMPILE FAILED: ".length + 80,
            "summary must remain compact for LLM skim-read. Got length=${result.summary.length}: ${result.summary}"
        )
        assertTrue(
            result.summary.endsWith("...") || !result.summary.contains(longMessage),
            "long message must be truncated in the summary"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // buildCompileFailureResult — null-context fallback paths.
    // These matter because when the CompilationStatusListener doesn't fire
    // (e.g. build aborted early, or the callback was never delivered), the
    // tool must still return an LLM-safe message that says "BUILD FAILED",
    // not a generic compile-result wrapper. Exercises the `context == null`
    // branch that formatCompileErrors can't reach.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `buildCompileFailureResult with null context and aborted=true mentions aborted`() {
        val tool = JavaRuntimeExecTool()
        val result = tool.buildCompileFailureResult(
            context = null,
            testTarget = "com.example.FooTest",
            aborted = true
        )

        assertTrue(result.isError, "null-context failure must still be an error")
        assertTrue(
            result.content.contains("BUILD FAILED"),
            "content must lead with BUILD FAILED. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("aborted", ignoreCase = true),
            "content must mention that the build was aborted. Got: ${result.content}"
        )
        assertTrue(
            result.summary.contains("aborted", ignoreCase = true),
            "summary must mention the abort. Got: ${result.summary}"
        )
    }

    @Test
    fun `buildCompileFailureResult with null context and aborted=false mentions no compile context captured`() {
        val tool = JavaRuntimeExecTool()
        val result = tool.buildCompileFailureResult(
            context = null,
            testTarget = "com.example.FooTest",
            aborted = false
        )

        assertTrue(result.isError, "null-context failure must still be an error")
        assertTrue(
            result.content.contains("BUILD FAILED"),
            "content must lead with BUILD FAILED. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("no compile context captured", ignoreCase = true),
            "content must say 'no compile context captured' to tell the LLM the listener never fired. Got: ${result.content}"
        )
    }
}
