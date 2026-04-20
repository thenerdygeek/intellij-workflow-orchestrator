package com.workflow.orchestrator.agent.tools.framework.spring

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Structural regression test for the constant-resolution bug fix in the
 * Spring endpoints actions.
 *
 * The buggy code used `PsiAnnotationMemberValue.text` (returns the raw source
 * text, so `@GetMapping(MyConstants.USER_PATH)` yields "MyConstants.USER_PATH"
 * instead of "/users"). The fix calls
 * [com.intellij.codeInsight.AnnotationUtil.getStringAttributeValue], which
 * delegates to [com.intellij.psi.PsiConstantEvaluationHelper] and resolves
 * constant references, concatenations, and inherited interface constants.
 *
 * Full PSI-backed tests live in the existing SpringToolTest integration suite;
 * this class pins the contract at the source level so the fix cannot regress.
 */
class ConstantResolutionTest {

    @Test
    fun `SpringEndpointsAction uses AnnotationUtil getStringAttributeValue`() {
        val source = readSource("SpringEndpointsAction.kt")
        assertTrue(
            source.contains("AnnotationUtil.getStringAttributeValue"),
            "extractMappingPath must call AnnotationUtil.getStringAttributeValue"
        )
        assertFalse(
            Regex("""value\?\.text\s*\??\s*\.removeSurrounding\("\\""\)""").containsMatchIn(source),
            "Buggy `.text.removeSurrounding(\"\\\"\")` pattern still present"
        )
    }

    @Test
    fun `SpringBootEndpointsAction uses AnnotationUtil getStringAttributeValue`() {
        val source = readSource("SpringBootEndpointsAction.kt")
        assertTrue(
            source.contains("AnnotationUtil.getStringAttributeValue"),
            "extractBootMappingPath must call AnnotationUtil.getStringAttributeValue"
        )
        assertFalse(
            Regex("""value\?\.text\s*\??\s*\.removeSurrounding\("\\""\)""").containsMatchIn(source),
            "Buggy `.text.removeSurrounding(\"\\\"\")` pattern still present"
        )
    }

    @Test
    fun `SpringBootEndpointsAction resolves RequestMethod enum via referenceName`() {
        val source = readSource("SpringBootEndpointsAction.kt")
        assertTrue(
            source.contains("PsiReferenceExpression") &&
                source.contains("referenceName"),
            "extractBootRequestMethod must resolve enum via PsiReferenceExpression.referenceName"
        )
    }

    @Test
    fun `SpringEndpointsAction handles array initializer values`() {
        val source = readSource("SpringEndpointsAction.kt")
        assertTrue(
            source.contains("PsiArrayInitializerMemberValue"),
            "extractMappingPath must handle `@RequestMapping({\"/a\", \"/b\"})` via PsiArrayInitializerMemberValue"
        )
    }

    /**
     * Reads the canonical spring-framework-tool source file directly from the
     * known module layout. No tree walk — if the layout changes, the test fails
     * loudly with the exact missing path instead of silently matching a peer
     * worktree or a fixture file of the same name.
     *
     * Gradle's `:agent:test` task runs with `user.dir = <repoRoot>/agent` (the
     * submodule dir). If a different runner sets `user.dir = <repoRoot>` (e.g.
     * IntelliJ's test runner with `Working directory = $MODULE_WORKING_DIR$`)
     * the first candidate will miss and we fall back to the repo-root layout.
     * Anything else is a layout change we want to fail on loudly.
     */
    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/spring/$name"
        val moduleRootedPath = File(root, relSubdir)                      // user.dir == <repo>/agent
        val repoRootedPath = File(root, "agent/$relSubdir")               // user.dir == <repo>
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}\n" +
                    "user.dir=$userDir — module layout may have changed."
            )
        }
        return path.readText()
    }
}
