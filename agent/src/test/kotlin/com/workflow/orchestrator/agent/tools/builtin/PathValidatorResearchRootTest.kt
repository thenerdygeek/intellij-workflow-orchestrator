package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pins the multi-root write allow-list behavior of [PathValidator.resolveAndValidateForWrite]
 * introduced in Task 2 of the cross-IDE delegation web/research effort.
 *
 * Before Task 2 the validator accepted a single `memoryDir: String?` extra root. The refactor
 * widens it to `allowedExtraRoots: List<String>` so the new `{agentDir}/research/` parallel root
 * (Task 1: `ProjectIdentifier.researchDir`) can be threaded through write tools alongside the
 * existing memory dir without further signature churn.
 *
 * These tests target the new shape directly and also lock down the traversal-attack surface so a
 * future "let's just normalize the path differently" refactor cannot silently re-open it.
 */
class PathValidatorResearchRootTest {

    @Test
    fun `write into research dir is allowed when research dir is in allowedExtraRoots`(@TempDir tmp: Path) {
        val projectBase = Files.createDirectory(tmp.resolve("project")).toFile().canonicalPath
        val researchDir = Files.createDirectories(tmp.resolve("agent/research")).toFile().canonicalPath
        val path = "$researchDir/2026-05-24-okhttp.md"
        val (canonical, error) = PathValidator.resolveAndValidateForWrite(
            rawPath = path,
            projectBasePath = projectBase,
            allowedExtraRoots = listOf(researchDir),
        )
        assertNotNull(canonical, "Write into the research dir must be allowed")
        assertNull(error)
    }

    @Test
    fun `write outside all allowed roots is rejected`(@TempDir tmp: Path) {
        val projectBase = Files.createDirectory(tmp.resolve("project")).toFile().canonicalPath
        val researchDir = Files.createDirectories(tmp.resolve("agent/research")).toFile().canonicalPath
        val outsidePath = tmp.resolve("outside.md").toFile().canonicalPath
        val (canonical, error) = PathValidator.resolveAndValidateForWrite(
            rawPath = outsidePath,
            projectBasePath = projectBase,
            allowedExtraRoots = listOf(researchDir),
        )
        assertNull(canonical, "Write outside project + extra roots must be rejected")
        assertNotNull(error)
    }

    @Test
    fun `dotdot traversal out of research dir is rejected`(@TempDir tmp: Path) {
        val projectBase = Files.createDirectory(tmp.resolve("project")).toFile().canonicalPath
        val researchDir = Files.createDirectories(tmp.resolve("agent/research")).toFile().canonicalPath
        val attack = "$researchDir/../../../etc/passwd"
        val (canonical, error) = PathValidator.resolveAndValidateForWrite(
            rawPath = attack,
            projectBasePath = projectBase,
            allowedExtraRoots = listOf(researchDir),
        )
        assertNull(canonical, "Traversal out of the research dir via .. must be rejected")
        assertNotNull(error)
    }

    @Test
    fun `traversal into sibling sessions dir via dotdot is rejected`(@TempDir tmp: Path) {
        val projectBase = Files.createDirectory(tmp.resolve("project")).toFile().canonicalPath
        val researchDir = Files.createDirectories(tmp.resolve("agent/research")).toFile().canonicalPath
        Files.createDirectories(tmp.resolve("agent/sessions"))
        val attack = "$researchDir/../sessions/leak.txt"
        val (canonical, error) = PathValidator.resolveAndValidateForWrite(
            rawPath = attack,
            projectBasePath = projectBase,
            allowedExtraRoots = listOf(researchDir),
        )
        assertNull(canonical, "Traversal into sibling sessions/ via .. must be rejected")
        assertNotNull(error)
    }

    @Test
    fun `empty allowedExtraRoots still allows writes inside projectBase`(@TempDir tmp: Path) {
        val projectBase = Files.createDirectory(tmp.resolve("project")).toFile().canonicalPath
        val path = "$projectBase/src/main/Foo.kt"
        val (canonical, error) = PathValidator.resolveAndValidateForWrite(
            rawPath = path,
            projectBasePath = projectBase,
            allowedExtraRoots = emptyList(),
        )
        assertNotNull(canonical, "Writes inside projectBase must still work with empty extra roots")
        assertNull(error)
    }
}
