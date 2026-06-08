package com.workflow.orchestrator.agent.tools.delegation

import com.workflow.orchestrator.agent.prompt.SystemPrompt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelegationTargetComposerTest {

    private fun entry(
        projectPath: String,
        repoName: String = projectPath.substringAfterLast('/'),
        status: String = "available",
    ) = DelegationTool.RecentEntry(
        projectPath = projectPath,
        repoName = repoName,
        status = status,
        lastOpened = null,
        busy = null,
    )

    @Test
    fun `empty inputs yield empty list`() {
        val result = DelegationTargetComposer.compose(emptyList(), emptyList())
        assertEquals(emptyList<SystemPrompt.DelegationTarget>(), result)
    }

    @Test
    fun `maps recents to targets preserving repoName and status`() {
        val result = DelegationTargetComposer.compose(
            recents = listOf(entry("/a/foo", repoName = "foo", status = "busy")),
            discovered = emptyList(),
        )
        assertEquals(listOf(SystemPrompt.DelegationTarget("foo", "busy")), result)
    }

    @Test
    fun `discovered entry sharing a recent projectPath is deduped out`() {
        val result = DelegationTargetComposer.compose(
            recents = listOf(entry("/a/foo", repoName = "foo")),
            discovered = listOf(entry("/a/foo", repoName = "foo-dup")),
        )
        assertEquals(1, result.size, "discovered duplicate of a recent path must be dropped")
        assertEquals("foo", result[0].repoName)
    }

    @Test
    fun `discovered entry with a new projectPath is appended after recents`() {
        val result = DelegationTargetComposer.compose(
            recents = listOf(entry("/a/foo", repoName = "foo")),
            discovered = listOf(entry("/b/bar", repoName = "bar")),
        )
        assertEquals(listOf("foo", "bar"), result.map { it.repoName }, "recents first, then deduped discovered")
    }

    @Test
    fun `missing-status entries are dropped from both recents and discovered`() {
        val result = DelegationTargetComposer.compose(
            recents = listOf(entry("/a/foo", repoName = "foo", status = "missing"), entry("/a/baz", repoName = "baz")),
            discovered = listOf(entry("/b/bar", repoName = "bar", status = "missing")),
        )
        assertEquals(listOf("baz"), result.map { it.repoName })
    }
}
