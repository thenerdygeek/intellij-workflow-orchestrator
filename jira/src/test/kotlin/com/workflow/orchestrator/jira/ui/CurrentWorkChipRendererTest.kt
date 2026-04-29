package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.settings.RepoConfig
import com.workflow.orchestrator.core.workflow.LocateResult
import com.workflow.orchestrator.core.workflow.TicketRepoBranch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import javax.swing.JPanel

class CurrentWorkChipRendererTest {

    private fun repo(name: String, root: String): RepoConfig = RepoConfig().apply {
        this.name = name
        this.bitbucketProjectKey = "PROJ"
        this.bitbucketRepoSlug = name.lowercase()
        this.localVcsRootPath = root
    }

    private fun row(
        repoName: String,
        branchDisplayId: String,
        targetBranchDisplayId: String? = null,
        isCheckedOut: Boolean = false,
        isPathMounted: Boolean = true,
        additionalMatchCount: Int = 0,
    ): TicketRepoBranch = TicketRepoBranch(
        repo = repo(repoName, "/x/$repoName"),
        branchDisplayId = branchDisplayId,
        targetBranchDisplayId = targetBranchDisplayId,
        isCheckedOut = isCheckedOut,
        isPathMounted = isPathMounted,
        additionalMatchCount = additionalMatchCount,
    )

    private fun allLabels(root: java.awt.Component): List<JLabel> {
        val out = mutableListOf<JLabel>()
        fun walk(c: java.awt.Component) {
            if (c is JLabel) out += c
            if (c is JPanel) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun labelTexts(host: JPanel): List<String> =
        allLabels(host).mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }

    @Test
    fun `Configured with 2 rows renders both with target branches and one checkout marker`() {
        val host = JPanel()
        val result = LocateResult.Configured(listOf(
            row("RepoA", "feature/ABC-123", targetBranchDisplayId = "develop", isCheckedOut = true),
            row("RepoB", "feature/ABC-123-api", targetBranchDisplayId = "main", isCheckedOut = false),
        ))

        CurrentWorkChipRenderer.render(host, result, "ABC-123", onSettingsClick = {}, onSwitchClick = {})

        val labels = labelTexts(host)
        assertTrue(labels.any { it.contains("RepoA") && it.contains("feature/ABC-123") && it.contains("→ develop") }, "RepoA row missing: $labels")
        assertTrue(labels.any { it.contains("RepoB") && it.contains("feature/ABC-123-api") && it.contains("→ main") }, "RepoB row missing: $labels")
        // ✓ marker present exactly once.
        assertEquals(1, allLabels(host).count { it.text == "✓" }, "expected exactly one row marked checked-out")
    }

    @Test
    fun `Configured with empty rows renders no-branches-found copy`() {
        val host = JPanel()
        CurrentWorkChipRenderer.render(host, LocateResult.Configured(emptyList()), "ABC-123", onSettingsClick = {}, onSwitchClick = {})

        val labels = labelTexts(host)
        assertEquals(1, labels.size, "expected only the empty-state label, got $labels")
        assertTrue(labels.single().contains("No branches found for ABC-123"), "wrong empty copy: $labels")
    }

    @Test
    fun `NoReposConfigured renders settings prompt and click invokes callback`() {
        val host = JPanel()
        var settingsClicked = 0
        CurrentWorkChipRenderer.render(host, LocateResult.NoReposConfigured, "ABC-123", onSettingsClick = { settingsClicked += 1 }, onSwitchClick = {})

        val link = allLabels(host).single()
        assertTrue(link.text.contains("Configure repositories"), "wrong copy: ${link.text}")
        // Simulate click via the registered listener.
        link.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(link, 0, 0L, 0, 0, 0, 1, false)) }
        assertEquals(1, settingsClicked)
    }

    @Test
    fun `unmounted row renders repo-not-mounted hint and no Switch link`() {
        val host = JPanel()
        val result = LocateResult.Configured(listOf(
            row("RepoA", "feature/ABC-123", isCheckedOut = false, isPathMounted = false),
        ))

        CurrentWorkChipRenderer.render(host, result, "ABC-123", onSettingsClick = {}, onSwitchClick = {})

        val labels = labelTexts(host)
        assertTrue(labels.any { it.contains("(repo not mounted)") }, "expected unmounted hint: $labels")
        assertFalse(labels.any { it == "Switch" }, "Switch link must NOT render when path is unmounted")
    }

    @Test
    fun `mounted unchecked row renders Switch link and click invokes callback with the row`() {
        val host = JPanel()
        val theRow = row("RepoA", "feature/ABC-123", isCheckedOut = false, isPathMounted = true)
        var switched: TicketRepoBranch? = null

        CurrentWorkChipRenderer.render(
            host = host,
            result = LocateResult.Configured(listOf(theRow)),
            ticketId = "ABC-123",
            onSettingsClick = {},
            onSwitchClick = { switched = it },
        )

        val switchLabel = allLabels(host).first { it.text == "Switch" }
        switchLabel.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(switchLabel, 0, 0L, 0, 0, 0, 1, false)) }
        assertEquals(theRow.branchDisplayId, switched?.branchDisplayId)
    }

    @Test
    fun `additionalMatchCount surfaces in tooltip`() {
        val host = JPanel()
        val theRow = row("RepoA", "feature/ABC-123", additionalMatchCount = 2)
        CurrentWorkChipRenderer.render(host, LocateResult.Configured(listOf(theRow)), "ABC-123", onSettingsClick = {}, onSwitchClick = {})

        val branchLabel = allLabels(host).first { it.text.contains("RepoA") && it.text.contains("feature/ABC-123") }
        assertTrue(branchLabel.toolTipText?.contains("+2 more matching branch") == true, "expected multi-match tooltip, got '${branchLabel.toolTipText}'")
    }
}
