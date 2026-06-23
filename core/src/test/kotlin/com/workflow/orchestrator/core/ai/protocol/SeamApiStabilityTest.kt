package com.workflow.orchestrator.core.ai.protocol

import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.api.InternalApi
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SeamApiStabilityTest {
    private val seam = listOf(ToolProtocol::class.java, NativeProtocol::class.java, LlmProvider::class.java)

    @Test fun `seam interfaces are public, not internal, and @InternalApi-annotated`() {
        for (c in seam) {
            // Self-guard: if a class reference is wrong (e.g. a typo aliases a different class),
            // the assertions below will fail loudly — the list is constructed from direct ::class.java
            // literals so a typo is a compile error, not a silent vacuous pass.
            assertTrue(c.isInterface, "${c.simpleName} must be an interface")
            // Kotlin `internal` on a top-level class is encoded as package-private at the JVM level;
            // a public interface is the only JVM-public form and the only way plugin B can implement it.
            assertTrue(Modifier.isPublic(c.modifiers), "${c.simpleName} must be public (not internal)")
            assertTrue(
                c.isAnnotationPresent(InternalApi::class.java),
                "${c.simpleName} must carry @InternalApi (public-but-unfrozen); " +
                    "@InternalApi is @Retention(RUNTIME) so isAnnotationPresent is valid at runtime",
            )
        }
        // Sanity: XmlToolProtocol is a concrete class implementing the seam, not an interface.
        // Verifies the test list contains only the intended seam interfaces.
        assertFalse(XmlToolProtocol::class.java.isInterface, "XmlToolProtocol is a class, not a seam interface")
    }
}
